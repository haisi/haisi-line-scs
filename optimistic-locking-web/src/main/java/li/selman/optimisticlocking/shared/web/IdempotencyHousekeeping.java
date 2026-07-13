package li.selman.optimisticlocking.shared.web;

import java.time.Duration;
import java.time.Instant;
import li.selman.optimisticlocking.shared.idempotency.IdempotencyRecordRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes {@code idempotency_record} rows older than {@link #RETENTION} -- otherwise the table
 * grows forever, since nothing else ever removes a row once {@code IdempotencyFilter} writes it.
 * {@code @SchedulerLock} (ShedLock) ensures only one instance of this app runs the cleanup at a
 * time when scaled horizontally, all sharing one database.
 */
@Component
class IdempotencyHousekeeping {

    /**
     * How long a replay stays available. 24h comfortably covers realistic client retry windows
     * (e.g. Stripe's own Idempotency-Key guidance) without keeping rows around indefinitely.
     */
    static final Duration RETENTION = Duration.ofDays(1);

    private final IdempotencyRecordRepository repository;

    IdempotencyHousekeeping(IdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "0 0 * * * *")
    @SchedulerLock(name = "idempotency-cleanup", lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    void deleteExpiredRecords() {
        repository.deleteByCreatedAtBefore(Instant.now().minus(RETENTION));
    }
}
