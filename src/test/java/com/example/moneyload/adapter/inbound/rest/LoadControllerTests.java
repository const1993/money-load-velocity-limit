package com.example.moneyload.adapter.inbound.rest;

import com.example.moneyload.application.LoadOutcome;
import com.example.moneyload.application.LoadFundsService;
import com.example.moneyload.application.error.TechnicalFailureClassifier;
import com.example.moneyload.application.port.LoadResultRepository.StoredLoadResult;
import com.example.moneyload.domain.*;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.MDC;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class LoadControllerTests {
    private static final String BODY = """
            {"id":"load","customer_id":"customer","load_amount":"$100.00","time":"2018-01-01T03:00:00+03:00"}
            """;
    private final LoadFundsService service = mock(LoadFundsService.class);
    private final JsonMapper json = new RestJsonConfiguration().restJsonMapper();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new LoadController(service))
                .setControllerAdvice(new LoadExceptionHandler(new TechnicalFailureClassifier()))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(json))
                .addFilters(new RequestIdFilter()).build();
    }

    @ParameterizedTest
    @EnumSource(DecisionReason.class)
    void businessDecisionsReturnOnlyContractFields(DecisionReason reason) throws Exception {
        when(service.process(any())).thenAnswer(call -> {
            LoadAttempt load = call.getArgument(0);
            assertThat(load).isEqualTo(new LoadAttempt("load", "customer", new Money(10000),
                    Instant.parse("2018-01-01T00:00:00Z")));
            assertThat(MDC.get("request_id")).isEqualTo("client-123");
            return new LoadOutcome.Completed(stored(reason));
        });
        var response = mvc.perform(post("/v1/loads").contentType("application/json")
                .header("X-Request-Id", "client-123").content(BODY)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).isEqualTo("{\"id\":\"load\",\"customer_id\":\"customer\",\"accepted\":"
                + (reason == DecisionReason.ACCEPTED) + "}");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("client-123");
        assertThat(MDC.get("request_id")).isNull();
        verify(service).process(any());
    }

    @ParameterizedTest
    @EnumSource(DecisionReason.class)
    void duplicatesReturnOriginalDecision(DecisionReason reason) throws Exception {
        when(service.process(any())).thenReturn(new LoadOutcome.Duplicate(stored(reason)));
        var response = request(BODY);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(json.readTree(response.getContentAsString()).get("accepted").asBoolean())
                .isEqualTo(reason == DecisionReason.ACCEPTED);
        assertThat(json.readTree(response.getContentAsString()).size()).isEqualTo(3);
        verify(service).process(any());
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void invalidRequestsAre400AndNeverReachService(String body) throws Exception {
        var response = request(body);
        assertError(response, 400, "INVALID_REQUEST");
        verifyNoInteractions(service);
        assertThat(MDC.get("request_id")).isNull();
    }

    static Stream<String> invalidBodies() {
        return Stream.of("{bad", "", "null", "[]", BODY + "{}",
                BODY.replace("\"id\":\"load\",", ""), BODY.replace("\"load\"", "\" \""),
                BODY.replace("\"customer_id\":\"customer\",", ""), BODY.replace("\"customer\"", "\" \""),
                BODY.replace("$100.00", "100.00"), BODY.replace("$100.00", "$1.001"),
                BODY.replace("$100.00", "$-1.00"), BODY.replace("\"$100.00\"", "null"),
                BODY.replace("2018-01-01T03:00:00+03:00", "bad"),
                BODY.replace("2018-01-01T03:00:00+03:00", "2018-01-01T03:00:00"),
                BODY.replace("\"2018-01-01T03:00:00+03:00\"", "null"),
                BODY.replace("\"load\"", "123"), BODY.replace("\"load\"", "true"),
                BODY.replace("\"id\":", "\"extra\":true,\"id\":"),
                BODY.replace("\"id\":", "\"id\":\"other\",\"id\":"));
    }

    @Test
    void transientFailureReturns503ImmediatelyWithSameRequestIdAndNoDetails() throws Exception {
        when(service.process(any())).thenThrow(new TransientDataAccessResourceException("SELECT secret FROM load_attempt"));
        var response = mvc.perform(post("/v1/loads").contentType("application/json")
                .header("X-Request-Id", "failure-123").content(BODY)).andReturn().getResponse();
        assertError(response, 503, "SERVICE_UNAVAILABLE");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("failure-123");
        assertThat(MDC.get("request_id")).isNull();
        verify(service).process(any());
    }

    @ParameterizedTest
    @MethodSource("internalFailures")
    void internalFailuresAre500IncludingIllegalArgumentException(RuntimeException failure) throws Exception {
        when(service.process(any())).thenThrow(failure);
        assertError(request(BODY), 500, "INTERNAL_ERROR");
        assertThat(MDC.get("request_id")).isNull();
        verify(service).process(any());
    }

    static Stream<RuntimeException> internalFailures() {
        return Stream.of(new IllegalStateException("secret SQL"), new IllegalArgumentException("internal bug"),
                new NullPointerException("mapping bug"));
    }

    @Test
    void generatedIdsAreUniqueAndInvalidIncomingIdsAreReplaced() throws Exception {
        when(service.process(any())).thenReturn(new LoadOutcome.Completed(stored(DecisionReason.ACCEPTED)));
        String first = request(BODY).getHeader("X-Request-Id");
        assertThat(UUID.fromString(first)).isNotNull();
        assertThat(request(BODY).getHeader("X-Request-Id")).isNotEqualTo(first);
        for (String invalid : new String[]{"", "has spaces", "x".repeat(129), "bad\nheader"}) {
            var response = mvc.perform(post("/v1/loads").contentType("application/json")
                    .header("X-Request-Id", invalid).content(BODY)).andReturn().getResponse();
            assertThat(UUID.fromString(response.getHeader("X-Request-Id"))).isNotNull();
        }
    }

    @Test
    void restoresExistingMdcEvenOnFailure() throws Exception {
        MDC.put("request_id", "outer");
        try {
            assertError(request("{bad"), 400, "INVALID_REQUEST");
            assertThat(MDC.get("request_id")).isEqualTo("outer");
        } finally {
            MDC.remove("request_id");
        }
    }

    @Test
    void unsupportedMediaAndMethodKeepHttpStatusAndStableErrorBody() throws Exception {
        assertError(mvc.perform(post("/v1/loads").contentType("text/plain").content(BODY))
                .andReturn().getResponse(), 415, "INVALID_REQUEST");
        assertError(mvc.perform(get("/v1/loads")).andReturn().getResponse(), 405, "INVALID_REQUEST");
        verifyNoInteractions(service);
    }

    private MockHttpServletResponse request(String body) throws Exception {
        return mvc.perform(post("/v1/loads").contentType("application/json").content(body)).andReturn().getResponse();
    }

    @ParameterizedTest
    @MethodSource("internalFailures")
    void adviceLogsTechnicalFailureExactlyOnceWithRequestContext(RuntimeException failure) throws Exception {
        var adviceLogger = (Logger) LoggerFactory.getLogger(LoadExceptionHandler.class);
        var events = new ListAppender<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        events.start();
        adviceLogger.addAppender(events);
        try {
            when(service.process(any())).thenThrow(failure);
            var response = request(BODY);
            assertError(response, 500, "INTERNAL_ERROR");
            assertThat(events.list).hasSize(1);
            var event = events.list.getFirst();
            assertThat(event.getLoggerName()).isEqualTo(LoadExceptionHandler.class.getName());
            assertThat(event.getMDCPropertyMap()).containsEntry("request_id", response.getHeader("X-Request-Id"));
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage()).doesNotContain("secret", "SQL", "internal bug", BODY);
            events.list.clear();
            assertError(request("{bad"), 400, "INVALID_REQUEST");
            assertError(request(BODY.replace("$100.00", "invalid")), 400, "INVALID_REQUEST");
            assertThat(events.list).isEmpty();
        } finally {
            adviceLogger.detachAppender(events);
            events.stop();
        }
    }

    private void assertError(MockHttpServletResponse response, int status, String code) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        var body = json.readTree(response.getContentAsString());
        assertThat(body.size()).isEqualTo(3);
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(body.get("message").asString()).isNotBlank();
        assertThat(body.get("request_id").asString()).isEqualTo(response.getHeader("X-Request-Id")).isNotBlank();
        assertThat(response.getContentAsString()).doesNotContain("SELECT", "secret", "load_attempt", "Exception", "accepted");
    }

    private StoredLoadResult stored(DecisionReason reason) {
        return new StoredLoadResult(new LoadAttempt("load", "customer", new Money(10000), Instant.EPOCH),
                new LoadDecision(reason), Instant.EPOCH);
    }
}
