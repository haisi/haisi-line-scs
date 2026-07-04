package li.selman.optimisticlocking.shared;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a write requires an If-Match precondition but the client sent none.
 * In HTTP world -> 428 Precondition Required.
 */
@ResponseStatus(HttpStatus.PRECONDITION_REQUIRED)
public class PreconditionRequired extends RuntimeException {

    public PreconditionRequired(UUID id) {
        super(String.format("Aggregate of id %s requires an If-Match precondition", id));
    }
}
