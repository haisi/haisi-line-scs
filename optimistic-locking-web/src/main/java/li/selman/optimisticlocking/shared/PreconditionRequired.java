package li.selman.optimisticlocking.shared;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Thrown when a write requires an If-Match precondition but the client sent none.
 * In HTTP world -> 428 Precondition Required.
 */
public class PreconditionRequired extends ErrorResponseException {

    public PreconditionRequired(UUID id) {
        super(
                HttpStatus.PRECONDITION_REQUIRED,
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.PRECONDITION_REQUIRED, "Aggregate of id %s requires an If-Match precondition".formatted(id)),
                null);
    }
}
