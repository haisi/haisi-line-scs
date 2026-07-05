package li.selman.optimisticlocking.line;

import java.util.UUID;
import org.jmolecules.architecture.onion.simplified.DomainRing;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when an operation targets a line id that doesn't exist.
 * In HTTP world -> 404 Not Found.
 */
@DomainRing
@ResponseStatus(HttpStatus.NOT_FOUND)
public class LineNotFound extends RuntimeException {

    public LineNotFound(UUID id) {
        super(String.format("Line %s not found", id));
    }
}
