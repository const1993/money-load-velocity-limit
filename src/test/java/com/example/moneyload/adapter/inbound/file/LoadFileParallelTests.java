package com.example.moneyload.adapter.inbound.file;

import com.example.moneyload.adapter.inbound.rest.RestJsonConfiguration;
import com.example.moneyload.application.LoadFundsService;
import com.example.moneyload.application.LoadOutcome;
import com.example.moneyload.application.port.LoadResultRepository.StoredLoadResult;
import com.example.moneyload.domain.*;
import java.io.*;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.MDC;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@Timeout(15)
class LoadFileParallelTests {
    @Test
    void customersOverlapButCustomerAndOutputOrderRemainStableWithBoundedReadAhead() throws Exception {
        var service = mock(LoadFundsService.class);
        var processor = new ParallelLoadFileProcessor(service, new RestJsonConfiguration().restJsonMapper(), 2, 4);
        var firstStarted = new CountDownLatch(1);
        var otherFinished = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var read = new AtomicInteger();
        var callsA = new CopyOnWriteArrayList<String>();
        var callsB = new CopyOnWriteArrayList<String>();
        when(service.process(any())).thenAnswer(call -> {
            LoadAttempt attempt = call.getArgument(0);
            assertThat(MDC.get("request_id")).isEqualTo("parallel-test");
            (attempt.customerId().equals("a") ? callsA : callsB).add(attempt.loadId());
            if (attempt.loadId().equals("1")) {
                firstStarted.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            }
            if (attempt.loadId().equals("2")) {
                assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
                otherFinished.countDown();
            }
            var stored = new StoredLoadResult(attempt, new LoadDecision(DecisionReason.ACCEPTED), Instant.EPOCH);
            return attempt.loadId().equals("3") ? new LoadOutcome.Duplicate(stored) : new LoadOutcome.Completed(stored);
        });
        String input = line("1", "a") + line("2", "b") + line("3", "a") + line("4", "b") + line("5", "a");
        var reader = new BufferedReader(new StringReader(input)) {
            @Override public String readLine() throws IOException {
                read.incrementAndGet();
                return super.readLine();
            }
        };
        var output = new StringWriter();
        try (var caller = Executors.newSingleThreadExecutor()) {
            var result = caller.submit(() -> {
                MDC.put("request_id", "parallel-test");
                try (var writer = new BufferedWriter(output)) {
                    return processor.process(reader, writer);
                } finally {
                    MDC.clear();
                }
            });
            try {
                assertThat(otherFinished.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(read.get()).isEqualTo(4);
                assertThat(callsA).containsExactly("1");
            } finally {
                release.countDown();
            }
            assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(new LoadFileProcessor.Counts(5, 4, 0, 1));
        }
        assertThat(callsA).containsExactly("1", "3", "5");
        assertThat(callsB).containsExactly("2", "4");
        assertThat(output.toString()).isEqualTo("""
                {"id":"1","customer_id":"a","accepted":true}
                {"id":"2","customer_id":"b","accepted":true}
                {"id":"4","customer_id":"b","accepted":true}
                {"id":"5","customer_id":"a","accepted":true}
                """);
    }

    @Test
    void failureStopsItsLaneAndPreventsReadingNextWindow() throws Exception {
        var service = mock(LoadFundsService.class);
        var processor = new ParallelLoadFileProcessor(service, new RestJsonConfiguration().restJsonMapper(), 2, 2);
        var read = new AtomicInteger();
        var reader = new BufferedReader(new StringReader(line("1", "a") + line("2", "a") + line("3", "b"))) {
            @Override public String readLine() throws IOException {
                read.incrementAndGet();
                return super.readLine();
            }
        };
        var cause = new IllegalStateException("database failure");
        when(service.process(any())).thenThrow(cause);
        var output = new StringWriter();
        assertThatThrownBy(() -> processor.process(reader, new BufferedWriter(output)))
                .isInstanceOf(FileLoadProcessingException.class).hasMessageContaining("line 1").hasCause(cause);
        assertThat(read.get()).isEqualTo(2);
        verify(service).process(any());
        assertThat(output.toString()).isEmpty();
    }

    @Test
    void rejectsInvalidWorkerConfiguration() {
        var service = mock(LoadFundsService.class);
        var json = new RestJsonConfiguration().restJsonMapper();
        assertThatThrownBy(() -> new ParallelLoadFileProcessor(service, json, 0, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParallelLoadFileProcessor(service, json, 4, 2)).isInstanceOf(IllegalArgumentException.class);
    }

    private static String line(String id, String customer) {
        return "{\"id\":\"" + id + "\",\"customer_id\":\"" + customer
                + "\",\"load_amount\":\"$1.00\",\"time\":\"2018-01-01T00:00:00Z\"}\n";
    }
}
