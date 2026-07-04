package li.selman.optimisticlocking.line;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a PUT-create targets an id that already exists with different content.
 * The client stated no precondition (creation carries no If-Match), so this is a
 * genuine 409 Conflict rather than a 412.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class LineAlreadyExists extends RuntimeException {

    public LineAlreadyExists(LineId id) {
        super(String.format("Line %s already exists with different content", id));
    }
}
