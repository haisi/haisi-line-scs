package li.selman.optimisticlocking.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link IdempotencyHousekeeping#deleteExpiredRecords()} is package-private, same package as
 * {@link BusinessPartnerFilterTest} for the same reason -- but unlike that pure-unit test, this one
 * needs a real database and the real {@code @SchedulerLock}-wrapped bean (ShedLock's default {@code
 * PROXY_METHOD} intercept mode proxies the bean class itself, so calling the method on the
 * Spring-injected instance below genuinely exercises the lock, not just the plain method body).
 */
@SpringBootTest
class IdempotencyHousekeepingTest {

    @Autowired
    IdempotencyHousekeeping housekeeping;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM idempotency_record");
        jdbcTemplate.update("DELETE FROM shedlock WHERE name = 'idempotency-cleanup'");
    }

    @Test
    void deletesOnlyRecordsOlderThanRetention_andTakesTheShedLock() {
        Instant old = Instant.now().minus(IdempotencyHousekeeping.RETENTION).minus(1, ChronoUnit.HOURS);
        Instant fresh = Instant.now();
        insertRecord("expired-key", old);
        insertRecord("fresh-key", fresh);

        housekeeping.deleteExpiredRecords();

        assertThat(countRecords("expired-key")).isZero();
        assertThat(countRecords("fresh-key")).isEqualTo(1);

        // Proves the @SchedulerLock actually ran (ShedLock's JDBC provider leaves the lock row
        // behind, updated rather than deleted, once a lock has been acquired at least once), not
        // just that the plain method body executed.
        Integer lockRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shedlock WHERE name = ?", Integer.class, "idempotency-cleanup");
        assertThat(lockRows).isEqualTo(1);
    }

    private void insertRecord(String id, Instant createdAt) {
        jdbcTemplate.update(
                "INSERT INTO idempotency_record (id, fingerprint, status, created_at) VALUES (?, ?, 'RESERVED', ?)",
                id,
                "fingerprint-" + id,
                createdAt);
    }

    private int countRecords(String id) {
        Integer count =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM idempotency_record WHERE id = ?", Integer.class, id);
        return count == null ? 0 : count;
    }
}
