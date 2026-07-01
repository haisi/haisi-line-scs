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
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.UUID;

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;


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

    @AfterEach
    void cleanup() {
        // Truncate all
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

        }

    }

    @Nested
    class PutCreate {

    }

    @Nested
    class PutMove {

    }

    @Nested
    class GetOne {

        // If-None-Match --> 304
        @Test
        void notModifiedSinceLastGet_return304NotModified() {

        }

        @Test
        void ifLeftPointAtZero_thenMovingLeftRelationUnavailable() {

        }
    }

    @Nested
    class GetAll {

        // Pagining

    }

}