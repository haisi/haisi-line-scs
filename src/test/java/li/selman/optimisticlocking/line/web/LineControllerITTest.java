package li.selman.optimisticlocking.line.web;

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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

// TODO also test the location header
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
        
        @Test
        void isIdempotent() {
            // given
            UUID id = LineFixture.CONSTANT_ID.id();
            lineRepository.save(LineFixture.newBuilder().id(id).build());
            client.get().uri("/lines/{id}", id).exchange().expectStatus().isOk();

            RestTestClient.ResponseSpec exchange1 = client.delete().uri("/lines/{id}", id).exchange();
            RestTestClient.ResponseSpec exchange2 = client.delete().uri("/lines/{id}", id).exchange();

            exchange1.expectAll(spec -> spec.expectStatus().isNoContent());
            exchange2.expectAll(spec -> spec.expectStatus().isNoContent());

            client.get().uri("/lines/{id}", id).exchange().expectStatus().isNotFound();
        }

        @Test
        void preconditionFailOnStaleData() {
            // given
            UUID id = UUID.randomUUID();
            lineRepository.save(LineFixture.newBuilder().id(id).lockVersion(1).build());

            // when: If-Match carries a version that no longer matches the current one
            client.delete().uri("/lines/{id}", id)
                    .header(HttpHeaders.IF_MATCH, "\"999\"")
                    .exchange()
                    .expectStatus().isEqualTo(412);

            // then: the write never happened
            client.get().uri("/lines/{id}", id).exchange().expectStatus().isOk();
        }

    }

    /**
     * <a href="https://www.rfc-editor.org/rfc/rfc9110.html#name-put">RFC9910 § 9.3.4 PUT</a>
     */
    @Nested
    class PutCreate {

        @Test
        void createsANewLine_returns201Created() {
            UUID id = UUID.randomUUID();

            client.put().uri("/lines/{id}", id)
                    .body(new CreateLineRequest(1, 5))
                    .exchange()
                    .expectStatus().isCreated()
                    .expectHeader().valueEquals("ETag", "\"1\"")
//                    .location("TODO") or content-location
                    .expectBody()
                    .jsonPath("$.left").isEqualTo(1)
                    .jsonPath("$.right").isEqualTo(5);

            client.get().uri("/lines/{id}", id).exchange().expectStatus().isOk();
        }

        @Test
        void creatingSameLineTwice_isIdempotent() {
            UUID id = UUID.randomUUID();

            client.put().uri("/lines/{id}", id)
                    .body(new CreateLineRequest(1, 5))
                    .exchange()
                    .expectStatus().isCreated(); //

            // retry with identical content -> no-op, not a second line
            client.put().uri("/lines/{id}", id)
                    .body(new CreateLineRequest(1, 5))
                    .exchange()
                    .expectStatus().isOk();
        }

        @Test
        void creatingWithDifferentBodyForExistingId_returns409Conflict() {
            UUID id = UUID.randomUUID();

            client.put().uri("/lines/{id}", id)
                    .body(new CreateLineRequest(1, 5))
                    .exchange()
                    .expectStatus().isCreated();

            // no precondition was stated, and the content genuinely differs -> 409, not 412
            client.put().uri("/lines/{id}", id)
                    .body(new CreateLineRequest(2, 6))
                    .exchange()
                    .expectStatus().isEqualTo(409);

            client.get().uri("/lines/{id}", id)
                    .exchange()
                    .expectBody()
                    .jsonPath("$.left").isEqualTo(1)
                    .jsonPath("$.right").isEqualTo(5);
        }

        @Test
        void creatingWithLeftGreaterThanRight_returns422() {
            UUID id = UUID.randomUUID();

            client.put().uri("/lines/{id}", id)
                    .body(new CreateLineRequest(5, 1))
                    .exchange()
                    .expectStatus().isEqualTo(422);
        }
    }

    @Nested
    class PutMove {

        @Test
        void movesLeftPoint_returns200WithNewETag() {
            UUID id = UUID.randomUUID();
            lineRepository.save(LineFixture.newBuilder().id(id).line(3, 9).lockVersion(1).build());

            // even though only the child LeftPoint row changes, the root's version bumps too
            client.put().uri("/lines/{id}/left", id)
                    .header(HttpHeaders.IF_MATCH, "\"1\"")
                    .body(new MoveRequest(2))
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().valueEquals(HttpHeaders.ETAG, "\"2\"")
                    .expectBody().jsonPath("$.left").isEqualTo(5);
        }

        @Test
        void missingIfMatch_returns428PreconditionRequired() {
            UUID id = UUID.randomUUID();
            lineRepository.save(LineFixture.newBuilder().id(id).line(3, 9).lockVersion(1).build());

            client.put().uri("/lines/{id}/left", id)
                    .body(new MoveRequest(2))
                    .exchange()
                    .expectStatus().isEqualTo(428);
        }

        @Test
        void staleIfMatch_returns412PreconditionFailed() {
            UUID id = UUID.randomUUID();
            lineRepository.save(LineFixture.newBuilder().id(id).line(3, 9).lockVersion(1).build());

            // someone else's move lands first, bumping the version to "2"
            client.put().uri("/lines/{id}/left", id)
                    .header(HttpHeaders.IF_MATCH, "\"1\"")
                    .body(new MoveRequest(1))
                    .exchange()
                    .expectStatus().isOk();

            // a stale tab retries against the version it originally read
            client.put().uri("/lines/{id}/left", id)
                    .header(HttpHeaders.IF_MATCH, "\"1\"")
                    .body(new MoveRequest(1))
                    .exchange()
                    .expectStatus().isEqualTo(412);
        }

        @Test
        void movingLeftPastRight_returns422UnprocessableEntity() {
            UUID id = UUID.randomUUID();
            lineRepository.save(LineFixture.newBuilder().id(id).line(8, 9).lockVersion(1).build());

            client.put().uri("/lines/{id}/left", id)
                    .header(HttpHeaders.IF_MATCH, "\"1\"")
                    .body(new MoveRequest(2))  // 8 + 2 = 10 > 9
                    .exchange()
                    .expectStatus().isEqualTo(422);

            // the rejected write never touched the aggregate
            client.get().uri("/lines/{id}", id)
                    .exchange()
                    .expectHeader().valueEquals(HttpHeaders.ETAG, "\"1\"");
        }

        @Test
        void exceedingUpdateLimit_returns422() {
            UUID id = UUID.randomUUID();
            lineRepository.save(LineFixture.newBuilder().id(id).line(3, 9).lockVersion(1).leftUpdates(5).build());

            client.put().uri("/lines/{id}/left", id)
                    .header(HttpHeaders.IF_MATCH, "\"1\"")
                    .body(new MoveRequest(1))
                    .exchange()
                    .expectStatus().isEqualTo(422);
        }

        @Test
        void retryingWithSameIdempotencyKey_isANoOp() {
            UUID id = UUID.randomUUID();
            lineRepository.save(LineFixture.newBuilder().id(id).line(3, 9).lockVersion(1).build());
            String idempotencyKey = UUID.randomUUID().toString();

            client.put().uri("/lines/{id}/left", id)
                    .header(HttpHeaders.IF_MATCH, "\"1\"")
                    .header("Idempotency-Key", idempotencyKey)
                    .body(new MoveRequest(2))
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().valueEquals(HttpHeaders.ETAG, "\"2\"");

            // retry: same key, same (now stale) If-Match -- must replay, not re-apply or 412
            client.put().uri("/lines/{id}/left", id)
                    .header(HttpHeaders.IF_MATCH, "\"1\"")
                    .header("Idempotency-Key", idempotencyKey)
                    .body(new MoveRequest(2))
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().valueEquals(HttpHeaders.ETAG, "\"2\"");  // unchanged: no second bump
        }

        @Test
        void reusingIdempotencyKeyWithDifferentBody_returns422() {
            UUID id = UUID.randomUUID();
            lineRepository.save(LineFixture.newBuilder().id(id).line(3, 9).lockVersion(1).build());
            String idempotencyKey = UUID.randomUUID().toString();

            client.put().uri("/lines/{id}/left", id)
                    .header(HttpHeaders.IF_MATCH, "\"1\"")
                    .header("Idempotency-Key", idempotencyKey)
                    .body(new MoveRequest(2))
                    .exchange()
                    .expectStatus().isOk();

            client.put().uri("/lines/{id}/left", id)
                    .header(HttpHeaders.IF_MATCH, "\"2\"")
                    .header("Idempotency-Key", idempotencyKey)
                    .body(new MoveRequest(3))  // same key, different payload
                    .exchange()
                    .expectStatus().isEqualTo(422);
        }

        @Test
        void concurrentMovesWithoutPrecondition_onlyOneSucceeds() throws Exception {
            UUID id = UUID.randomUUID();
            lineRepository.save(LineFixture.newBuilder().id(id).line(3, 9).lockVersion(1).build());

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
                        client.put().uri("/lines/{id}/left", id)
                                .header(HttpHeaders.IF_MATCH, "\"1\"")
                                .body(new MoveRequest(1))
                                .exchange()
                                .expectStatus().value(status::set);
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
            long conflicts = statuses.stream().filter(status -> status == 409 || status == 412).count();

            assertThat(successes).isEqualTo(1);
            assertThat(conflicts).isEqualTo(threadCount - 1);

            // exactly one of the identical moves took effect
            client.get().uri("/lines/{id}", id)
                    .exchange()
                    .expectBody().jsonPath("$.left").isEqualTo(4);
        }
    }

    @Nested
    class GetOne {

        // If-None-Match --> 304
        @Test
        void notModifiedSinceLastGet_return304NotModified() {
            // given
            UUID id = UUID.randomUUID();
            lineRepository.save(LineFixture.newBuilder().id(id).lockVersion(1).build());

            // when: the client already holds the current version
            client.get().uri("/lines/{id}", id)
                    .ifNoneMatch("\"1\"")
                    .exchange()
                    .expectStatus().isNotModified();
        }

        @Test
        void ifLeftPointAtZero_thenMovingLeftRelationUnavailable() {
            // given: a line whose left point already sits at zero
            UUID atZeroId = UUID.randomUUID();
            lineRepository.save(LineFixture.newBuilder().id(atZeroId).line(0, 5).build());

            // then: the affordance to move it further left is not offered
            client.get().uri("/lines/{id}", atZeroId)
                    .accept(MediaTypes.HAL_JSON)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$._links.move-left").doesNotExist();

            // given: a line whose left point has room to move
            UUID awayFromZeroId = UUID.randomUUID();
            lineRepository.save(LineFixture.newBuilder().id(awayFromZeroId).line(2, 5).build());

            // then: the affordance is offered
            client.get().uri("/lines/{id}", awayFromZeroId)
                    .accept(MediaTypes.HAL_JSON)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$._links.move-left").exists()
                    .jsonPath("$._links.move-right").exists();
        }

        @Test
        void onceUpdateBudgetIsSpent_bothMoveRelationsAreUnavailable() {
            UUID id = UUID.randomUUID();
            lineRepository.save(LineFixture.newBuilder().id(id).line(3, 9).leftUpdates(3).rightUpdates(2).build());

            client.get().uri("/lines/{id}", id)
                    .accept(MediaTypes.HAL_JSON)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$._links.move-left").doesNotExist()
                    .jsonPath("$._links.move-right").doesNotExist();
        }
    }

    @Nested
    class GetAll {

        @Test
        void returnsLinesPaginated() {
            lineRepository.save(LineFixture.newBuilder().randomId().build());
            lineRepository.save(LineFixture.newBuilder().randomId().build());
            lineRepository.save(LineFixture.newBuilder().randomId().build());

            client.get().uri("/lines?size=2")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.page.totalElements").isEqualTo(3)
                    .jsonPath("$.page.totalPages").isEqualTo(2)
                    .jsonPath("$.content.length()").isEqualTo(2);
        }

        @Test
        void returnsEmptyPage_whenNoLinesExist() {
            client.get().uri("/lines")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.page.totalElements").isEqualTo(0)
                    .jsonPath("$.content.length()").isEqualTo(0);
        }
    }

}