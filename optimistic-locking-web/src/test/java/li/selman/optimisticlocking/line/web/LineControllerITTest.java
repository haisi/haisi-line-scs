package li.selman.optimisticlocking.line.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.DEFINED_PORT;

import ch.admin.bit.jeap.security.resource.token.JeapAuthenticationContext;
import ch.admin.bit.jeap.security.test.jws.JwsBuilderFactory;
import ch.admin.bit.jeap.security.test.resource.configuration.JeapOAuth2IntegrationTestResourceConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import li.selman.optimisticlocking.line.LineAuthorization;
import li.selman.optimisticlocking.line.LineFixture;
import li.selman.optimisticlocking.line.LineRepository;
import li.selman.optimisticlocking.line.LineService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Full end-to-end test: goes through the real jeap-spring-boot-security-starter filter chain, so
 * every request needs a genuine, signed bearer token. {@link JeapOAuth2IntegrationTestResourceConfiguration}
 * registers a mock JWKS endpoint inside this same app; the {@code jwk-set-uri} override below points the
 * resource server at it (see src/test/resources/application.properties for the matching issuer).
 *
 * <p>Uses a fixed port rather than {@code RANDOM_PORT}: the resource server's {@code JwtDecoder}
 * (and the {@code jwk-set-uri} it's built from) is bound during regular singleton creation, well
 * before the embedded web server publishes {@code local.server.port} in {@code finishRefresh()} --
 * so {@code ${local.server.port}} would never resolve in time. This mirrors the jeap security
 * starter's own {@code SemanticRoleAuthorizationWebmvcIT}.
 */
@SpringBootTest(
        webEnvironment = DEFINED_PORT,
        properties = {
            "server.port=18089",
            "jeap.security.oauth2.resourceserver.authorization-server.jwk-set-uri="
                    + "http://localhost:18089/.well-known/jwks.json"
        })
@AutoConfigureRestTestClient
@Import(JeapOAuth2IntegrationTestResourceConfiguration.class)
class LineControllerITTest {

    // We purposefully do not reference the constant in LineAuthorization class
    private static final String LINE_READ = "wvs_@line_#read";
    private static final String LINE_CREATE = "wvs_@line_#create";
    private static final String LINE_DELETE = "wvs_@line_#delete";

    @Autowired
    LineRepository lineRepository;

    @Autowired
    LineService lineService;

    @Autowired
    RestTestClient client;

    @Autowired
    JwsBuilderFactory jwsBuilderFactory;

    @Autowired
    JdbcTemplate jdbcTemplate;

    /**
     * Authenticated as the sole business partner every {@link LineFixture} line belongs to, holding both
     * roles under test -- so the pre-existing (non-authorization-focused) tests below don't incidentally
     * start testing authorization too. Tests that specifically exercise a role/ownership rule mint their
     * own narrower token via {@link #authedAs}.
     *
     * <p>Carries a default {@code X-Partner-Id: acme} header: every business-partner-scoped caller
     * must always declare which partner it acts as (only a user-independent caller like AdBAZG may
     * omit it), so the pre-existing tests below need it on every request too.
     */
    RestTestClient authedClient;

    /** The bearer token behind {@link #authedClient}, without its baked-in {@code X-Partner-Id} default. */
    private String authedToken;

    public static final String BP_ID_COOP_PRONTO = "coop_pronto";
    public static final String BP_ID_COOP_JUMBO = "coop_jumbo";

    public static final String BP_ID_MGB_MIGROLINO = "mgb_migrolino";
    public static final String BP_ID_MGB_DENNER = "mgb_denner";

    /** Can READ and DELETE ALL Lines, but not create them*/
    private String TOKEN_BAZG_EMPLOYEE;

    /** Is in GP Coop Pronto AND Coop Jumbo. However, Pronto GP is not authorized for line service. */
    private String TOKEN_COOP_EMPLOYEE;

    /**
     *  Employee of the Migros Genossenschafts Bund. In GP Migrolino and Denner.
     *  Both are authorized for line service. However, the user must switch between GPs to view and edit
     *  the lines of Denner or Migrolino
     */
    private String TOKEN_MGB_EMPLOYEE;

    /** Has neither GP nor User Roles */
    private String TOKEN_EMPTY;

    @BeforeEach
    void authenticate() {
        TOKEN_BAZG_EMPLOYEE = jwsBuilderFactory
                .createValidForFixedLongPeriodBuilder("test-subject", JeapAuthenticationContext.USER)
                .withUserRoles(LINE_DELETE, LINE_READ)
                .build()
                .serialize();

        TOKEN_COOP_EMPLOYEE = jwsBuilderFactory
                .createValidForFixedLongPeriodBuilder("test-subject", JeapAuthenticationContext.USER)
                .withBusinessPartnerRoles(BP_ID_COOP_JUMBO, LineAuthorization.CREATE_ROLE, LineAuthorization.READ_ROLE)
                .withBusinessPartnerRoles(BP_ID_COOP_PRONTO, "something_@else_#READ")
                .build()
                .serialize();

        TOKEN_MGB_EMPLOYEE = jwsBuilderFactory
                .createValidForFixedLongPeriodBuilder("test-subject", JeapAuthenticationContext.USER)
                .withBusinessPartnerRoles(BP_ID_MGB_DENNER, LineAuthorization.CREATE_ROLE, LineAuthorization.READ_ROLE)
                .withBusinessPartnerRoles(
                        BP_ID_MGB_MIGROLINO, LineAuthorization.CREATE_ROLE, LineAuthorization.READ_ROLE)
                .build()
                .serialize();

        TOKEN_EMPTY = jwsBuilderFactory
                .createValidForFixedLongPeriodBuilder("test-subject", JeapAuthenticationContext.USER)
                .build()
                .serialize();

        // line_#create for the fixture's partner (business-partner-scoped) plus line_#delete as a
        // regular/internal user (user-independent) -- these are deliberately two different claims.
        authedToken = jwsBuilderFactory
                .createValidForFixedLongPeriodBuilder("test-subject", JeapAuthenticationContext.USER)
                .withBusinessPartnerRoles(
                        LineFixture.BUSINESS_PARTNER_ID, LineAuthorization.CREATE_ROLE, LineAuthorization.READ_ROLE)
                .withBusinessPartnerRoles("Roche", LineAuthorization.DELETE_ROLE)
                .withUserRoles(LINE_DELETE)
                .build()
                .serialize();
        authedClient = client.mutate()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + authedToken)
                .defaultHeader("X-Partner-Id", LineFixture.BUSINESS_PARTNER_ID)
                .build();
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM line");
        jdbcTemplate.update("DELETE FROM left_point");
        jdbcTemplate.update("DELETE FROM right_point");
    }

    private RestTestClient authedAs(String bearerToken) {
        return client.mutate()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .build();
    }

    private String tokenFor(String businessPartnerId, String... businessPartnerRoles) {
        return jwsBuilderFactory
                .createValidForFixedLongPeriodBuilder("test-subject", JeapAuthenticationContext.USER)
                .withBusinessPartnerRoles(businessPartnerId, businessPartnerRoles)
                .build()
                .serialize();
    }

    private String tokenWithUserRoles(String... userRoles) {
        return jwsBuilderFactory
                .createValidForFixedLongPeriodBuilder("test-subject", JeapAuthenticationContext.USER)
                .withUserRoles(userRoles)
                .build()
                .serialize();
    }

    private String tokenWithNoRoles() {
        return jwsBuilderFactory
                .createValidForFixedLongPeriodBuilder("test-subject", JeapAuthenticationContext.USER)
                .build()
                .serialize();
    }

    @Nested
    class Delete {

        /**
         * <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-9.3.5">RFC 9110 §9.3.5
         * DELETE</a>: "if a DELETE method request to a target resource returns a status code of
         * 2xx, 404, or 409, a client can be confident that the representation has been deleted".
         * We choose the 2xx branch of that guarantee: a retry of an already-completed delete is a
         * no-op 204, not a 404 -- deliberately more lenient than the RFC requires, since If-Match
         * was never stated on this call.
         */
        @Test
        void isIdempotent() {
            // given
            UUID id = LineFixture.CONSTANT_ID.id();
            lineRepository.save(LineFixture.newBuilder().id(id).build());
            authedClient.get().uri("/lines/{id}", id).exchange().expectStatus().isOk();

            RestTestClient.ResponseSpec exchange1 =
                    authedClient.delete().uri("/lines/{id}", id).exchange();
            RestTestClient.ResponseSpec exchange2 =
                    authedClient.delete().uri("/lines/{id}", id).exchange();

            exchange1.expectAll(spec -> spec.expectStatus().isNoContent());
            exchange2.expectAll(spec -> spec.expectStatus().isNoContent());

            authedClient.get().uri("/lines/{id}", id).exchange().expectStatus().isNotFound();
        }

        /**
         * <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-13.1.1">RFC 9110 §13.1.1
         * If-Match</a>: "If the condition is false... the server SHOULD respond with a 412
         * (Precondition Failed) status code."
         */
        @Test
        void preconditionFailOnStaleData() {
            // given
            UUID id = UUID.randomUUID();
            lineRepository.save(LineFixture.newBuilder().id(id).lockVersion(1).build());

            // when: If-Match carries a version that no longer matches the current one
            authedClient
                    .delete()
                    .uri("/lines/{id}", id)
                    .header(HttpHeaders.IF_MATCH, "\"999\"")
                    .exchange()
                    .expectStatus()
                    .isEqualTo(412);

            // then: the write never happened
            authedClient.get().uri("/lines/{id}", id).exchange().expectStatus().isOk();
        }

        /**
         * <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-13.1.1">RFC 9110 §13.1.1
         * If-Match</a>: "If the field-value is '*'... the condition is false if the origin server
         * does not have a current representation for the target resource" -- so for a resource
         * that still exists, {@code If-Match: *} always matches, regardless of its actual version.
         */
        @Test
        void ifMatchWildcard_deletesRegardlessOfVersion() {
            UUID id = UUID.randomUUID();
            lineRepository.save(LineFixture.newBuilder().id(id).lockVersion(1).build());

            authedClient
                    .delete()
                    .uri("/lines/{id}", id)
                    .header(HttpHeaders.IF_MATCH, "*")
                    .exchange()
                    .expectStatus()
                    .isNoContent();

            authedClient.get().uri("/lines/{id}", id).exchange().expectStatus().isNotFound();
        }
    }

    /**
     * <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-9.3.4">RFC 9110 § 9.3.4 PUT</a>
     */
    @Nested
    class PutCreate {

        /**
         * <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-9.3.4">RFC 9110 §9.3.4
         * PUT</a>: "If the target resource does not have a current representation and the PUT
         * successfully creates one, then the origin server MUST inform the user agent by sending
         * a 201 (Created) response." That same paragraph continues: "If a 201 response is
         * expected, the origin server MUST send the Location header field", identifying the new
         * resource (<a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-10.2.2">§10.2.2</a>).
         */
        @Test
        void createsANewLine_returns201Created() {
            UUID id = UUID.randomUUID();

            authedClient
                    .put()
                    .uri("/lines/{id}", id)
                    .body(new CreateLineRequest(1, 5, LineFixture.BUSINESS_PARTNER_ID))
                    .exchange()
                    .expectStatus()
                    .isCreated()
                    .expectHeader()
                    .valueEquals("ETag", "\"1\"")
                    .expectHeader()
                    .value(HttpHeaders.LOCATION, location -> assertThat(location)
                            .endsWith("/lines/" + id))
                    .expectBody()
                    .jsonPath("$.left")
                    .isEqualTo(1)
                    .jsonPath("$.right")
                    .isEqualTo(5);

            authedClient.get().uri("/lines/{id}", id).exchange().expectStatus().isOk();
        }

        /**
         * <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-9.2.2">RFC 9110 §9.2.2
         * Idempotent Methods</a>: PUT is defined to be idempotent, so a byte-for-byte identical
         * retry must not be treated as a second, conflicting write.
         */
        @Test
        void creatingSameLineTwice_isIdempotent() {
            UUID id = UUID.randomUUID();

            authedClient
                    .put()
                    .uri("/lines/{id}", id)
                    .body(new CreateLineRequest(1, 5, LineFixture.BUSINESS_PARTNER_ID))
                    .exchange()
                    .expectStatus()
                    .isCreated(); //

            // retry with identical content -> no-op, not a second line
            authedClient
                    .put()
                    .uri("/lines/{id}", id)
                    .body(new CreateLineRequest(1, 5, LineFixture.BUSINESS_PARTNER_ID))
                    .exchange()
                    .expectStatus()
                    .isOk();
        }

        /**
         * <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-15.5.10">RFC 9110 §15.5.10
         * 409 Conflict</a>: "used in situations where the user might be able to resolve the
         * conflict and resubmit the request" -- here, a differing body for an existing id, with no
         * precondition ever stated, is exactly such a conflict (as opposed to 412, which requires
         * a stated but stale If-Match).
         */
        @Test
        void creatingWithDifferentBodyForExistingId_returns409Conflict() {
            UUID id = UUID.randomUUID();

            authedClient
                    .put()
                    .uri("/lines/{id}", id)
                    .body(new CreateLineRequest(1, 5, LineFixture.BUSINESS_PARTNER_ID))
                    .exchange()
                    .expectStatus()
                    .isCreated();

            // no precondition was stated, and the content genuinely differs -> 409, not 412
            authedClient
                    .put()
                    .uri("/lines/{id}", id)
                    .body(new CreateLineRequest(2, 6, LineFixture.BUSINESS_PARTNER_ID))
                    .exchange()
                    .expectStatus()
                    .isEqualTo(409);

            authedClient
                    .get()
                    .uri("/lines/{id}", id)
                    .exchange()
                    .expectBody()
                    .jsonPath("$.left")
                    .isEqualTo(1)
                    .jsonPath("$.right")
                    .isEqualTo(5);
        }

        /**
         * <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-15.5.21">RFC 9110 §15.5.21
         * 422 Unprocessable Content</a>: the request syntax is fine, but the instructed state
         * (left past right) violates a domain invariant the server can't carry out.
         */
        @Test
        void creatingWithLeftGreaterThanRight_returns422() {
            UUID id = UUID.randomUUID();

            authedClient
                    .put()
                    .uri("/lines/{id}", id)
                    .body(new CreateLineRequest(5, 1, LineFixture.BUSINESS_PARTNER_ID))
                    .exchange()
                    .expectStatus()
                    .isEqualTo(422);
        }
    }

    @Nested
    class PutMove {

        /**
         * <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-8.8.3">RFC 9110 §8.8.3
         * ETag</a>: every successful state change yields a new representation, so it MUST get a
         * new entity-tag -- callers rely on that to build their next If-Match.
         */
        @Test
        void movesLeftPoint_returns200WithNewETag() {
            UUID id = UUID.randomUUID();
            lineRepository.save(
                    LineFixture.newBuilder().id(id).line(3, 9).lockVersion(1).build());

            // even though only the child LeftPoint row changes, the root's version bumps too
            authedClient
                    .put()
                    .uri("/lines/{id}/left", id)
                    .header(HttpHeaders.IF_MATCH, "\"1\"")
                    .body(new MoveRequest(2))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectHeader()
                    .valueEquals(HttpHeaders.ETAG, "\"2\"")
                    .expectBody()
                    .jsonPath("$.left")
                    .isEqualTo(5);
        }

        /**
         * 428 Precondition Required is defined by <a
         * href="https://www.rfc-editor.org/rfc/rfc6585.html#section-3">RFC 6585 §3</a>, not RFC
         * 9110 -- it plugs the gap RFC 9110 §13.1.1 leaves open by never mandating that a client
         * send If-Match at all. We choose to require it for a move, since without it two racing
         * writers can never be told apart from a legitimate lost-update.
         */
        @Test
        void missingIfMatch_returns428PreconditionRequired() {
            UUID id = UUID.randomUUID();
            lineRepository.save(
                    LineFixture.newBuilder().id(id).line(3, 9).lockVersion(1).build());

            authedClient
                    .put()
                    .uri("/lines/{id}/left", id)
                    .body(new MoveRequest(2))
                    .exchange()
                    .expectStatus()
                    .isEqualTo(428);
        }

        /**
         * <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-13.1.1">RFC 9110 §13.1.1
         * If-Match</a>: comparison is strong-only (<a
         * href="https://www.rfc-editor.org/rfc/rfc9110.html#section-8.8.3.2">§8.8.3.2</a>), so a
         * stated version that no longer strong-matches the current one MUST NOT be applied, and
         * the server responds 412.
         */
        @Test
        void staleIfMatch_returns412PreconditionFailed() {
            UUID id = UUID.randomUUID();
            lineRepository.save(
                    LineFixture.newBuilder().id(id).line(3, 9).lockVersion(1).build());

            // someone else's move lands first, bumping the version to "2"
            authedClient
                    .put()
                    .uri("/lines/{id}/left", id)
                    .header(HttpHeaders.IF_MATCH, "\"1\"")
                    .body(new MoveRequest(1))
                    .exchange()
                    .expectStatus()
                    .isOk();

            // a stale tab retries against the version it originally read
            authedClient
                    .put()
                    .uri("/lines/{id}/left", id)
                    .header(HttpHeaders.IF_MATCH, "\"1\"")
                    .body(new MoveRequest(1))
                    .exchange()
                    .expectStatus()
                    .isEqualTo(412);
        }

        /**
         * <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-13.1.1">RFC 9110 §13.1.1
         * If-Match</a>: "If the field-value is '*'... the condition is false if the origin server
         * does not have a current representation for the target resource" -- for a resource that
         * still exists, {@code If-Match: *} always matches, bypassing the version check entirely.
         */
        @Test
        void ifMatchWildcard_bypassesVersionCheck() {
            UUID id = UUID.randomUUID();
            lineRepository.save(
                    LineFixture.newBuilder().id(id).line(3, 9).lockVersion(1).build());

            // someone else's move lands first, bumping the version to "2"
            authedClient
                    .put()
                    .uri("/lines/{id}/left", id)
                    .header(HttpHeaders.IF_MATCH, "\"1\"")
                    .body(new MoveRequest(1))
                    .exchange()
                    .expectStatus()
                    .isOk();

            // a client that never read the current version, but still wants "whatever it is now"
            authedClient
                    .put()
                    .uri("/lines/{id}/left", id)
                    .header(HttpHeaders.IF_MATCH, "*")
                    .body(new MoveRequest(1))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectHeader()
                    .valueEquals(HttpHeaders.ETAG, "\"3\"");
        }

        /**
         * <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-15.5.21">RFC 9110 §15.5.21
         * 422 Unprocessable Content</a>: syntactically valid request, semantically impossible
         * instruction (would push left past right).
         */
        @Test
        void movingLeftPastRight_returns422UnprocessableEntity() {
            UUID id = UUID.randomUUID();
            lineRepository.save(
                    LineFixture.newBuilder().id(id).line(8, 9).lockVersion(1).build());

            authedClient
                    .put()
                    .uri("/lines/{id}/left", id)
                    .header(HttpHeaders.IF_MATCH, "\"1\"")
                    .body(new MoveRequest(2)) // 8 + 2 = 10 > 9
                    .exchange()
                    .expectStatus()
                    .isEqualTo(422);

            // the rejected write never touched the aggregate
            authedClient.get().uri("/lines/{id}", id).exchange().expectHeader().valueEquals(HttpHeaders.ETAG, "\"1\"");
        }

        /**
         * <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-15.5.21">RFC 9110 §15.5.21
         * 422 Unprocessable Content</a>: same status as the invariant above, different rule (this
         * app's update budget, not a positional constraint).
         */
        @Test
        void exceedingUpdateLimit_returns422() {
            UUID id = UUID.randomUUID();
            lineRepository.save(LineFixture.newBuilder()
                    .id(id)
                    .line(3, 9)
                    .lockVersion(1)
                    .leftUpdates(5)
                    .build());

            authedClient
                    .put()
                    .uri("/lines/{id}/left", id)
                    .header(HttpHeaders.IF_MATCH, "\"1\"")
                    .body(new MoveRequest(1))
                    .exchange()
                    .expectStatus()
                    .isEqualTo(422);
        }

        /**
         * {@code Idempotency-Key} is not part of RFC 9110 -- it's the separate <a
         * href="https://www.ietf.org/archive/id/draft-ietf-httpapi-idempotency-key-header-05.html">IETF
         * HTTP-API Idempotency-Key draft</a>. It supplements PUT's built-in idempotency (RFC 9110
         * §9.2.2) for a request whose safety would otherwise depend on client-side version
         * tracking alone: here, a retry replays the original response even though its own If-Match
         * has since gone stale.
         */
        @Test
        void retryingWithSameIdempotencyKey_isANoOp() {
            UUID id = UUID.randomUUID();
            lineRepository.save(
                    LineFixture.newBuilder().id(id).line(3, 9).lockVersion(1).build());
            String idempotencyKey = UUID.randomUUID().toString();

            authedClient
                    .put()
                    .uri("/lines/{id}/left", id)
                    .header(HttpHeaders.IF_MATCH, "\"1\"")
                    .header("Idempotency-Key", idempotencyKey)
                    .body(new MoveRequest(2))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectHeader()
                    .valueEquals(HttpHeaders.ETAG, "\"2\"");

            // retry: same key, same (now stale) If-Match -- must replay, not re-apply or 412
            authedClient
                    .put()
                    .uri("/lines/{id}/left", id)
                    .header(HttpHeaders.IF_MATCH, "\"1\"")
                    .header("Idempotency-Key", idempotencyKey)
                    .body(new MoveRequest(2))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectHeader()
                    .valueEquals(HttpHeaders.ETAG, "\"2\""); // unchanged: no second bump
        }

        /**
         * Not RFC 9110 either (see {@link #retryingWithSameIdempotencyKey_isANoOp}): reusing an
         * {@code Idempotency-Key} for a request with a different fingerprint is the one case the
         * idempotency-key draft treats as a client error rather than a replay, which this app
         * reports as 422.
         */
        @Test
        void reusingIdempotencyKeyWithDifferentBody_returns422() {
            UUID id = UUID.randomUUID();
            lineRepository.save(
                    LineFixture.newBuilder().id(id).line(3, 9).lockVersion(1).build());
            String idempotencyKey = UUID.randomUUID().toString();

            authedClient
                    .put()
                    .uri("/lines/{id}/left", id)
                    .header(HttpHeaders.IF_MATCH, "\"1\"")
                    .header("Idempotency-Key", idempotencyKey)
                    .body(new MoveRequest(2))
                    .exchange()
                    .expectStatus()
                    .isOk();

            authedClient
                    .put()
                    .uri("/lines/{id}/left", id)
                    .header(HttpHeaders.IF_MATCH, "\"2\"")
                    .header("Idempotency-Key", idempotencyKey)
                    .body(new MoveRequest(3)) // same key, different payload
                    .exchange()
                    .expectStatus()
                    .isEqualTo(422);
        }

        /**
         * <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-13.1.1">RFC 9110 §13.1.1
         * If-Match</a> plus the forced-increment version CAS: racing writers that all stated the
         * same (now-shared) precondition can only ever have one winner. Whether a loser sees 409
         * or 412 depends on scheduling, not on any RFC requirement -- both are "the version I
         * cared about is no longer current".
         */
        @Test
        void concurrentMovesWithoutPrecondition_onlyOneSucceeds() throws Exception {
            UUID id = UUID.randomUUID();
            lineRepository.save(
                    LineFixture.newBuilder().id(id).line(3, 9).lockVersion(1).build());

            // TODO get pool size from config
            int threadCount = 8; // <= default Hikari pool size, so none serialize on a connection wait
            CyclicBarrier barrier = new CyclicBarrier(threadCount);
            ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            List<Integer> statuses;
            try {
                List<Callable<Integer>> racers = new ArrayList<>();
                for (int i = 0; i < threadCount; i++) {
                    racers.add(() -> {
                        barrier.await();
                        AtomicInteger status = new AtomicInteger();
                        authedClient
                                .put()
                                .uri("/lines/{id}/left", id)
                                .header(HttpHeaders.IF_MATCH, "\"1\"")
                                .body(new MoveRequest(1))
                                .exchange()
                                .expectStatus()
                                .value(status::set);
                        return status.get();
                    });
                }
                List<Future<Integer>> futures = pool.invokeAll(racers);
                statuses = new ArrayList<>();
                for (Future<Integer> future : futures) {
                    statuses.add(future.get());
                }
            } finally {
                pool.shutdown();
            }

            long successes = statuses.stream().filter(status -> status == 200).count();
            // A racing writer that stated no version at all loses at the version CAS -> 409.
            // A racing writer whose manual precondition check ran after the winner had already
            // committed sees its stated version is already stale -> 412. Both are correct
            // outcomes of the very same race; which split occurs depends on scheduling.
            long conflicts = statuses.stream()
                    .filter(status -> status == 409 || status == 412)
                    .count();

            assertThat(successes).isEqualTo(1);
            assertThat(conflicts).isEqualTo(threadCount - 1);

            // exactly one of the identical moves took effect
            authedClient
                    .get()
                    .uri("/lines/{id}", id)
                    .exchange()
                    .expectBody()
                    .jsonPath("$.left")
                    .isEqualTo(4);
        }
    }

    /**
     * Every error response in this app -- both the domain exceptions in {@code line}/{@code
     * shared} (which extend {@link org.springframework.web.ErrorResponseException}) and Spring
     * MVC's own framework-level failures (enabled via {@code spring.mvc.problemdetails.enabled})
     * -- comes back as an RFC 9457 {@code application/problem+json} body, not the framework's
     * classic {@code {"timestamp","status","error","path"}} shape.
     */
    @Nested
    class ErrorResponses {

        @Test
        void domainException_movingANonexistentLine_returns404ProblemDetail() {
            UUID id = UUID.randomUUID(); // never saved

            authedClient
                    .put()
                    .uri("/lines/{id}/left", id)
                    .header(HttpHeaders.IF_MATCH, "\"1\"")
                    .body(new MoveRequest(1))
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectHeader()
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody()
                    .jsonPath("$.status")
                    .isEqualTo(404)
                    .jsonPath("$.title")
                    .isEqualTo("Not Found")
                    .jsonPath("$.detail")
                    .value(String.class, detail -> assertThat(detail).contains(id.toString()));
        }

        @Test
        void domainException_violatingAnInvariant_returns422ProblemDetailWithReason() {
            UUID id = UUID.randomUUID();
            // left is already at 0: moving further left would push it negative.
            lineRepository.save(
                    LineFixture.newBuilder().id(id).line(0, 5).lockVersion(1).build());

            authedClient
                    .put()
                    .uri("/lines/{id}/left", id)
                    .header(HttpHeaders.IF_MATCH, "\"1\"")
                    .body(new MoveRequest(-1))
                    .exchange()
                    .expectStatus()
                    .isEqualTo(422)
                    .expectHeader()
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody()
                    .jsonPath("$.status")
                    .isEqualTo(422)
                    .jsonPath("$.detail")
                    .value(
                            String.class,
                            detail -> assertThat(detail).contains("MoveLeft").contains(id.toString()));
        }

        /**
         * Not just the domain exceptions: {@code spring.mvc.problemdetails.enabled} also converts
         * Spring MVC's own framework-level failures -- here, a request body Jackson can't map onto
         * {@link MoveRequest} at all ({@code by} is declared {@code int}) -- into the same {@code
         * application/problem+json} shape.
         */
        @Test
        void frameworkException_unreadableRequestBody_returns400ProblemDetail() {
            UUID id = UUID.randomUUID();
            lineRepository.save(
                    LineFixture.newBuilder().id(id).line(3, 9).lockVersion(1).build());

            authedClient
                    .put()
                    .uri("/lines/{id}/left", id)
                    .header(HttpHeaders.IF_MATCH, "\"1\"")
                    .body(Map.of("by", "not-a-number"))
                    .exchange()
                    .expectStatus()
                    .isEqualTo(400)
                    .expectHeader()
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody()
                    .jsonPath("$.status")
                    .isEqualTo(400);
        }

        /**
         * Bean Validation ({@code @NotBlank} on {@link CreateLineRequest#businessPartnerId}) is
         * enforced before the request ever reaches {@code LineService} -- {@code @Valid} on the
         * controller parameter rejects it with a {@link
         * org.springframework.web.bind.MethodArgumentNotValidException}, which {@link
         * ApiExceptionHandler} enriches with an {@code errors} array so the client learns exactly
         * which field failed and why, rather than just the generic "Invalid request content."
         */
        @Test
        void beanValidation_blankBusinessPartnerId_returns400WithFieldErrors() {
            UUID id = UUID.randomUUID();

            authedClient
                    .put()
                    .uri("/lines/{id}", id)
                    .body(new CreateLineRequest(1, 5, " "))
                    .exchange()
                    .expectStatus()
                    .isEqualTo(400)
                    .expectHeader()
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody()
                    .jsonPath("$.status")
                    .isEqualTo(400)
                    .jsonPath("$.errors[0].field")
                    .isEqualTo("businessPartnerId")
                    .jsonPath("$.errors[0].message")
                    .isEqualTo("must not be blank");

            authedClient.get().uri("/lines/{id}", id).exchange().expectStatus().isNotFound();
        }
    }

    @Nested
    class GetOne {

        /**
         * <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-13.1.2">RFC 9110 §13.1.2
         * If-None-Match</a>: "For a GET or HEAD request, the origin server... MAY respond with a
         * 304 (Not Modified) response if the condition is false" -- i.e. the client's cached
         * entity-tag still matches. <a
         * href="https://www.rfc-editor.org/rfc/rfc9110.html#section-15.4.5">§15.4.5 304 Not
         * Modified</a> requires this response carry no body and repeat any header that a 200
         * would have (here: ETag).
         */
        @Test
        void notModifiedSinceLastGet_return304NotModified() {
            // given
            UUID id = UUID.randomUUID();
            lineRepository.save(LineFixture.newBuilder().id(id).lockVersion(1).build());

            // when: the client already holds the current version
            authedClient
                    .get()
                    .uri("/lines/{id}", id)
                    .ifNoneMatch("\"1\"")
                    .exchange()
                    .expectStatus()
                    .isNotModified()
                    .expectHeader()
                    .valueEquals(HttpHeaders.ETAG, "\"1\"");
        }

        /**
         * <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-13.1.2">RFC 9110 §13.1.2
         * If-None-Match</a>: "If the field-value is '*'... the condition is false if the origin
         * server has a current representation for the target resource" -- so {@code *} always
         * yields 304 for an existing resource, without the client ever naming its version.
         */
        @Test
        void ifNoneMatchWildcard_returns304() {
            UUID id = UUID.randomUUID();
            lineRepository.save(LineFixture.newBuilder().id(id).lockVersion(1).build());

            authedClient
                    .get()
                    .uri("/lines/{id}", id)
                    .ifNoneMatch("*")
                    .exchange()
                    .expectStatus()
                    .isNotModified();
        }

        /**
         * <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-13.1.2">RFC 9110 §13.1.2
         * If-None-Match</a> allows a comma-separated list of entity-tags; the condition is false
         * (304) if any one of them matches.
         */
        @Test
        void ifNoneMatchWithMultipleTags_returns304WhenAnyMatches() {
            UUID id = UUID.randomUUID();
            lineRepository.save(LineFixture.newBuilder().id(id).lockVersion(1).build());

            authedClient
                    .get()
                    .uri("/lines/{id}", id)
                    .ifNoneMatch("\"999\"", "\"1\"")
                    .exchange()
                    .expectStatus()
                    .isNotModified();
        }

        /**
         * <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-13.1.2">RFC 9110 §13.1.2
         * If-None-Match</a>: comparison is weak (<a
         * href="https://www.rfc-editor.org/rfc/rfc9110.html#section-8.8.3.2">§8.8.3.2</a>), so a
         * weak validator the client supplies must still match our (always-strong) ETag once the
         * {@code W/} prefix is disregarded.
         */
        @Test
        void ifNoneMatchWeakValidator_stillMatchesStrongETag() {
            UUID id = UUID.randomUUID();
            lineRepository.save(LineFixture.newBuilder().id(id).lockVersion(1).build());

            authedClient
                    .get()
                    .uri("/lines/{id}", id)
                    .ifNoneMatch("W/\"1\"")
                    .exchange()
                    .expectStatus()
                    .isNotModified();
        }

        /**
         * Not governed by RFC 9110 directly -- this is a hypermedia affordance (HAL {@code _links})
         * reflecting the domain invariant enforced in {@code Line.moveLeft}, rather than raw HTTP
         * semantics.
         */
        @Test
        void ifLeftPointAtZero_thenMovingLeftRelationUnavailable() {
            // given: a line whose left point already sits at zero
            UUID atZeroId = UUID.randomUUID();
            lineRepository.save(LineFixture.newBuilder().id(atZeroId).line(0, 5).build());

            // then: the affordance to move it further left is not offered
            authedClient
                    .get()
                    .uri("/lines/{id}", atZeroId)
                    .accept(MediaTypes.HAL_JSON)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$._links.move-left")
                    .doesNotExist();

            // given: a line whose left point has room to move
            UUID awayFromZeroId = UUID.randomUUID();
            lineRepository.save(
                    LineFixture.newBuilder().id(awayFromZeroId).line(2, 5).build());

            // then: the affordance is offered
            authedClient
                    .get()
                    .uri("/lines/{id}", awayFromZeroId)
                    .accept(MediaTypes.HAL_JSON)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$._links.move-left")
                    .exists()
                    .jsonPath("$._links.move-right")
                    .exists();
        }

        /**
         * Same as {@link #ifLeftPointAtZero_thenMovingLeftRelationUnavailable} -- a hypermedia
         * affordance, not raw RFC 9110 mechanics.
         */
        @Test
        void onceUpdateBudgetIsSpent_bothMoveRelationsAreUnavailable() {
            UUID id = UUID.randomUUID();
            lineRepository.save(LineFixture.newBuilder()
                    .id(id)
                    .line(3, 9)
                    .leftUpdates(3)
                    .rightUpdates(2)
                    .build());

            authedClient
                    .get()
                    .uri("/lines/{id}", id)
                    .accept(MediaTypes.HAL_JSON)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$._links.move-left")
                    .doesNotExist()
                    .jsonPath("$._links.move-right")
                    .doesNotExist();
        }

        /**
         * The {@code delete} affordance reflects {@code line_#delete}: this caller holds
         * {@code line_#create} and {@code line_#read} (read is needed to view the line at all)
         * but not {@code line_#delete}, so the link must not be offered even though a delete
         * would otherwise succeed for any owner.
         */
        @Test
        void withoutDeleteRole_deleteRelationUnavailable() {
            UUID id = UUID.randomUUID();
            lineRepository.save(LineFixture.newBuilder().id(id).build());

            authedAs(tokenFor(LineFixture.BUSINESS_PARTNER_ID, LINE_CREATE, LINE_READ))
                    .get()
                    .uri("/lines/{id}", id)
                    .header("X-Partner-Id", LineFixture.BUSINESS_PARTNER_ID)
                    .accept(MediaTypes.HAL_JSON)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$._links.delete")
                    .doesNotExist();
        }
    }

    /**
     * Pagination shape is Spring Data/HAL convention, not something RFC 9110 specifies; §9.3.1 GET
     * only requires that a safe, cacheable representation of the collection be returned.
     */
    @Nested
    class GetAll {

        @Test
        void returnsLinesPaginated() {
            lineRepository.save(LineFixture.newBuilder().randomId().build());
            lineRepository.save(LineFixture.newBuilder().randomId().build());
            lineRepository.save(LineFixture.newBuilder().randomId().build());

            authedClient
                    .get()
                    .uri("/lines?size=2")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.page.totalElements")
                    .isEqualTo(3)
                    .jsonPath("$.page.totalPages")
                    .isEqualTo(2)
                    .jsonPath("$.content.length()")
                    .isEqualTo(2);
        }

        @Test
        void returnsEmptyPage_whenNoLinesExist() {
            authedClient
                    .get()
                    .uri("/lines")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.page.totalElements")
                    .isEqualTo(0)
                    .jsonPath("$.content.length()")
                    .isEqualTo(0);
        }

        /**
         * Lines belonging to a business partner the caller isn't affiliated with are filtered out.
         */
        @Test
        void excludesLinesOfOtherBusinessPartners() {
            lineRepository.save(LineFixture.newBuilder().randomId().build()); // LineFixture.BUSINESS_PARTNER_ID
            lineRepository.save(LineFixture.newBuilder()
                    .randomId()
                    .businessPartnerId("other-corp")
                    .build());

            authedClient
                    .get()
                    .uri("/lines")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.page.totalElements")
                    .isEqualTo(1);
        }
    }

    @Nested
    class Authorization {

        private static final String OTHER_BUSINESS_PARTNER_ID = "other-corp";

        @Test
        void unauthenticatedRequest_returns401() {
            client.get()
                    .uri("/lines/{id}", UUID.randomUUID())
                    .exchange()
                    .expectStatus()
                    .isUnauthorized();
        }

        @Test
        void create_withoutAnyRole_returns403() {
            UUID id = UUID.randomUUID();

            authedAs(tokenWithNoRoles())
                    .put()
                    .uri("/lines/{id}", id)
                    .body(new CreateLineRequest(1, 5, LineFixture.BUSINESS_PARTNER_ID))
                    .exchange()
                    .expectStatus()
                    .isForbidden();
        }

        /**
         * {@code line_#create} held for a different business partner does not authorize creation for this one.
         */
        @Test
        void create_withCreateRoleForDifferentPartner_returns403() {
            UUID id = UUID.randomUUID();

            authedAs(tokenFor(OTHER_BUSINESS_PARTNER_ID, LINE_CREATE))
                    .put()
                    .uri("/lines/{id}", id)
                    .header("X-Partner-Id", OTHER_BUSINESS_PARTNER_ID)
                    .body(new CreateLineRequest(1, 5, LineFixture.BUSINESS_PARTNER_ID))
                    .exchange()
                    .expectStatus()
                    .isForbidden();
        }

        /**
         * Only the creating business partner may view a line, even for another otherwise-valid caller.
         */
        @Test
        void get_byNonOwningBusinessPartner_returns403() {
            UUID id = UUID.randomUUID();
            lineRepository.save(LineFixture.newBuilder().id(id).build()); // owned by LineFixture.BUSINESS_PARTNER_ID

            authedAs(tokenFor(OTHER_BUSINESS_PARTNER_ID, LINE_CREATE, LINE_READ))
                    .get()
                    .uri("/lines/{id}", id)
                    .header("X-Partner-Id", OTHER_BUSINESS_PARTNER_ID)
                    .exchange()
                    .expectStatus()
                    .isForbidden();
        }

        /**
         * Same ownership rule applies to edits (move), not just reads.
         */
        @Test
        void move_byNonOwningBusinessPartner_returns403() {
            UUID id = UUID.randomUUID();
            lineRepository.save(
                    LineFixture.newBuilder().id(id).line(3, 9).lockVersion(1).build());

            authedAs(tokenFor(OTHER_BUSINESS_PARTNER_ID, LINE_CREATE))
                    .put()
                    .uri("/lines/{id}/left", id)
                    .header("X-Partner-Id", OTHER_BUSINESS_PARTNER_ID)
                    .header(HttpHeaders.IF_MATCH, "\"1\"")
                    .body(new MoveRequest(1))
                    .exchange()
                    .expectStatus()
                    .isForbidden();
        }

        /**
         * {@code X-Partner-Id} declares which single business partner the caller acts as for this
         * request -- naming one the caller holds no role for at all is an authorization failure,
         * caught by {@link li.selman.optimisticlocking.shared.web.BusinessPartnerFilter} before the
         * request ever reaches the controller.
         */
        @Test
        void getAll_withPartnerIdHeaderForUnaffiliatedPartner_returns403() {
            authedAs(authedToken)
                    .get()
                    .uri("/lines")
                    .header("X-Partner-Id", OTHER_BUSINESS_PARTNER_ID)
                    .exchange()
                    .expectStatus()
                    .isForbidden();
        }

        @Test
        void delete_withoutDeleteRole_returns403() {
            UUID id = UUID.randomUUID();
            lineRepository.save(LineFixture.newBuilder().id(id).build());

            authedAs(tokenFor(LineFixture.BUSINESS_PARTNER_ID, LINE_CREATE))
                    .delete()
                    .uri("/lines/{id}", id)
                    .exchange()
                    .expectStatus()
                    .isForbidden();
        }

        /**
         * Delete is gated purely by the user-independent {@code line_#delete} role -- unlike
         * view/edit, it does not additionally require the caller to be affiliated with the line's
         * owning business partner.
         */
        @Test
        void delete_withDeleteRole_succeedsRegardlessOfOwnership() {
            UUID id = UUID.randomUUID();
            lineRepository.save(LineFixture.newBuilder().id(id).build()); // owned by LineFixture.BUSINESS_PARTNER_ID

            authedAs(tokenWithUserRoles(LINE_DELETE))
                    .delete()
                    .uri("/lines/{id}", id)
                    .exchange()
                    .expectStatus()
                    .isNoContent();
        }
    }

    /**
     * Documents the three kinds of caller this app distinguishes, exercised via the tokens minted
     * in {@link #authenticate()}:
     * <ul>
     *   <li>{@code TOKEN_BAZG_EMPLOYEE}: {@code line_#read}/{@code line_#delete} as
     *       <b>user roles</b> -- user-independent, so they apply across every business partner's
     *       lines, like an admin. No {@code line_#create} at all, so creation/editing stays out
     *       of reach regardless of ownership.</li>
     *   <li>{@code TOKEN_COOP_EMPLOYEE}: affiliated with two business partners (Coop Pronto and
     *       Coop Jumbo), but only Jumbo carries {@code line} roles -- Pronto's role is for a
     *       different resource entirely. Being "in" a GP is not sufficient; the role must be for
     *       the {@code line} resource specifically.</li>
     *   <li>{@code TOKEN_MGB_EMPLOYEE}: affiliated with two business partners (Denner and
     *       Migrolino), both fully authorized for {@code line}. Unlike {@code TOKEN_BAZG_EMPLOYEE},
     *       a business-partner caller must state {@code X-Partner-Id} on <b>every</b> request --
     *       there is no coarse "any partner of mine" fallback, so MGB has to switch the header to
     *       move between acting as Denner and acting as Migrolino.</li>
     *   <li>{@code TOKEN_EMPTY}: neither a user role nor any business-partner role -- forbidden
     *       everywhere.</li>
     * </ul>
     */
    @Nested
    class RolesAndBusinessPartners {

        @Test
        void bazgEmployee_canReadAnyLine_regardlessOfOwningBusinessPartner() {
            UUID acmeLine = UUID.randomUUID();
            UUID otherCorpLine = UUID.randomUUID();
            lineRepository.save(LineFixture.newBuilder().id(acmeLine).build()); // owned by "acme"
            lineRepository.save(LineFixture.newBuilder()
                    .id(otherCorpLine)
                    .businessPartnerId("other-corp")
                    .build());

            authedAs(TOKEN_BAZG_EMPLOYEE)
                    .get()
                    .uri("/lines/{id}", acmeLine)
                    .exchange()
                    .expectStatus()
                    .isOk();
            authedAs(TOKEN_BAZG_EMPLOYEE)
                    .get()
                    .uri("/lines/{id}", otherCorpLine)
                    .exchange()
                    .expectStatus()
                    .isOk();
        }

        @Test
        void bazgEmployee_canDeleteAnyLine_regardlessOfOwningBusinessPartner() {
            UUID id = UUID.randomUUID();
            lineRepository.save(LineFixture.newBuilder()
                    .id(id)
                    .businessPartnerId("other-corp")
                    .build());

            authedAs(TOKEN_BAZG_EMPLOYEE)
                    .delete()
                    .uri("/lines/{id}", id)
                    .exchange()
                    .expectStatus()
                    .isNoContent();
        }

        @Test
        void bazgEmployee_cannotCreate_holdsNoCreateRoleAtAll() {
            authedAs(TOKEN_BAZG_EMPLOYEE)
                    .put()
                    .uri("/lines/{id}", UUID.randomUUID())
                    .body(new CreateLineRequest(1, 5, LineFixture.BUSINESS_PARTNER_ID))
                    .exchange()
                    .expectStatus()
                    .isForbidden();
        }

        @Test
        void bazgEmployee_getAll_seesLinesOfEveryBusinessPartner() {
            lineRepository.save(LineFixture.newBuilder().randomId().build()); // "acme"
            lineRepository.save(LineFixture.newBuilder()
                    .randomId()
                    .businessPartnerId("other-corp")
                    .build());

            authedAs(TOKEN_BAZG_EMPLOYEE)
                    .get()
                    .uri("/lines")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.page.totalElements")
                    .isEqualTo(2);
        }

        @Test
        void coopEmployee_canCreateReadAndMove_forJumbo_theAuthorizedPartner() {
            UUID id = UUID.randomUUID();

            authedAs(TOKEN_COOP_EMPLOYEE)
                    .put()
                    .uri("/lines/{id}", id)
                    .header("X-Partner-Id", BP_ID_COOP_JUMBO)
                    .body(new CreateLineRequest(3, 9, BP_ID_COOP_JUMBO))
                    .exchange()
                    .expectStatus()
                    .isCreated();

            authedAs(TOKEN_COOP_EMPLOYEE)
                    .get()
                    .uri("/lines/{id}", id)
                    .header("X-Partner-Id", BP_ID_COOP_JUMBO)
                    .exchange()
                    .expectStatus()
                    .isOk();

            authedAs(TOKEN_COOP_EMPLOYEE)
                    .put()
                    .uri("/lines/{id}/left", id)
                    .header("X-Partner-Id", BP_ID_COOP_JUMBO)
                    .header(HttpHeaders.IF_MATCH, "\"1\"")
                    .body(new MoveRequest(1))
                    .exchange()
                    .expectStatus()
                    .isOk();
        }

        @Test
        void coopEmployee_cannotCreate_forPronto_beingInThatGpIsNotEnough() {
            authedAs(TOKEN_COOP_EMPLOYEE)
                    .put()
                    .uri("/lines/{id}", UUID.randomUUID())
                    .header("X-Partner-Id", BP_ID_COOP_PRONTO)
                    .body(new CreateLineRequest(1, 5, BP_ID_COOP_PRONTO))
                    .exchange()
                    .expectStatus()
                    .isForbidden();
        }

        @Test
        void coopEmployee_cannotReadOrMove_prontosLines_beingInThatGpIsNotEnough() {
            UUID id = UUID.randomUUID();
            lineRepository.save(LineFixture.newBuilder()
                    .id(id)
                    .businessPartnerId(BP_ID_COOP_PRONTO)
                    .lockVersion(1)
                    .build());

            authedAs(TOKEN_COOP_EMPLOYEE)
                    .get()
                    .uri("/lines/{id}", id)
                    .header("X-Partner-Id", BP_ID_COOP_PRONTO)
                    .exchange()
                    .expectStatus()
                    .isForbidden();

            authedAs(TOKEN_COOP_EMPLOYEE)
                    .put()
                    .uri("/lines/{id}/left", id)
                    .header("X-Partner-Id", BP_ID_COOP_PRONTO)
                    .header(HttpHeaders.IF_MATCH, "\"1\"")
                    .body(new MoveRequest(1))
                    .exchange()
                    .expectStatus()
                    .isForbidden();
        }

        /**
         * {@code X-Partner-Id} is mandatory for a business-partner caller -- there is no coarse
         * "any partner of mine" fallback once the header is missing, unlike a global-role admin.
         */
        @Test
        void coopEmployee_getAll_withoutPartnerHeader_isForbidden() {
            authedAs(TOKEN_COOP_EMPLOYEE)
                    .get()
                    .uri("/lines")
                    .exchange()
                    .expectStatus()
                    .isForbidden();
        }

        @Test
        void coopEmployee_getAll_withPartnerHeader_scopesToJumbosLines_notProntos() {
            lineRepository.save(LineFixture.newBuilder()
                    .randomId()
                    .businessPartnerId(BP_ID_COOP_JUMBO)
                    .build());
            lineRepository.save(LineFixture.newBuilder()
                    .randomId()
                    .businessPartnerId(BP_ID_COOP_PRONTO)
                    .build());

            authedAs(TOKEN_COOP_EMPLOYEE)
                    .get()
                    .uri("/lines")
                    .header("X-Partner-Id", BP_ID_COOP_JUMBO)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.page.totalElements")
                    .isEqualTo(1);
        }

        /**
         * COOP holds {@code line_#read} for Jumbo, not Pronto -- naming Pronto via {@code
         * X-Partner-Id} must not let Jumbo's role "cover" a partner it was never granted for.
         */
        @Test
        void coopEmployee_getAll_cannotUseJumbosReadRole_toListProntosLines_viaPartnerHeader() {
            lineRepository.save(LineFixture.newBuilder()
                    .randomId()
                    .businessPartnerId(BP_ID_COOP_PRONTO)
                    .build());

            authedAs(TOKEN_COOP_EMPLOYEE)
                    .get()
                    .uri("/lines")
                    .header("X-Partner-Id", BP_ID_COOP_PRONTO)
                    .exchange()
                    .expectStatus()
                    .isForbidden();
        }

        @Test
        void mgbEmployee_canCreateReadAndMove_forBothOfItsPartners_bySwitchingThePartnerHeader() {
            UUID dennerLine = UUID.randomUUID();
            UUID migrolinoLine = UUID.randomUUID();

            authedAs(TOKEN_MGB_EMPLOYEE)
                    .put()
                    .uri("/lines/{id}", dennerLine)
                    .header("X-Partner-Id", BP_ID_MGB_DENNER)
                    .body(new CreateLineRequest(1, 5, BP_ID_MGB_DENNER))
                    .exchange()
                    .expectStatus()
                    .isCreated();
            authedAs(TOKEN_MGB_EMPLOYEE)
                    .put()
                    .uri("/lines/{id}", migrolinoLine)
                    .header("X-Partner-Id", BP_ID_MGB_MIGROLINO)
                    .body(new CreateLineRequest(1, 5, BP_ID_MGB_MIGROLINO))
                    .exchange()
                    .expectStatus()
                    .isCreated();

            // acting as Denner can't see Migrolino's line, and vice versa -- each request states
            // exactly one partner, matching the role it holds for that line
            authedAs(TOKEN_MGB_EMPLOYEE)
                    .get()
                    .uri("/lines/{id}", dennerLine)
                    .header("X-Partner-Id", BP_ID_MGB_DENNER)
                    .exchange()
                    .expectStatus()
                    .isOk();
            authedAs(TOKEN_MGB_EMPLOYEE)
                    .get()
                    .uri("/lines/{id}", migrolinoLine)
                    .header("X-Partner-Id", BP_ID_MGB_MIGROLINO)
                    .exchange()
                    .expectStatus()
                    .isOk();

            authedAs(TOKEN_MGB_EMPLOYEE)
                    .put()
                    .uri("/lines/{id}/left", dennerLine)
                    .header("X-Partner-Id", BP_ID_MGB_DENNER)
                    .header(HttpHeaders.IF_MATCH, "\"1\"")
                    .body(new MoveRequest(1))
                    .exchange()
                    .expectStatus()
                    .isOk();
        }

        /**
         * {@code X-Partner-Id} is mandatory for a business-partner caller -- there is no coarse
         * "any partner of mine" fallback once the header is missing, unlike a global-role admin.
         */
        @Test
        void mgbEmployee_getAll_withoutPartnerHeader_isForbidden() {
            authedAs(TOKEN_MGB_EMPLOYEE)
                    .get()
                    .uri("/lines")
                    .exchange()
                    .expectStatus()
                    .isForbidden();
        }

        /**
         * With {@code X-Partner-Id}, MGB "switches" to a single GP: listing narrows to just that
         * one partner's lines, exactly like {@code TOKEN_COOP_EMPLOYEE} always has to.
         */
        @Test
        void mgbEmployee_getAll_withPartnerHeader_scopesToJustThatOnePartner() {
            lineRepository.save(LineFixture.newBuilder()
                    .randomId()
                    .businessPartnerId(BP_ID_MGB_DENNER)
                    .build());
            lineRepository.save(LineFixture.newBuilder()
                    .randomId()
                    .businessPartnerId(BP_ID_MGB_MIGROLINO)
                    .build());

            authedAs(TOKEN_MGB_EMPLOYEE)
                    .get()
                    .uri("/lines")
                    .header("X-Partner-Id", BP_ID_MGB_DENNER)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.page.totalElements")
                    .isEqualTo(1);
        }

        @Test
        void noRoles_isForbiddenEverywhere() {
            UUID id = UUID.randomUUID();
            lineRepository.save(LineFixture.newBuilder().id(id).build());

            authedAs(TOKEN_EMPTY)
                    .get()
                    .uri("/lines/{id}", id)
                    .exchange()
                    .expectStatus()
                    .isForbidden();
            authedAs(TOKEN_EMPTY).get().uri("/lines").exchange().expectStatus().isForbidden();
            authedAs(TOKEN_EMPTY)
                    .delete()
                    .uri("/lines/{id}", id)
                    .exchange()
                    .expectStatus()
                    .isForbidden();
            authedAs(TOKEN_EMPTY)
                    .put()
                    .uri("/lines/{id}", UUID.randomUUID())
                    .body(new CreateLineRequest(1, 5, LineFixture.BUSINESS_PARTNER_ID))
                    .exchange()
                    .expectStatus()
                    .isForbidden();
        }
    }
}
