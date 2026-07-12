package li.selman.optimisticlocking.line.web;

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.DEFINED_PORT;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders;
import static org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders;
import static org.springframework.restdocs.hypermedia.HypermediaDocumentation.linkWithRel;
import static org.springframework.restdocs.hypermedia.HypermediaDocumentation.relaxedLinks;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.relaxedRequestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.relaxedResponseFields;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.admin.bit.jeap.security.resource.token.JeapAuthenticationContext;
import ch.admin.bit.jeap.security.test.jws.JwsBuilderFactory;
import ch.admin.bit.jeap.security.test.resource.configuration.JeapOAuth2IntegrationTestResourceConfiguration;
import java.util.UUID;
import li.selman.optimisticlocking.line.LineAuthorization;
import li.selman.optimisticlocking.line.LineFixture;
import li.selman.optimisticlocking.line.LineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Generates the Spring REST Docs snippets included by {@code src/docs/asciidoc/index.adoc}. Kept
 * separate from {@link LineControllerITTest}, which asserts behaviour -- this class exists purely
 * to capture one representative request/response for each documented API concern (creation,
 * business invariants, optimistic-locking preconditions, idempotent replay).
 *
 * <p>Uses a hand-assembled {@link MockMvc} (rather than {@code @AutoConfigureRestDocs}, which has
 * no Spring Boot 4-compatible release yet) wired with the real security filter chain via {@code
 * springSecurity()}, so requests still need a genuine signed bearer token -- see {@link
 * LineControllerITTest}'s class Javadoc for why a fixed port is required for the {@code JwtDecoder}
 * to resolve its {@code jwk-set-uri} in time.
 */
@SpringBootTest(
        webEnvironment = DEFINED_PORT,
        properties = {
            "server.port=18099",
            "jeap.security.oauth2.resourceserver.authorization-server.jwk-set-uri="
                    + "http://localhost:18099/.well-known/jwks.json"
        })
@Import(JeapOAuth2IntegrationTestResourceConfiguration.class)
@ExtendWith(RestDocumentationExtension.class)
class LineApiDocumentationTest {

    private static final String LINE_DELETE_USER_ROLE = "wvs_@line_#delete";

    @Autowired
    WebApplicationContext webApplicationContext;

    @Autowired
    LineRepository lineRepository;

    @Autowired
    JwsBuilderFactory jwsBuilderFactory;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    MockMvc mockMvc;

    private String bearerToken;
    private String deleteBearerToken;

    @BeforeEach
    void setUp(RestDocumentationContextProvider restDocumentation) {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .apply(documentationConfiguration(restDocumentation)
                        .operationPreprocessors()
                        .withRequestDefaults(prettyPrint())
                        .withResponseDefaults(prettyPrint()))
                .build();

        bearerToken = jwsBuilderFactory
                .createValidForFixedLongPeriodBuilder("api-guide", JeapAuthenticationContext.USER)
                .withBusinessPartnerRoles(
                        LineFixture.BUSINESS_PARTNER_ID, LineAuthorization.CREATE_ROLE, LineAuthorization.READ_ROLE)
                .build()
                .serialize();

        deleteBearerToken = jwsBuilderFactory
                .createValidForFixedLongPeriodBuilder("api-guide", JeapAuthenticationContext.USER)
                .withUserRoles(LINE_DELETE_USER_ROLE)
                .build()
                .serialize();

        jdbcTemplate.update("DELETE FROM line");
        jdbcTemplate.update("DELETE FROM left_point");
        jdbcTemplate.update("DELETE FROM right_point");
        jdbcTemplate.update("DELETE FROM manual_operation");
    }

