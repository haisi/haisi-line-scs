package li.selman.optimisticlocking.shared.idempotency;

import io.github.adr.linked.ADR;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.jmolecules.architecture.onion.simplified.ApplicationRing;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * The atomic reserve/complete/abandon operations {@code IdempotencyFilter} drives around a
 * request. The filter itself runs before Spring MVC dispatch, so there is no ambient transaction to
 * join -- every write below commits on its own, immediately (see {@link #reserve}'s Javadoc for
 * exactly how, and why it deliberately isn't itself annotated {@code @Transactional}), so a
 * concurrent request can always see it.
 *
 * <p><b>Transaction-boundary trade-off (see ADR 2 for the full write-up, including alternatives
 * considered and rejected):</b> this is a deliberate change from the {@code Line}-embedded
 * mechanism it replaces, where the idempotency record was written in the very same transaction as
 * the business mutation, so a lost version CAS rolled both back together. Here, {@link #reserve}
 * commits <em>before</em> the business logic runs and {@link #complete}/{@link #abandon} commit
 * <em>after</em> it already has -- two or three separate transactions, not one. Concretely, a crash
 * between the business transaction's commit and {@link #complete} leaves a stale {@code RESERVED}
 * row behind:
 * <ul>
 *   <li>Within {@link #RESERVATION_TIMEOUT} of that crash, a retry is told (wrongly) that the
 *       request is still in flight -- {@code 409}, via {@code IdempotencyKeyInUse} -- even though
 *       nothing is actually running anymore.
 *   <li>Past that window, a retry instead treats the reservation as abandoned and genuinely
 *       re-executes -- but carrying the client's <em>original</em> (by-then-stale) {@code If-Match},
 *       so it lands on the ordinary {@code 412} path rather than either double-applying the
 *       mutation or replaying the (never-recorded) original response.
 * </ul>
 * Accepted because the optimistic-locking version CAS remains the real safety net against a
 * double-apply, unconditionally, regardless of what happens to this table -- what this trade-off
 * costs is protocol fidelity (a client can get an unexpected status code instead of a clean replay
 * in this narrow window), never data-integrity. See {@code
 * LineControllerITTest.IdempotencyFilterBehavior#retryAfterCrashBetweenBusinessCommitAndComplete_landsOn412NotAReplay}
 * for this exact scenario reproduced end-to-end.
 *
 * <p><b>Metrics</b> (exposed via {@code /actuator/prometheus}, see {@code
 * jeap-spring-boot-monitoring-starter}): {@code idempotency.outcomes} (a counter tagged {@code
 * outcome} with one of {@code reserved}/{@code replay}/{@code fingerprint_mismatch}/{@code
 * in_progress} -- e.g. {@code rate(idempotency_outcomes_total{outcome="replay"}[5m])} shows how
 * often a replay is actually serving a retry instead of new work), {@code idempotency.records} (a
 * gauge: current row count in {@code idempotency_record}, i.e. how large the table has grown since
 * the last housekeeping sweep), and {@code idempotency.reserve.duration} (a timer around {@link
 * #reserve}: how fast the reservation lookup itself is).
 */
@ApplicationRing
@Service
@ADR(2)
public class IdempotencyService {

    /**
     * How long a {@code RESERVED} row is treated as "still genuinely in flight" before a later
     * request is instead allowed to treat it as an abandoned/crashed attempt and retry fresh.
     */
    static final Duration RESERVATION_TIMEOUT = Duration.ofSeconds(30);

    private final IdempotencyRecordRepository repository;
    private final MeterRegistry meterRegistry;
    private final Timer reserveTimer;

    IdempotencyService(IdempotencyRecordRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
        this.reserveTimer = Timer.builder("idempotency.reserve.duration")
                .description("Time to resolve an Idempotency-Key reservation attempt (lookup, plus insert or stale cleanup)")
                .register(meterRegistry);
        // A live gauge, sampled by re-running COUNT(*) on every scrape rather than a value updated
        // in the background -- perfectly fine at this app's scale/scrape frequency, but a table
        // this cheap to query is itself a property of a teaching app, not something to assume at
        // higher volume or scrape rate.
        Gauge.builder("idempotency.records", repository, IdempotencyRecordRepository::count)
                .description("Current number of rows in idempotency_record (reserved + completed, not yet pruned)")
                .register(meterRegistry);
    }

    /**
     * Claims {@code key} for a new request, or reports what should happen instead of executing it.
     * Insert-first-wins: a fresh reservation is flushed immediately (not just at commit) so a
     * concurrent racer's insert of the very same key surfaces its constraint violation right away.
     *
     * <p>Deliberately <b>not</b> wrapped in its own {@code @Transactional}: each repository call
     * below (a bare {@code Repository<T,ID>}, backed by the same {@code SimpleJpaRepository} any
     * Spring Data JPA repository is) already gets its own transaction from Spring Data itself. That
     * matters specifically for {@link IdempotencyRecordRepository#saveAndFlush} below -- catching
     * its {@link DataIntegrityViolationException} here only works because that failed flush's own,
     * separate transaction is left to roll back and complete on its own; wrapping this whole method
     * in one outer {@code REQUIRES_NEW} transaction instead would let a caught, swallowed exception
     * reach this method's normal return, at which point that outer transaction's own commit would
     * find the persistence context already poisoned by the failed flush and throw {@code
     * UnexpectedRollbackException} -- a well-known trap: a JPA persistence context that has seen a
     * constraint violation cannot simply keep going, no matter how promptly the exception is caught.
     */
    public IdempotencyReservationResult reserve(String key, String fingerprint) {
        IdempotencyReservationResult result = reserveTimer.record(() -> doReserve(key, fingerprint));
        meterRegistry.counter("idempotency.outcomes", "outcome", outcomeTag(result)).increment();
        return result;
    }

    private static String outcomeTag(IdempotencyReservationResult result) {
        return switch (result) {
            case IdempotencyReservationResult.Reserved() -> "reserved";
            case IdempotencyReservationResult.Replay(IdempotencyRecord ignored) -> "replay";
            case IdempotencyReservationResult.FingerprintMismatch() -> "fingerprint_mismatch";
            case IdempotencyReservationResult.InProgress() -> "in_progress";
        };
    }

    private IdempotencyReservationResult doReserve(String key, String fingerprint) {
        Optional<IdempotencyRecord> existing = repository.findById(key);
        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();
            if (record.getStatus() == IdempotencyStatus.COMPLETED) {
                return record.fingerprintMatches(fingerprint)
                        ? new IdempotencyReservationResult.Replay(record)
                        : new IdempotencyReservationResult.FingerprintMismatch();
            }
            if (!record.isStaleReservation(Instant.now(), RESERVATION_TIMEOUT)) {
                return new IdempotencyReservationResult.InProgress();
            }
            repository.deleteById(key); // abandoned/crashed attempt -- fall through to a fresh one
        }
        try {
            repository.saveAndFlush(new IdempotencyRecord(key, fingerprint, Instant.now()));
            return new IdempotencyReservationResult.Reserved();
        } catch (DataIntegrityViolationException e) {
            return new IdempotencyReservationResult.InProgress(); // lost the race to a concurrent reservation
        }
    }

    /** Stores the response so a later replay of the same key/fingerprint can be served without re-executing. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void complete(String key, int responseStatus, String responseHeaders, byte[] responseBody) {
        repository
                .findById(key)
                .ifPresent(record -> record.markCompleted(responseStatus, responseHeaders, responseBody, Instant.now()));
    }

    /** Releases a reservation that didn't end in success, so a retry gets a genuinely fresh attempt. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void abandon(String key) {
        repository.deleteById(key);
    }
}
