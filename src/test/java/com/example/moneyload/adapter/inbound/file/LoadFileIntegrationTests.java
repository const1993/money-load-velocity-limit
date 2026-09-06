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
                context.getBean(LoadFileProcessor.class).process(reader, writer);
            }
            try (var fixture = getClass().getResourceAsStream("/file/expected.txt")) {
                assertThat(Files.readString(output)).isEqualTo(new String(fixture.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
    }
}
