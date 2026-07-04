package li.selman.optimisticlocking.line.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Two requests raced with neither stating an If-Match precondition, and the aggregate's own
 * version CAS caught the collision at commit time. Since nobody promised a specific version,
 * this is 409 Conflict -- distinct from 412, which is for a precondition the client *did* state.
 */
@RestControllerAdvice
class OptimisticLockingExceptionHandler {

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> onConflict() {
        var body = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "The line was modified concurrently. Re-read and retry.");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }
}
