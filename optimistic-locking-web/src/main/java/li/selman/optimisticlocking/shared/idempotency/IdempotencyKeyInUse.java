package li.selman.optimisticlocking.shared.idempotency;

import org.jmolecules.architecture.onion.simplified.DomainRing;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * A request carrying this {@code Idempotency-Key} is already being handled by another,
 * concurrent request -- there is nothing to replay yet (it hasn't completed), so this is a genuine
 * conflict rather than a 422 fingerprint mismatch. Constructed directly by {@code
 * IdempotencyFilter}; see {@link IdempotencyKeyReused} for why this isn't thrown/caught the usual way.
 */
@DomainRing
public class IdempotencyKeyInUse extends ErrorResponseException {

    public IdempotencyKeyInUse(String key) {
        super(
                HttpStatus.CONFLICT,
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.CONFLICT,
                        "A request with Idempotency-Key %s is already being processed".formatted(key)),
                null);
    }
}
