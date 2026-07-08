package li.selman.optimisticlocking.line.web;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The single {@code application/problem+json} (RFC 9457) entry point for every error this API
 * returns, extending {@link ResponseEntityExceptionHandler} so that its built-in handling of
 * framework-level failures (malformed request bodies, unsupported methods, ...) and of any {@link
 * ErrorResponseException} both funnel through here. All six domain exceptions in {@code line}/
 * {@code shared} ({@code LineNotFound}, {@code LineAlreadyExists}, {@code BusinessRuleViolated},
 * {@code IdempotencyKeyReused}, {@code PreconditionRequired}, {@code StaleStateIdentified}) extend
 * {@link ErrorResponseException} and are therefore already handled by the inherited {@code
 * handleErrorResponseException} -- only {@link ObjectOptimisticLockingFailureException} (a
 * framework exception we don't own, so it can't extend {@code ErrorResponseException} itself)
 * needs an explicit handler below.
 *
 * <p>Declared as our own bean -- rather than relying on {@code spring.mvc.problemdetails.enabled}
 * to activate Boot's autoconfigured equivalent, which is {@code @ConditionalOnMissingBean(
 * ResponseEntityExceptionHandler.class)} -- so this behaviour doesn't depend on that condition
 * resolving the same way across environments.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    @Nullable ResponseEntity<Object> onOptimisticLockConflict(ObjectOptimisticLockingFailureException ex, WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "The line was modified concurrently. Re-read and retry.");
        return handleExceptionInternal(ex, body, new HttpHeaders(), HttpStatus.CONFLICT, request);
    }

    /**
     * The inherited handling of a {@code @Valid}-rejected request body carries a deliberately
     * generic {@code detail} ("Invalid request content.") -- correct per RFC 9457, but useless to
     * a client that needs to know *which* field failed and why. This adds an {@code errors} array
     * (one {@code field}/{@code message} pair per Bean Validation constraint violation, e.g.
     * {@code @NotBlank}) as a ProblemDetail extension property, same envelope, more actionable body.
     */
    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = ex.getBody();
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of(
                        "field", fe.getField(), "message", Objects.requireNonNullElse(fe.getDefaultMessage(), "invalid")))
                .toList();
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }
}