    @Test
    void createLine() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(put("/lines/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                        .header("X-Partner-Id", LineFixture.BUSINESS_PARTNER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateLineRequest(1, 5, LineFixture.BUSINESS_PARTNER_ID))))
                .andExpect(status().isCreated())
                .andDo(document(
                        "create-line",
                        pathParameters(parameterWithName("id").description("Client-chosen id of the line to create.")),
                        requestHeaders(headerWithName("X-Partner-Id")
                                .description("Business partner context this request acts in -- see "
                                        + "<<business-partner-scoping>>.")),
                        relaxedRequestFields(
                                fieldWithPath("left").description("Initial position of the left point."),
                                fieldWithPath("right")
                                        .description("Initial position of the right point (must be >= left)."),
                                fieldWithPath("businessPartnerId")
                                        .description("Business partner the line is created on behalf of.")),
                        responseHeaders(
                                headerWithName(HttpHeaders.ETAG)
                                        .description(
                                                "The new aggregate version, quoted, for use as a later `If-Match`."),
                                headerWithName(HttpHeaders.LOCATION).description("URI of the newly created line.")),
                        relaxedResponseFields(
                                fieldWithPath("id").description("The line's id (echoes the path parameter)."),
                                fieldWithPath("businessPartnerId").description("Owning business partner."),
                                fieldWithPath("_embedded.leftPoint.position")
                                        .description("Current position of the left point."),
                                fieldWithPath("_embedded.rightPoint.position")
                                        .description("Current position of the right point."))));
    }

    @Test
    void createLine_identicalRetryIsAReplay() throws Exception {
        UUID id = UUID.randomUUID();
        CreateLineRequest body = new CreateLineRequest(1, 5, LineFixture.BUSINESS_PARTNER_ID);

        mockMvc.perform(put("/lines/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                        .header("X-Partner-Id", LineFixture.BUSINESS_PARTNER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());

        // PUT is defined to be idempotent (RFC 9110 SS9.2.2): a byte-for-byte identical retry
        // replays the existing resource instead of being treated as a conflicting second write.
        mockMvc.perform(put("/lines/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                        .header("X-Partner-Id", LineFixture.BUSINESS_PARTNER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andDo(document("create-line-replay"));
    }

    @Test
    void createLine_conflictingRetryIsRejected() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(put("/lines/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                        .header("X-Partner-Id", LineFixture.BUSINESS_PARTNER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateLineRequest(1, 5, LineFixture.BUSINESS_PARTNER_ID))))
                .andExpect(status().isCreated());

        // No precondition was ever stated for a create, so a genuinely different body for the
        // same id is a 409 Conflict, not a 412 -- see the talk's status-code decision rule.
        mockMvc.perform(put("/lines/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                        .header("X-Partner-Id", LineFixture.BUSINESS_PARTNER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateLineRequest(2, 6, LineFixture.BUSINESS_PARTNER_ID))))
                .andExpect(status().isConflict())
                .andDo(document("create-line-conflict"));
    }

    /**
     * {@code @Valid} on the controller parameter rejects a blank {@code businessPartnerId} before
     * a create ever reaches {@code LineService} -- see <<error-responses>> for how {@link
     * li.selman.optimisticlocking.line.web.ApiExceptionHandler} enriches the response with an
     * {@code errors} array beyond Spring's own generic ProblemDetail for this case.
     */
    @Test
    void createLine_blankBusinessPartnerIdFailsValidation() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(put("/lines/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                        .header("X-Partner-Id", LineFixture.BUSINESS_PARTNER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateLineRequest(1, 5, " "))))
                .andExpect(status().isBadRequest())
                .andDo(
                        document(
                                "create-line-validation-failed",
                                relaxedResponseFields(
                                        fieldWithPath("status")
                                                .description(
                                                        "The HTTP status code, repeated in the body per RFC 9457."),
                                        fieldWithPath("title")
                                                .description(
                                                        "Short, human-readable summary of the problem -- the HTTP status's reason phrase."),
                                        fieldWithPath("detail")
                                                .description(
                                                        "Spring's own generic Bean Validation message -- deliberately unspecific, since it never names which field failed."),
                                        fieldWithPath("instance")
                                                .description("The request path that produced this problem."),
                                        fieldWithPath("errors")
                                                .description(
                                                        "Extension property added by ApiExceptionHandler: one entry per failed constraint."),
                                        fieldWithPath("errors[].field")
                                                .description("Name of the request field that failed validation."),
                                        fieldWithPath("errors[].message")
                                                .description(
                                                        "The Bean Validation constraint's message, e.g. `@NotBlank`'s default."))));
    }

    @Test
    void getLine() throws Exception {
        UUID id = UUID.randomUUID();
        lineRepository.save(LineFixture.newBuilder().id(id).line(2, 5).build());

        mockMvc.perform(get("/lines/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                        .header("X-Partner-Id", LineFixture.BUSINESS_PARTNER_ID)
                        .accept(MediaTypes.HAL_JSON))
                .andExpect(status().isOk())
                .andDo(document(
                        "get-line",
                        pathParameters(parameterWithName("id").description("Id of the line.")),
                        requestHeaders(headerWithName("X-Partner-Id")
                                .description("Business partner context this request acts in -- see "
                                        + "<<business-partner-scoping>>.")),
                        responseHeaders(
                                headerWithName(HttpHeaders.ETAG)
                                        .description(
                                                "Current aggregate version, quoted; reflect it back as `If-Match` to edit or delete.")),
                        relaxedResponseFields(
                                fieldWithPath("id").description("The line's id."),
                                fieldWithPath("businessPartnerId").description("Owning business partner."),
                                fieldWithPath("_embedded.leftPoint.id").description("Id of the left point."),
                                fieldWithPath("_embedded.leftPoint.position")
                                        .description("Current position of the left point."),
                                fieldWithPath("_embedded.leftPoint.numberOfUpdates")
                                        .description("How many times the left point has been moved so far."),
                                fieldWithPath("_embedded.rightPoint.id").description("Id of the right point."),
                                fieldWithPath("_embedded.rightPoint.position")
                                        .description("Current position of the right point."),
                                fieldWithPath("_embedded.rightPoint.numberOfUpdates")
                                        .description("How many times the right point has been moved so far.")),
                        relaxedLinks(linkWithRel("self").description("This line."))));
    }

    @Test
    void getLine_notModifiedWhenIfNoneMatchMatchesCurrentETag() throws Exception {
        UUID id = UUID.randomUUID();
        lineRepository.save(LineFixture.newBuilder().id(id).build());

        mockMvc.perform(get("/lines/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                        .header("X-Partner-Id", LineFixture.BUSINESS_PARTNER_ID)
                        .header(HttpHeaders.IF_NONE_MATCH, "\"1\""))
                .andExpect(status().isNotModified())
                .andDo(document(
                        "get-line-not-modified",
                        requestHeaders(headerWithName(HttpHeaders.IF_NONE_MATCH)
                                .description("Entity-tag the client already holds a cached representation for."))));
    }

    @Test
    void moveLeft() throws Exception {
        UUID id = UUID.randomUUID();
        lineRepository.save(
                LineFixture.newBuilder().id(id).line(3, 9).lockVersion(1).build());

        mockMvc.perform(put("/lines/{id}/left/move-right", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                        .header("X-Partner-Id", LineFixture.BUSINESS_PARTNER_ID)
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new MoveRequest(2))))
                .andExpect(status().isOk())
                .andDo(document(
                        "move-left",
                        pathParameters(parameterWithName("id").description("Id of the line.")),
                        requestHeaders(
                                headerWithName(HttpHeaders.IF_MATCH)
                                        .description(
                                                "Mandatory precondition: the version this write is conditional on."),
                                headerWithName("X-Partner-Id")
                                        .description("Business partner context this request acts in -- see "
                                                + "<<business-partner-scoping>>.")),
                        relaxedRequestFields(fieldWithPath("by")
                                .description("Positive magnitude to move the left point to the right by -- "
                                        + "the URL (which point, `move-left`/`move-right`) fixes the sign.")),
                        responseHeaders(headerWithName(HttpHeaders.ETAG)
                                .description("New aggregate version -- bumped even though only the left "
                                        + "point's row changed, via the forced-increment read.")),
                        relaxedResponseFields(fieldWithPath("_embedded.leftPoint.position")
                                .description("New position of the left point."))));
    }

    @Test
    void moveLeft_missingIfMatchIsRejected() throws Exception {
        UUID id = UUID.randomUUID();
        lineRepository.save(
                LineFixture.newBuilder().id(id).line(3, 9).lockVersion(1).build());

        // 428 Precondition Required (RFC 6585 SS3): without a stated If-Match, a lost update could
        // never be told apart from a legitimate move -- so this app makes the precondition mandatory.
        mockMvc.perform(put("/lines/{id}/left/move-right", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                        .header("X-Partner-Id", LineFixture.BUSINESS_PARTNER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new MoveRequest(1))))
                .andExpect(status().is(428))
                .andDo(document("move-left-missing-precondition"));
    }

    /**
     * The canonical example for <<error-responses>>: every error this API returns, whatever the
     * status code, shares this same {@code application/problem+json} (RFC 9457) shape.
     */
    @Test
    void moveLeft_nonexistentLine_returns404ProblemDetail() throws Exception {
        UUID id = UUID.randomUUID(); // never saved

        mockMvc.perform(put("/lines/{id}/left/move-right", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                        .header("X-Partner-Id", LineFixture.BUSINESS_PARTNER_ID)
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new MoveRequest(1))))
                .andExpect(status().isNotFound())
                .andDo(document(
                        "move-left-not-found",
                        relaxedResponseFields(
                                fieldWithPath("status")
                                        .description("The HTTP status code, repeated in the body per RFC 9457."),
                                fieldWithPath("title")
                                        .description(
                                                "Short, human-readable summary of the problem -- the HTTP status's reason phrase."),
                                fieldWithPath("detail")
                                        .description(
                                                "Human-readable explanation specific to this occurrence of the problem."),
                                fieldWithPath("instance")
                                        .description("The request path that produced this problem."))));
    }

    @Test
    void moveLeft_staleIfMatchIsRejected() throws Exception {
        UUID id = UUID.randomUUID();
        lineRepository.save(
                LineFixture.newBuilder().id(id).line(3, 9).lockVersion(1).build());

        mockMvc.perform(put("/lines/{id}/left/move-right", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                        .header("X-Partner-Id", LineFixture.BUSINESS_PARTNER_ID)
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new MoveRequest(1))))
                .andExpect(status().isOk());

        // 412 Precondition Failed (RFC 9110 SS13.1.1): the stated version ("1") no longer
        // strong-matches the current one (now "2") -- someone else's move landed first.
        mockMvc.perform(put("/lines/{id}/left/move-right", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                        .header("X-Partner-Id", LineFixture.BUSINESS_PARTNER_ID)
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new MoveRequest(1))))
                .andExpect(status().is(412))
                .andDo(document("move-left-stale-precondition"));
    }

    @Test
    void moveLeft_belowZeroViolatesInvariant() throws Exception {
        UUID id = UUID.randomUUID();
        // left is already at 0: moving further left would push it negative.
        lineRepository.save(
                LineFixture.newBuilder().id(id).line(0, 5).lockVersion(1).build());

        // 422 Unprocessable Content: syntactically valid request, semantically impossible --
        // `Line.moveLeft` never lets the left point go below zero.
        mockMvc.perform(put("/lines/{id}/left/move-left", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                        .header("X-Partner-Id", LineFixture.BUSINESS_PARTNER_ID)
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new MoveRequest(1))))
                .andExpect(status().is(422))
                .andDo(document("move-left-below-zero"));
    }

    @Test
    void moveLeft_updateBudgetExhaustedViolatesInvariant() throws Exception {
        UUID id = UUID.randomUUID();
        // 5 updates already spent across both points -- the line's total update budget (see
        // Line.MAX_UPDATES) is exhausted, regardless of which point this move targets.
        lineRepository.save(LineFixture.newBuilder()
                .id(id)
                .line(3, 9)
                .lockVersion(1)
                .leftUpdates(3)
                .rightUpdates(2)
                .build());

        mockMvc.perform(put("/lines/{id}/left/move-right", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                        .header("X-Partner-Id", LineFixture.BUSINESS_PARTNER_ID)
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new MoveRequest(1))))
                .andExpect(status().is(422))
                .andDo(document("move-left-update-budget-exhausted"));
    }

    @Test
    void moveLeft_idempotentRetryReplaysTheOriginalResult() throws Exception {
        UUID id = UUID.randomUUID();
        lineRepository.save(
                LineFixture.newBuilder().id(id).line(3, 9).lockVersion(1).build());
        String idempotencyKey = UUID.randomUUID().toString();

        mockMvc.perform(put("/lines/{id}/left/move-right", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                        .header("X-Partner-Id", LineFixture.BUSINESS_PARTNER_ID)
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new MoveRequest(2))))
                .andExpect(status().isOk());

        // Retried with the very same (now-stale) If-Match: the recognised Idempotency-Key
        // short-circuits straight to a replay of the first attempt's result, skipping the
        // precondition check entirely -- so this must not become a 412.
        mockMvc.perform(put("/lines/{id}/left/move-right", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                        .header("X-Partner-Id", LineFixture.BUSINESS_PARTNER_ID)
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new MoveRequest(2))))
                .andExpect(status().isOk())
                .andDo(document(
                        "move-left-idempotent-replay",
                        requestHeaders(headerWithName("Idempotency-Key")
                                .description("Client-generated key correlating retries of the exact same intent."))));
    }

    @Test
    void deleteLine() throws Exception {
        UUID id = UUID.randomUUID();
        lineRepository.save(LineFixture.newBuilder().id(id).build());

        mockMvc.perform(delete("/lines/{id}", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + deleteBearerToken))
                .andExpect(status().isNoContent())
                .andDo(document(
                        "delete-line", pathParameters(parameterWithName("id").description("Id of the line."))));
    }
}
