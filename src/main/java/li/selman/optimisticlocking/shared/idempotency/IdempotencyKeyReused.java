package li.selman.optimisticlocking.shared.idempotency;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when an Idempotency-Key is replayed with a different payload than the one it
 * was originally recorded against. In HTTP world -> 422 Unprocessable Entity: the client
 * is reusing a key it shouldn't, rather than genuinely retrying the same operation.
 */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class IdempotencyKeyReused extends RuntimeException {

    public IdempotencyKeyReused(UUID key) {
        super(String.format("Idempotency-Key %s was already used for a different request", key));
    }
}
