package li.selman.optimisticlocking.shared;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Thrown by the service when expected version no longer matches the current version.
 * In HTTP world, when If-Match no longer matches the current version. -> 412
 */
public class StaleStateIdentified extends ErrorResponseException {

    public StaleStateIdentified(UUID id) {
        super(
                HttpStatus.PRECONDITION_FAILED,
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.PRECONDITION_FAILED, "Aggregate of id %s is stale".formatted(id)),
                null);
    }
}
