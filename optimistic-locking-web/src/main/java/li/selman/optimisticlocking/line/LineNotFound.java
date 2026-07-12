package li.selman.optimisticlocking.line;

import java.util.UUID;
import org.jmolecules.architecture.onion.simplified.DomainRing;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Thrown when an operation targets a line id that doesn't exist.
 * In HTTP world -> 404 Not Found, rendered as an {@code application/problem+json} body (RFC 9457)
 * since this extends {@link ErrorResponseException}.
 */
@DomainRing
public class LineNotFound extends ErrorResponseException {

    public LineNotFound(UUID id) {
        super(
                HttpStatus.NOT_FOUND,
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Line %s not found".formatted(id)),
                null);
    }
}
