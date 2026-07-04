package li.selman.optimisticlocking.line.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import li.selman.optimisticlocking.line.LineFixture;
import li.selman.optimisticlocking.line.LineRepository;
import li.selman.optimisticlocking.line.LineService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@AutoConfigureRestTestClient
class LineControllerITTest {

    //    @Autowired
    //    TestEntityManager testEntityManager;

    @Autowired
    LineRepository lineRepository;

    @Autowired
    LineService lineService;

    @Autowired
    RestTestClient client;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM line");
        jdbcTemplate.update("DELETE FROM left_point");
        jdbcTemplate.update("DELETE FROM right_point");
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
            client.get().uri("/lines/{id}", id).exchange().expectStatus().isOk();

            RestTestClient.ResponseSpec exchange1 =
                    client.delete().uri("/lines/{id}", id).exchange();
            RestTestClient.ResponseSpec exchange2 =
                    client.delete().uri("/lines/{id}", id).exchange();

            exchange1.expectAll(spec -> spec.expectStatus().isNoContent());
            exchange2.expectAll(spec -> spec.expectStatus().isNoContent());

            client.get().uri("/lines/{id}", id).exchange().expectStatus().isNotFound();
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
            client.delete()
                    .uri("/lines/{id}", id)
                    .header(HttpHeaders.IF_MATCH, "\"999\"")
                    .exchange()
                    .expectStatus()
                    .isEqualTo(412);

            // then: the write never happened
            client.get().uri("/lines/{id}", id).exchange().expectStatus().isOk();
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

            client.delete()
                    .uri("/lines/{id}", id)
                    .header(HttpHeaders.IF_MATCH, "*")
                    .exchange()
                    .expectStatus()
                    .isNoContent();

            client.get().uri("/lines/{id}", id).exchange().expectStatus().isNotFound();
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

            client.put()
                    .uri("/lines/{id}", id)
                    .body(new CreateLineRequest(1, 5))
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

            client.get().uri("/lines/{id}", id).exchange().expectStatus().isOk();
        }

        /**
         * <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-9.2.2">RFC 9110 §9.2.2
         * Idempotent Methods</a>: PUT is defined to be idempotent, so a byte-for-byte identical
         * retry must not be treated as a second, conflicting write.
         */
        @Test
        void creatingSameLineTwice_isIdempotent() {
            UUID id = UUID.randomUUID();

            client.put()
                    .uri("/lines/{id}", id)
                    .body(new CreateLineRequest(1, 5))
                    .exchange()
                    .expectStatus()
                    .isCreated(); //

            // retry with identical content -> no-op, not a second line
            client.put()
                    .uri("/lines/{id}", id)
                    .body(new CreateLineRequest(1, 5))
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

            client.put()
                    .uri("/lines/{id}", id)
                    .body(new CreateLineRequest(1, 5))
                    .exchange()
                    .expectStatus()
                    .isCreated();

            // no precondition was stated, and the content genuinely differs -> 409, not 412
            client.put()
                    .uri("/lines/{id}", id)
                    .body(new CreateLineRequest(2, 6))
                    .exchange()
                    .expectStatus()
                    .isEqualTo(409);

            client.get()
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

            client.put()
                    .uri("/lines/{id}", id)
                    .body(new CreateLineRequest(5, 1))
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
            client.put()
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

            client.put()
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
            client.put()
                    .uri("/lines/{id}/left", id)
                    .header(HttpHeaders.IF_MATCH, "\"1\"")
                    .body(new MoveRequest(1))
                    .exchange()
                    .expectStatus()
                    .isOk();

            // a stale tab retries against the version it originally read
            client.put()
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
            client.put()
                    .uri("/lines/{id}/left", id)
                    .header(HttpHeaders.IF_MATCH, "\"1\"")
                    .body(new MoveRequest(1))
                    .exchange()
                    .expectStatus()
                    .isOk();

            // a client that never read the current version, but still wants "whatever it is now"
            client.put()
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

            client.put()
                    .uri("/lines/{id}/left", id)
                    .header(HttpHeaders.IF_MATCH, "\"1\"")
                    .body(new MoveRequest(2)) // 8 + 2 = 10 > 9
                    .exchange()
                    .expectStatus()
                    .isEqualTo(422);

            // the rejected write never touched the aggregate
            client.get().uri("/lines/{id}", id).exchange().expectHeader().valueEquals(HttpHeaders.ETAG, "\"1\"");
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

            client.put()
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

            client.put()
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
            client.put()
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

            client.put()
                    .uri("/lines/{id}/left", id)
                    .header(HttpHeaders.IF_MATCH, "\"1\"")
                    .header("Idempotency-Key", idempotencyKey)
                    .body(new MoveRequest(2))
                    .exchange()
                    .expectStatus()
                    .isOk();

            client.put()
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
                        client.put()
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
            client.get()
                    .uri("/lines/{id}", id)
                    .exchange()
                    .expectBody()
                    .jsonPath("$.left")
                    .isEqualTo(4);
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
            client.get()
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

            client.get()
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

            client.get()
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

            client.get()
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
            client.get()
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
            client.get()
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

            client.get()
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

            client.get()
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
            client.get()
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
    }
}
