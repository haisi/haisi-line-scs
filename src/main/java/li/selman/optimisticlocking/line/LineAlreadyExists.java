package li.selman.optimisticlocking.line;

import org.jmolecules.architecture.onion.simplified.DomainRing;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Thrown when a PUT-create targets an id that already exists with different content.
 * The client stated no precondition (creation carries no If-Match), so this is a
 * genuine 409 Conflict rather than a 412.
 */
@DomainRing
public class LineAlreadyExists extends ErrorResponseException {

    public LineAlreadyExists(LineId id) {
        super(
                HttpStatus.CONFLICT,
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.CONFLICT, "Line %s already exists with different content".formatted(id)),
                null);
    }
}
