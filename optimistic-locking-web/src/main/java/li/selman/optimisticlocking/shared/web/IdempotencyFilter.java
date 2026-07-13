package li.selman.optimisticlocking.shared.web;

import ch.admin.bit.jeap.security.resource.semanticAuthentication.ServletSemanticAuthorization;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import li.selman.optimisticlocking.shared.idempotency.IdempotencyKeyInUse;
import li.selman.optimisticlocking.shared.idempotency.IdempotencyKeyReused;
import li.selman.optimisticlocking.shared.idempotency.IdempotencyRecord;
import li.selman.optimisticlocking.shared.idempotency.IdempotencyReservationResult;
import li.selman.optimisticlocking.shared.idempotency.IdempotencyService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Generic, protocol-level replacement for the {@code Line}-embedded idempotency mechanism it
 * replaces (see {@code CLAUDE.md}): for any endpoint opted in via {@link Idempotent} (see that
 * annotation's Javadoc for why opt-in, not opt-out), picks up {@value #HEADER_NAME}, fingerprints
 * the request (method, URI, {@code X-Partner-Id}, caller subject, and body), and either replays a
 * previously stored response, rejects a fingerprint mismatch (422) or an in-flight duplicate (409),
 * or lets a fresh request through and stores what it produced. A replay never calls {@code
 * chain.doFilter} at all, so business logic downstream (and whatever side effects it has, e.g.
 * {@code ManualOperation}) never runs a second time for a recognised retry.
 *
 * <p>Only 2xx responses get stored for replay -- see {@link IdempotencyService}'s Javadoc for why a
 * non-2xx outcome instead abandons the reservation so a retry gets a genuinely fresh attempt.
 *
 * <p>Wired into {@link ApiSecurityConfig}'s {@code apiSecurityFilterChain} after {@link
 * BusinessPartnerFilter}, so it only ever runs for a request that already cleared authentication
 * and partner-affiliation checks -- nothing is ever reserved/cached for a request that chain would
 * have rejected anyway, and {@link ServletSemanticAuthorization#getAuthenticationToken()} is safe
 * to call for the fingerprint's subject component.
 */
@Component
public class IdempotencyFilter extends OncePerRequestFilter {

    static final String HEADER_NAME = "Idempotency-Key";

    private final IdempotencyService idempotencyService;
    private final ServletSemanticAuthorization authorization;
    private final JsonMapper jsonMapper;
    private final HandlerMapping handlerMapping;

    public IdempotencyFilter(
            IdempotencyService idempotencyService,
            ServletSemanticAuthorization authorization,
            JsonMapper jsonMapper,
            @Qualifier("requestMappingHandlerMapping") HandlerMapping handlerMapping) {
        this.idempotencyService = idempotencyService;
        this.authorization = authorization;
        this.jsonMapper = jsonMapper;
        this.handlerMapping = handlerMapping;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER_NAME);
        if (key == null || !isOptedIn(request)) {
            chain.doFilter(request, response);
            return;
        }

        CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);
        String fingerprint = fingerprint(cachedRequest, cachedRequest.getCachedBody());
        IdempotencyReservationResult result = idempotencyService.reserve(key, fingerprint);

        switch (result) {
            case IdempotencyReservationResult.Replay(IdempotencyRecord record) -> replay(response, record);
            case IdempotencyReservationResult.FingerprintMismatch() ->
                writeProblemDetail(response, new IdempotencyKeyReused(key));
            case IdempotencyReservationResult.InProgress() ->
                writeProblemDetail(response, new IdempotencyKeyInUse(key));
            case IdempotencyReservationResult.Reserved() -> handleFresh(cachedRequest, response, chain, key);
        }
    }

    /**
     * Resolves which controller method would handle this request (the same lookup {@code
     * DispatcherServlet} itself does later, so this is a well-established technique -- Spring
     * Security's own {@code MvcRequestMatcher} does the same thing for the same reason: a Filter
     * runs before dispatch and has no other way to know) and checks it -- or its declaring class --
     * for {@link Idempotent}. Resolving early costs nothing extra: {@code DispatcherServlet} still
     * does its own lookup afterward regardless, it just finds the same answer again.
     */
    private boolean isOptedIn(HttpServletRequest request) {
        try {
            HandlerExecutionChain executionChain = handlerMapping.getHandler(request);
            if (executionChain == null) {
                return false;
            }
            Object handler = executionChain.getHandler();
            if (!(handler instanceof HandlerMethod handlerMethod)) {
                return false;
            }
            return handlerMethod.getMethodAnnotation(Idempotent.class) != null
                    || handlerMethod.getBeanType().isAnnotationPresent(Idempotent.class);
        } catch (Exception e) {
            return false;
        }
    }

    private void handleFresh(
            CachedBodyHttpServletRequest request, HttpServletResponse response, FilterChain chain, String key)
            throws ServletException, IOException {
        ContentCachingResponseWrapper cachingResponse = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, cachingResponse);
        } finally {
            if (cachingResponse.getStatus() / 100 == 2) {
                idempotencyService.complete(
                        key,
                        cachingResponse.getStatus(),
                        encodeHeaders(cachingResponse),
                        cachingResponse.getContentAsByteArray());
            } else {
                idempotencyService.abandon(key);
            }
            cachingResponse.copyBodyToResponse();
        }
    }

    private void replay(HttpServletResponse response, IdempotencyRecord record) throws IOException {
        response.setStatus(record.getResponseStatus());
        decodeHeaders(record.getResponseHeaders(), response);
        response.getOutputStream().write(record.getResponseBody());
    }

    private void writeProblemDetail(HttpServletResponse response, ErrorResponseException exception) throws IOException {
        response.setStatus(exception.getStatusCode().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(jsonMapper.writeValueAsString(exception.getBody()));
    }

    /** {@code name\nvalue\n} pairs -- HTTP header values can't themselves contain a raw newline. */
    private static String encodeHeaders(HttpServletResponse response) {
        StringBuilder encoded = new StringBuilder();
        for (String name : response.getHeaderNames()) {
            if (isHopByHop(name)) {
                continue; // let the container recompute these for the replayed body
            }
            for (String value : response.getHeaders(name)) {
                encoded.append(name).append('\n').append(value).append('\n');
            }
        }
        return encoded.toString();
    }

    private static void decodeHeaders(String encoded, HttpServletResponse response) {
        if (encoded.isEmpty()) {
            return;
        }
        String[] lines = encoded.split("\n", -1);
        for (int i = 0; i + 1 < lines.length; i += 2) {
            response.addHeader(lines[i], lines[i + 1]);
        }
    }

    private static boolean isHopByHop(String name) {
        return name.equalsIgnoreCase(HttpHeaders.CONTENT_LENGTH) || name.equalsIgnoreCase(HttpHeaders.TRANSFER_ENCODING);
    }

    private String fingerprint(HttpServletRequest request, byte[] body) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a mandatory JDK algorithm", e);
        }
        digest.update(request.getMethod().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(request.getRequestURI().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        String queryString = request.getQueryString();
        if (queryString != null) {
            digest.update(queryString.getBytes(StandardCharsets.UTF_8));
        }
        digest.update((byte) 0);
        String partnerId = request.getHeader(BusinessPartnerFilter.PARTNER_ID_HEADER);
        if (partnerId != null) {
            digest.update(partnerId.getBytes(StandardCharsets.UTF_8));
        }
        digest.update((byte) 0);
        // Not every caller necessarily has one (e.g. a pure client-credentials token) -- when
        // absent, every such caller shares one fingerprint component rather than the digest
        // blowing up on a null subject.
        String subject = authorization.getAuthenticationToken().getTokenSubject();
        if (subject != null) {
            digest.update(subject.getBytes(StandardCharsets.UTF_8));
        }
        digest.update((byte) 0);
        digest.update(body);
        return HexFormat.of().formatHex(digest.digest());
    }
}
