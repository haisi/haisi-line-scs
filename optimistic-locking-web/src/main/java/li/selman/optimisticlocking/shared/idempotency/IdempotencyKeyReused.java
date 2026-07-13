package li.selman.optimisticlocking.shared.idempotency;

import org.jmolecules.architecture.onion.simplified.DomainRing;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * An {@code Idempotency-Key} was reused for a request that fingerprints differently from the one
 * it was originally recorded against -- the client is reusing a key it shouldn't, rather than
 * genuinely retrying the same request. Constructed directly by {@code IdempotencyFilter} (which
 * runs before Spring MVC dispatch, so nothing throws/catches this the way a domain exception
 * normally would) purely to reuse its {@link #getBody()} ProblemDetail in the same RFC 9457 shape
 * every other error response in this app uses.
 */
@DomainRing
public class IdempotencyKeyReused extends ErrorResponseException {

    public IdempotencyKeyReused(String key) {
        super(
                HttpStatus.UNPROCESSABLE_CONTENT,
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "Idempotency-Key %s was already used for a different request".formatted(key)),
                null);
    }
}
