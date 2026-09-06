package com.example.moneyload.configuration;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import static org.assertj.core.api.Assertions.*;

class FileProcessingConfigurationTests {
    @Test
    void bindsDefaults() {
        try (var context = context(Map.of())) {
            context.refresh();
            assertThat(context.getBean(FileProcessingProperties.class))
                    .isEqualTo(new FileProcessingProperties(4, 256, 2, Duration.ofMinutes(5)));
        }
    }

    @Test
    void bindsOverrides() {
        try (var context = context(Map.of("processing.file.workers", "8", "processing.file.window-size", "512",
                "processing.file.max-concurrent-uploads", "3", "processing.file.timeout", "30s"))) {
            context.refresh();
            assertThat(context.getBean(FileProcessingProperties.class))
                    .isEqualTo(new FileProcessingProperties(8, 512, 3, Duration.ofSeconds(30)));
        }
    }

    @ParameterizedTest
    @CsvSource({"workers,0", "workers,-1", "workers,abc", "window-size,3", "max-concurrent-uploads,0",
            "timeout,0s", "timeout,-1s", "timeout,invalid", "timeout,999999999999d"})
    void invalidSettingsFailStartup(String property, String value) {
        try (var context = context(Map.of("processing.file." + property, value))) {
            assertThatThrownBy(context::refresh).hasStackTraceContaining(property);
        }
    }

    private AnnotationConfigApplicationContext context(Map<String, Object> values) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", values));
        context.register(FileProcessingConfiguration.class);
        return context;
    }
}
