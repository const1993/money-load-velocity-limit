package com.example.moneyload.adapter.inbound.file;

import com.example.moneyload.adapter.inbound.rest.RestJsonConfiguration;
import com.example.moneyload.application.LoadFundsService;
import com.example.moneyload.application.LoadOutcome;
import com.example.moneyload.application.port.LoadResultRepository.StoredLoadResult;
import com.example.moneyload.domain.LoadAttempt;
import com.example.moneyload.domain.LoadDecision;
import com.example.moneyload.domain.DecisionReason;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class LoadFileProcessorTests {
    private final LoadFundsService service = mock(LoadFundsService.class);
    private final LoadFileProcessor processor = new SequentialLoadFileProcessor(service, new RestJsonConfiguration().restJsonMapper());
    private final StringWriter output = new StringWriter();

    @Test
    void writesExactCompactDecisionsInInputOrderAndSkipsDuplicates() throws Exception {
        when(service.process(any())).thenAnswer(call -> {
            LoadAttempt attempt = call.getArgument(0);
            var stored = new StoredLoadResult(attempt, new LoadDecision(attempt.loadId().equals("1")
                    ? DecisionReason.ACCEPTED : DecisionReason.DAILY_AMOUNT_LIMIT_EXCEEDED), Instant.EPOCH);
            return attempt.loadId().equals("3") ? new LoadOutcome.Duplicate(stored) : new LoadOutcome.Completed(stored);
        });
        var counts = process(line("1", "$1.00") + line("2", "$6000.00") + line("3", "$1.00"));
        assertThat(output.toString()).isEqualTo("""
                {"id":"1","customer_id":"c","accepted":true}
                {"id":"2","customer_id":"c","accepted":false}
                """);
        assertThat(counts).isEqualTo(new LoadFileProcessor.Counts(3, 1, 1, 1));
        var order = inOrder(service);
        for (String id : new String[]{"1", "2", "3"}) {
            order.verify(service).process(argThat(attempt -> attempt.loadId().equals(id)));
        }
        order.verifyNoMoreInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", "[]", "{}", " ",
            "{\"id\":\"x\"} {\"id\":\"y\"}"})
    void malformedInputStopsBeforeLaterLines(String invalid) {
        assertThatThrownBy(() -> process(invalid + "\n" + line("later", "$1.00")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid file input at line 1");
        verifyNoInteractions(service);
        assertThat(output.toString()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.00", "$1.001", "$-1.00", "$1e2"})
    void invalidAmountStopsProcessing(String amount) {
        assertThatThrownBy(() -> process(line("1", amount) + line("later", "$1.00")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("line 1");
        verifyNoInteractions(service);
    }

    @Test
    void invalidSecondLineReportsItsNumberAndStops() {
        when(service.process(any())).thenAnswer(call -> new LoadOutcome.Completed(new StoredLoadResult(
                call.getArgument(0), new LoadDecision(DecisionReason.ACCEPTED), Instant.EPOCH)));
        assertThatThrownBy(() -> process(line("1", "$1.00") + "invalid\n" + line("3", "$1.00")))
                .hasMessage("Invalid file input at line 2");
        verify(service).process(any());
        verifyNoMoreInteractions(service);
    }

    @Test
    void technicalFailureStopsWithoutDeclineOrRetry() {
        var failure = new IllegalStateException("database unavailable");
        when(service.process(any())).thenThrow(failure);
        assertThatThrownBy(() -> process(line("1", "$1.00") + line("2", "$1.00")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("line 1").hasCause(failure);
        verify(service).process(any());
        verifyNoMoreInteractions(service);
        assertThat(output.toString()).isEmpty();
    }

    @Test
    void emptyFileProducesNothing() throws Exception {
        assertThat(process("")).isEqualTo(new LoadFileProcessor.Counts(0, 0, 0, 0));
        assertThat(output.toString()).isEmpty();
        verifyNoInteractions(service);
    }

    private LoadFileProcessor.Counts process(String input) throws Exception {
        try (var reader = new BufferedReader(new StringReader(input)); var writer = new BufferedWriter(output)) {
            return processor.process(reader, writer);
        }
    }

    private String line(String id, String amount) {
        return "{\"id\":\"" + id + "\",\"customer_id\":\"c\",\"load_amount\":\"" + amount
                + "\",\"time\":\"2018-01-01T00:00:00Z\"}\n";
    }
}
