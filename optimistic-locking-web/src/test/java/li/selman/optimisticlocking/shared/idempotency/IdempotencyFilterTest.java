package li.selman.optimisticlocking.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.admin.bit.jeap.security.test.resource.ServletSemanticAuthorizationMock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import li.selman.optimisticlocking.shared.web.Idempotent;
import li.selman.optimisticlocking.shared.web.IdempotencyFilter;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pure unit test, same style as {@code BusinessPartnerFilterTest} (no Spring context). Lives here,
 * next to {@link IdempotencyService}, rather than next to {@link IdempotencyFilter} in {@code
 * shared.web}, purely so it can call {@link IdempotencyService}'s package-private constructor
 * directly with an in-memory fake repository -- a real one, not a mock, so the fingerprinting/
 * replay/mismatch logic in both classes actually runs rather than being stubbed away. {@code
 * Idempotency-Key} is hardcoded below rather than referencing {@code IdempotencyFilter.HEADER_NAME}
 * (package-private to {@code shared.web}, not visible from here). {@link FakeHandlerMapping} stands
 * in for the real {@code RequestMappingHandlerMapping} the filter resolves the handler through to
 * decide opt-in -- it's just a lambda-backed {@code HandlerMapping}, so no Spring context is needed
 * to exercise the real annotation-checking logic in {@code IdempotencyFilter.isOptedIn}.
 */
class IdempotencyFilterTest {

    private static final String HEADER_NAME = "Idempotency-Key";
    private static final String URI = "/lines/11111111-1111-1111-1111-111111111111/left/move-right";
    private static final String NOT_OPTED_IN_URI = "/lines/11111111-1111-1111-1111-111111111111";

    private final FakeIdempotencyRecordRepository repository = new FakeIdempotencyRecordRepository();
    private final IdempotencyService idempotencyService =
            new IdempotencyService(repository, new SimpleMeterRegistry());
    private final ServletSemanticAuthorizationMock authorization =
            ServletSemanticAuthorizationMock.builder().systemName("wvs").build();
    private final IdempotencyFilter filter = new IdempotencyFilter(
            idempotencyService, authorization, JsonMapper.builder().build(), new FakeHandlerMapping());

