package li.selman.optimisticlocking.shared.web;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.Instant;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/**
 * Adds {@code timestamp}, {@code traceId}, and {@code spanId} as RFC 9457 extension properties to
 * every {@code application/problem+json} response this API returns. There are two call sites,
 * because there are two distinct paths a {@link ProblemDetail} can take out of this app: {@code
 * ApiExceptionHandler} (every domain exception, plus framework-level failures like a malformed
 * body) and {@code IdempotencyFilter}'s own two direct responses (a fingerprint mismatch/in-flight
 * duplicate) -- the latter runs before Spring MVC dispatch, so it can never reach the exception
 * handler at all, and would otherwise miss this enrichment entirely.
 *
 * <p>{@code traceId}/{@code spanId} are the exact same values {@code RestRequestTracer}'s log line
 * and every other log statement during this request already carry in MDC (see the "logging &amp;
 * tracing" non-functional concern) -- letting an operator paste a client-reported error body's
 * {@code traceId} straight into log search and land on every line that request produced.
 */
@Component
public class ProblemDetailEnricher {

    private final Tracer tracer;

    public ProblemDetailEnricher(Tracer tracer) {
        this.tracer = tracer;
    }

    public void enrich(ProblemDetail problemDetail) {
        problemDetail.setProperty("timestamp", Instant.now());
        Span currentSpan = tracer.currentSpan();
        if (currentSpan != null) {
            problemDetail.setProperty("traceId", currentSpan.context().traceId());
            problemDetail.setProperty("spanId", currentSpan.context().spanId());
        }
    }
}
