package li.selman.optimisticlocking.shared.idempotency;

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
 * <p>This is a deliberate change from the {@code Line}-embedded mechanism it replaces: there, the
 * idempotency record was written in the very same transaction as the business mutation, so a lost
 * version CAS rolled both back together. Here, {@link #reserve} commits <em>before</em> the
 * business logic runs and {@link #complete}/{@link #abandon} commit <em>after</em> it already has
 * -- two or three separate transactions, not one. A crash between the business commit and {@link
 * #complete} leaves a stale {@code RESERVED} row behind; a retry then finds it past {@link
 * #RESERVATION_TIMEOUT}, treats it as abandoned, and genuinely re-executes -- carrying the
 * *original* (by-then-stale) {@code If-Match}, so it lands on the ordinary 412 path rather than
 * silently double-applying. Accepted here because the optimistic-locking version CAS remains the
 * real safety net underneath this mechanism, not the idempotency record itself.
 */
@ApplicationRing
@Service
public class IdempotencyService {

    /**
     * How long a {@code RESERVED} row is treated as "still genuinely in flight" before a later
     * request is instead allowed to treat it as an abandoned/crashed attempt and retry fresh.
     */
    static final Duration RESERVATION_TIMEOUT = Duration.ofSeconds(30);

    private final IdempotencyRecordRepository repository;

    IdempotencyService(IdempotencyRecordRepository repository) {
        this.repository = repository;
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
