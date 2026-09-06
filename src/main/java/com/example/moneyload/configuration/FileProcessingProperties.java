package com.example.moneyload.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("processing.file")
public record FileProcessingProperties(@DefaultValue("4") int workers,
                                       @DefaultValue("256") int windowSize,
                                       @DefaultValue("2") int maxConcurrentUploads,
                                       @DefaultValue("5m") Duration timeout) {
    public FileProcessingProperties {
        if (workers < 1) throw new IllegalArgumentException("processing.file.workers must be positive");
        if (windowSize < workers) {
            throw new IllegalArgumentException("processing.file.window-size must be at least workers");
        }
        if (maxConcurrentUploads < 1) {
            throw new IllegalArgumentException("processing.file.max-concurrent-uploads must be positive");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("processing.file.timeout must be positive");
        }
        try {
            timeout.toNanos();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("processing.file.timeout is too large", failure);
        }
    }
}
