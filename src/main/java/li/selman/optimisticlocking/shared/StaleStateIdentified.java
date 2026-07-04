package li.selman.optimisticlocking.shared;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown by the service when expected version no longer matches the current version.
 * In HTTP world, when If-Match no longer matches the current version. -> 412
 */
@ResponseStatus(HttpStatus.PRECONDITION_FAILED)
public class StaleStateIdentified extends RuntimeException {

    public StaleStateIdentified(UUID id) {
        super(String.format("Aggregate of id %s is stale", id));
    }
}
