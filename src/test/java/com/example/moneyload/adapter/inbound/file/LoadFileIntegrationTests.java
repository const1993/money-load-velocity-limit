package com.example.moneyload.adapter.inbound.file;

import com.example.moneyload.MoneyLoadApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import static org.assertj.core.api.Assertions.*;

class LoadFileIntegrationTests {
    @TempDir Path directory;

    @Test
    void processorProcessesFixtureThroughRealServiceAndH2() throws Exception {
        Path input = directory.resolve("input.txt");
        Path output = directory.resolve("output.txt");
        try (var fixture = getClass().getResourceAsStream("/file/input.txt")) {
            Files.copy(fixture, input);
        }
        try (var context = SpringApplication.run(MoneyLoadApplication.class,
                "--spring.main.web-application-type=none",
                "--spring.datasource.url=jdbc:h2:mem:file-fixture;DB_CLOSE_ON_EXIT=FALSE")) {
            assertThat(Files.exists(output)).isFalse();
            try (var reader = Files.newBufferedReader(input); var writer = Files.newBufferedWriter(output)) {
                context.getBean(SequentialLoadFileProcessor.class).process(reader, writer);
            }
            try (var fixture = getClass().getResourceAsStream("/file/expected.txt")) {
                assertThat(Files.readString(output)).isEqualTo(new String(fixture.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
    }
    @Test
    void parallelCustomersMatchSequentialDecisionsAndCounters() throws Exception {
        StringBuilder input = new StringBuilder();
        StringBuilder expected = new StringBuilder();
        for (int id = 1; id <= 4; id++) {
            for (String customer : new String[]{"a", "b"}) {
                input.append("{\"id\":\"").append(id).append("\",\"customer_id\":\"").append(customer)
                        .append("\",\"load_amount\":\"$2000.00\",\"time\":\"2018-01-01T00:00:00Z\"}\n");
                expected.append("{\"id\":\"").append(id).append("\",\"customer_id\":\"").append(customer)
                        .append("\",\"accepted\":").append(id <= 2).append("}\n");
            }
        }
        for (int workers : new int[]{1, 4}) {
            try (var context = SpringApplication.run(MoneyLoadApplication.class,
                    "--spring.main.web-application-type=none",
                    "--spring.datasource.url=jdbc:h2:mem:file-workers-" + workers + ";DB_CLOSE_ON_EXIT=FALSE",
                    "--processing.file.workers=" + workers, "--processing.file.window-size=4")) {
                LoadFileProcessor processor = workers == 1 ? context.getBean(SequentialLoadFileProcessor.class)
                        : context.getBean(ParallelLoadFileProcessor.class);
                for (boolean replay : new boolean[]{false, true}) {
                    var output = new java.io.StringWriter();
                    try (var reader = new java.io.BufferedReader(new java.io.StringReader(input.toString()));
                         var writer = new java.io.BufferedWriter(output)) {
                        var counts = processor.process(reader, writer, replay);
                        assertThat(counts).isEqualTo(replay ? new LoadFileProcessor.Counts(8, 0, 0, 8)
                                : new LoadFileProcessor.Counts(8, 4, 4, 0));
                    }
                    assertThat(output.toString()).isEqualTo(expected.toString());
                }
                var jdbc = context.getBean(org.springframework.jdbc.core.simple.JdbcClient.class);
                assertThat(jdbc.sql("SELECT COUNT(*) FROM load_attempt").query(Integer.class).single()).isEqualTo(8);
                // Replays do not change the two accepted loads per customer.
                assertThat(jdbc.sql("SELECT SUM(accepted_count) FROM daily_velocity").query(Integer.class).single()).isEqualTo(4);
            }
        }
    }

}
