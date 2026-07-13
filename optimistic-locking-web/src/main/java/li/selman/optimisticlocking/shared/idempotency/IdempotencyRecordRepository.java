package li.selman.optimisticlocking.shared.idempotency;

import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.Optional;
import org.jmolecules.architecture.onion.simplified.DomainRing;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

@DomainRing
public interface IdempotencyRecordRepository extends Repository<IdempotencyRecord, String> {

    IdempotencyRecord save(IdempotencyRecord record);

    /**
     * Flushes immediately rather than at surrounding-transaction commit, so a concurrent insert of
     * the same (PK) key surfaces its constraint violation synchronously to the caller -- see
     * {@link IdempotencyService#reserve}, which relies on catching it right there.
     */
    IdempotencyRecord saveAndFlush(IdempotencyRecord record);

    Optional<IdempotencyRecord> findById(String id);

    void deleteById(String id);

    /** Backs the {@code idempotency.records} gauge -- see {@link IdempotencyService}. */
    long count();

    /**
     * Housekeeping: called by {@code IdempotencyHousekeeping}'s ShedLock-guarded cron. A plain
     * derived {@code deleteByCreatedAtBefore} has no backing method on {@code SimpleJpaRepository}
     * for Spring Data's transactional proxy to find {@code @Transactional} on, so (unlike every
     * other method above, which all match a real, already-annotated {@code SimpleJpaRepository}
     * method) it silently runs with no transaction at all and fails outright -- {@code @Modifying}
     * plus an explicit {@code @Transactional} right here on the derived method is what a bulk
     * delete like this actually needs.
     */
    @Modifying
    @Transactional
    @Query("delete from IdempotencyRecord r where r.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
