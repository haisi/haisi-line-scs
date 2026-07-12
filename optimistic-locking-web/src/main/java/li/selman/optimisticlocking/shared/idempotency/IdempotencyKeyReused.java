package li.selman.optimisticlocking.shared.idempotency;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Thrown when an Idempotency-Key is replayed with a different payload than the one it
 * was originally recorded against. In HTTP world -> 422 Unprocessable Entity: the client
 * is reusing a key it shouldn't, rather than genuinely retrying the same operation.
 */
public class IdempotencyKeyReused extends ErrorResponseException {

    public IdempotencyKeyReused(UUID key) {
        super(
                HttpStatus.UNPROCESSABLE_CONTENT,
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "Idempotency-Key %s was already used for a different request".formatted(key)),
                null);
    }
}
