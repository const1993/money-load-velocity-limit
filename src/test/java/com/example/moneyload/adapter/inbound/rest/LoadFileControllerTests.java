package com.example.moneyload.adapter.inbound.rest;

import com.example.moneyload.adapter.inbound.file.SequentialLoadFileProcessor;
import com.example.moneyload.adapter.inbound.file.ParallelLoadFileProcessor;
import com.example.moneyload.application.LoadFundsService;
import com.example.moneyload.application.LoadOutcome;
import com.example.moneyload.application.error.TechnicalFailureClassifier;
import com.example.moneyload.application.port.LoadResultRepository.StoredLoadResult;
import com.example.moneyload.domain.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

class LoadFileControllerTests {
    private final LoadFundsService service = mock(LoadFundsService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var json = new RestJsonConfiguration().restJsonMapper();
        mvc = MockMvcBuilders.standaloneSetup(new LoadFileController(new SequentialLoadFileProcessor(service, json), new ParallelLoadFileProcessor(service, json)))
                .setControllerAdvice(new LoadExceptionHandler(new TechnicalFailureClassifier()))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(json)).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v1/loads/file/sequential", "/v1/loads/file/parallel"})
    void uploadReturnsExactOrderedJsonLinesWithoutDuplicates(String endpoint) throws Exception {
        when(service.process(any())).thenAnswer(call -> {
            LoadAttempt attempt = call.getArgument(0);
            var result = new StoredLoadResult(attempt, new LoadDecision(attempt.loadId().equals("1")
                    ? DecisionReason.ACCEPTED : DecisionReason.DAILY_AMOUNT_LIMIT_EXCEEDED), Instant.EPOCH);
            return attempt.loadId().equals("3") ? new LoadOutcome.Duplicate(result) : new LoadOutcome.Completed(result);
        });
        var response = mvc.perform(multipart(endpoint).file(file(line("1") + line("2") + line("3"))))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith("application/x-ndjson");
        assertThat(response.getContentAsString()).isEqualTo("""
                {"id":"1","customer_id":"c","accepted":true}
                {"id":"2","customer_id":"c","accepted":false}
                """);
        assertThat(response.getHeader("Content-Disposition")).isNull();
        verify(service, times(3)).process(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v1/loads/file/sequential", "/v1/loads/file/parallel"})
    void optionallyReplaysOriginalAcceptedAndDeclinedDuplicatesInOrder(String endpoint) throws Exception {
        when(service.process(any())).thenAnswer(call -> {
            LoadAttempt incoming = call.getArgument(0);
            var original = new LoadAttempt(incoming.loadId(), incoming.customerId(), new Money(600000), Instant.EPOCH);
            var stored = new StoredLoadResult(original, new LoadDecision(incoming.loadId().equals("1")
                    ? DecisionReason.ACCEPTED : DecisionReason.DAILY_AMOUNT_LIMIT_EXCEEDED), Instant.EPOCH);
            return incoming.loadId().equals("2") ? new LoadOutcome.Completed(stored) : new LoadOutcome.Duplicate(stored);
        });
        var response = mvc.perform(multipart(endpoint).param("includeDuplicates", "true")
                .file(file(line("1") + line("2") + line("3")))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("""
                {"id":"1","customer_id":"c","accepted":true}
                {"id":"2","customer_id":"c","accepted":false}
                {"id":"3","customer_id":"c","accepted":false}
                """);
        verify(service, times(3)).process(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v1/loads/file/sequential", "/v1/loads/file/parallel"})
    void explicitFalseSkipsDuplicates(String endpoint) throws Exception {
        when(service.process(any())).thenAnswer(call -> new LoadOutcome.Duplicate(new StoredLoadResult(
                call.getArgument(0), new LoadDecision(DecisionReason.ACCEPTED), Instant.EPOCH)));
        var response = mvc.perform(multipart(endpoint).param("includeDuplicates", "false")
                .file(file(line("1")))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEmpty();
        verify(service).process(any());
    }

    @Test
    void invalidLineReturns400AndStopsBeforeLaterLines() throws Exception {
        when(service.process(any())).thenAnswer(call -> new LoadOutcome.Completed(new StoredLoadResult(
                call.getArgument(0), new LoadDecision(DecisionReason.ACCEPTED), Instant.EPOCH)));
        var response = mvc.perform(multipart("/v1/loads/file/parallel")
                .file(file(line("1") + "invalid\n" + line("2")))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getHeader("Content-Disposition")).isNull();
        assertThat(response.getContentAsString()).contains("INVALID_REQUEST", "line 2").doesNotContain("accepted");
        verify(service).process(any());
    }

    @Test
    void technicalFailureReturns503WithoutDecisionsOrRetry() throws Exception {
        when(service.process(any())).thenThrow(new TransientDataAccessResourceException("unavailable"));
        var response = mvc.perform(multipart("/v1/loads/file/sequential").file(file(line("1") + line("2"))))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("SERVICE_UNAVAILABLE").doesNotContain("accepted");
        verify(service).process(any());
    }

    @Test
    void emptyUploadReturnsEmptyBody() throws Exception {
        var response = mvc.perform(multipart("/v1/loads/file/sequential").file(file(""))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEmpty();
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v1/loads/file/sequential", "/v1/loads/file/parallel"})
    void downloadOptionReturnsAttachment(String endpoint) throws Exception {
        var response = mvc.perform(multipart(endpoint).param("download", "true").file(file("")))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"output.txt\"");
    }

    @Test
    void missingFileReturns400() throws Exception {
        assertThat(mvc.perform(multipart("/v1/loads/file/sequential")).andReturn().getResponse().getStatus()).isEqualTo(400);
        verifyNoInteractions(service);
    }

    @Test
    void admissionLimitRejectsConcurrentWorkAndReleasesPermitAfterFailure() throws Exception {
        var sequential = mock(SequentialLoadFileProcessor.class);
        var parallel = mock(ParallelLoadFileProcessor.class);
        var controller = new LoadFileController(sequential, parallel, 1, java.time.Duration.ofMinutes(1));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(sequential.process(any(), any(), anyBoolean())).thenAnswer(_ -> {
            entered.countDown();
            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Worker not released");
            throw new IllegalStateException("test failure");
        }).thenReturn(new com.example.moneyload.adapter.inbound.file.LoadFileProcessor.Counts(0, 0, 0, 0));
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> {
                assertThatThrownBy(() -> controller.sequential(file(""), false, false,
                        new org.springframework.mock.web.MockHttpServletResponse())).hasMessage("test failure");
            });
            try {
                assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> controller.parallel(file(""), false, false,
                        new org.springframework.mock.web.MockHttpServletResponse()))
                        .isInstanceOf(TransientDataAccessResourceException.class);
                verifyNoInteractions(parallel);
            } finally {
                release.countDown();
            }
            first.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        controller.sequential(file(""), false, false, new org.springframework.mock.web.MockHttpServletResponse());
    }

    @Test
    void expiredDeadlineStopsBeforeServiceCall() {
        var json = new RestJsonConfiguration().restJsonMapper();
        var controller = new LoadFileController(new SequentialLoadFileProcessor(service, json),
                new ParallelLoadFileProcessor(service, json), 1, java.time.Duration.ofNanos(1));
        assertThatThrownBy(() -> controller.sequential(file(line("1")), false, false,
                new org.springframework.mock.web.MockHttpServletResponse()))
                .isInstanceOf(TransientDataAccessResourceException.class);
        verifyNoInteractions(service);
    }

    private MockMultipartFile file(String body) {
        return new MockMultipartFile("file", "input.txt", "text/plain", body.getBytes(StandardCharsets.UTF_8));
    }

    private String line(String id) {
        return "{\"id\":\"" + id + "\",\"customer_id\":\"c\",\"load_amount\":\"$1.00\",\"time\":\"2018-01-01T00:00:00Z\"}\n";
    }
}
