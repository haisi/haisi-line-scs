package li.selman.optimisticlocking.shared;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a change would violate one of the aggregate's invariants (e.g. left past right,
 * update budget exhausted). In HTTP world -> 422 Unprocessable Entity.
 */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class BusinessRuleViolated extends RuntimeException {

    public BusinessRuleViolated(String message) {
        super(message);
    }
}
