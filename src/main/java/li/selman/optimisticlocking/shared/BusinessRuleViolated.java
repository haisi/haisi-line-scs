package li.selman.optimisticlocking.shared;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Thrown when a change would violate one of the aggregate's invariants (e.g. left past right,
 * update budget exhausted). In HTTP world -> 422 Unprocessable Entity.
 */
public class BusinessRuleViolated extends ErrorResponseException {

    public BusinessRuleViolated(String message) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, message), null);
    }
}