    @Test
    void noIdempotencyKeyHeader_letsRequestThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", URI);
        RecordingFilterChain chain = new RecordingFilterChain(200, "unused");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.invoked).isTrue();
    }

    @Test
    void endpointNotAnnotatedIdempotent_ignoresHeaderEntirely() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", NOT_OPTED_IN_URI);
        request.addHeader(HEADER_NAME, "key-not-opted-in");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingFilterChain chain = new RecordingFilterChain(200, "deleted");

        filter.doFilter(request, response, chain);

        assertThat(chain.invoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(repository.records).isEmpty();
    }

    @Test
    void freshKey_executesOnceAndStoresTheResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", URI);
        request.addHeader(HEADER_NAME, "key-1");
        request.setContent("{\"by\":1}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingFilterChain chain = new RecordingFilterChain(200, "moved");

        filter.doFilter(request, response, chain);

        assertThat(chain.invoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("moved");
        assertThat(repository.records).containsKey("key-1");
    }

    /**
     * If the handler chain throws past every exception resolver -- genuinely unhandled, not a
     * normal error path -- the reservation must be abandoned, not cached as a fabricated 200/empty
     * success (nothing would have explicitly set a status yet, so the response would still read its
     * default, 200). Otherwise a retry would replay that fake success and never actually re-run.
     */
    @Test
    void chainThrows_abandonsReservationAndRethrowsRatherThanCachingAFakeSuccess() {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", URI);
        request.addHeader(HEADER_NAME, "key-throws");
        request.setContent("{\"by\":1}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain throwingChain = (req, res) -> {
            throw new IllegalStateException("unexpected bug downstream");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, throwingChain))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("unexpected bug downstream");

        assertThat(repository.records).isEmpty();
    }

    @Test
    void replayOfSameKeyAndSameRequest_doesNotInvokeChainAgain() throws Exception {
        MockHttpServletRequest first = new MockHttpServletRequest("PUT", URI);
        first.addHeader(HEADER_NAME, "key-2");
        first.setContent("{\"by\":1}".getBytes(StandardCharsets.UTF_8));
        filter.doFilter(first, new MockHttpServletResponse(), new RecordingFilterChain(200, "moved-once"));

        // Retry: same key, same method/path/body -> must replay, not execute again.
        MockHttpServletRequest retry = new MockHttpServletRequest("PUT", URI);
        retry.addHeader(HEADER_NAME, "key-2");
        retry.setContent("{\"by\":1}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse retryResponse = new MockHttpServletResponse();
        RecordingFilterChain retryChain = new RecordingFilterChain(200, "should-not-run");

        filter.doFilter(retry, retryResponse, retryChain);

        assertThat(retryChain.invoked).isFalse();
        assertThat(retryResponse.getStatus()).isEqualTo(200);
        assertThat(retryResponse.getContentAsString()).isEqualTo("moved-once");
    }

    @Test
    void reusedKeyWithADifferentRequest_returns422WithoutInvokingChain() throws Exception {
        MockHttpServletRequest first = new MockHttpServletRequest("PUT", URI);
        first.addHeader(HEADER_NAME, "key-3");
        first.setContent("{\"by\":1}".getBytes(StandardCharsets.UTF_8));
        filter.doFilter(first, new MockHttpServletResponse(), new RecordingFilterChain(200, "moved"));

        // Same key, different body -> a fingerprint mismatch, not a replay.
        MockHttpServletRequest reused = new MockHttpServletRequest("PUT", URI);
        reused.addHeader(HEADER_NAME, "key-3");
        reused.setContent("{\"by\":2}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse reusedResponse = new MockHttpServletResponse();
        RecordingFilterChain reusedChain = new RecordingFilterChain(200, "should-not-run");

        filter.doFilter(reused, reusedResponse, reusedChain);

        assertThat(reusedChain.invoked).isFalse();
        assertThat(reusedResponse.getStatus()).isEqualTo(422);
        assertThat(reusedResponse.getContentAsString()).contains("already used for a different request");
    }

    private static final class RecordingFilterChain implements FilterChain {
        private final int status;
        private final String body;
        boolean invoked;

        RecordingFilterChain(int status, String body) {
            this.status = status;
            this.body = body;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) throws IOException {
            invoked = true;
            HttpServletResponse httpResponse = (HttpServletResponse) response;
            httpResponse.setStatus(status);
            httpResponse.getWriter().write(body);
        }
    }

    /**
     * Mimics {@code RequestMappingHandlerMapping} just enough for {@code IdempotencyFilter} to
     * resolve a handler and check it for {@link Idempotent}: the move-endpoint-shaped {@link #URI}
     * routes to an {@code @Idempotent}-annotated fake handler method, everything else to a plain
     * one -- matching this app's real controller, where only the move endpoints carry the annotation.
     */
    private static final class FakeHandlerMapping implements org.springframework.web.servlet.HandlerMapping {
        @Override
        public HandlerExecutionChain getHandler(HttpServletRequest request) throws NoSuchMethodException {
            boolean idempotentEndpoint = request.getRequestURI().endsWith("/left/move-right");
            Method method = idempotentEndpoint
                    ? FakeController.class.getDeclaredMethod("idempotentEndpoint")
                    : FakeController.class.getDeclaredMethod("plainEndpoint");
            return new HandlerExecutionChain(new HandlerMethod(new FakeController(), method));
        }
    }

    private static final class FakeController {
        @Idempotent
        @SuppressWarnings("unused") // invoked reflectively by FakeHandlerMapping
        void idempotentEndpoint() {}

        @SuppressWarnings("unused") // invoked reflectively by FakeHandlerMapping
        void plainEndpoint() {}
    }

    /** Stands in for a real database: records exactly what {@link IdempotencyService} persists. */
    private static final class FakeIdempotencyRecordRepository implements IdempotencyRecordRepository {
        private final Map<String, IdempotencyRecord> records = new HashMap<>();

        @Override
        public IdempotencyRecord save(IdempotencyRecord record) {
            records.put(record.getId(), record);
            return record;
        }

        @Override
        public IdempotencyRecord saveAndFlush(IdempotencyRecord record) {
            if (records.containsKey(record.getId())) {
                throw new DataIntegrityViolationException("duplicate key " + record.getId());
            }
            return save(record);
        }

        @Override
        public Optional<IdempotencyRecord> findById(String id) {
            return Optional.ofNullable(records.get(id));
        }

        @Override
        public void deleteById(String id) {
            records.remove(id);
        }

        @Override
        public long count() {
            return records.size();
        }

        @Override
        public int deleteByCreatedAtBefore(Instant cutoff) {
            return 0; // not exercised by this filter-focused test
        }
    }
}
