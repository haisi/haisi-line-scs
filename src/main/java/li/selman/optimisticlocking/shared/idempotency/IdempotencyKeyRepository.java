package li.selman.optimisticlocking.shared.idempotency;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

public interface IdempotencyKeyRepository extends Repository<IdempotencyKey, UUID> {

    IdempotencyKey save(IdempotencyKey key);

    Optional<IdempotencyKey> findById(UUID id);
}
