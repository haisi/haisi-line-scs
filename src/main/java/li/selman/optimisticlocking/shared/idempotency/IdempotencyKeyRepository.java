package li.selman.optimisticlocking.shared.idempotency;

import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyKeyRepository extends Repository<IdempotencyKey, UUID> {

    IdempotencyKey save(IdempotencyKey key);

    Optional<IdempotencyKey> findById(UUID id);

}
