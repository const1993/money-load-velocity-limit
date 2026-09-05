package com.example.moneyload.configuration;

import com.example.moneyload.domain.VelocityPolicy;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import static org.assertj.core.api.Assertions.*;

class VelocityConfigurationTests {
    private AnnotationConfigApplicationContext context(Map<String, Object> values) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", values));
        context.register(VelocityConfiguration.class);
        return context;
    }

    private Map<String, Object> defaults() {
        return new HashMap<>(Map.of("velocity.limits.daily-amount", "5000.00",
                "velocity.limits.weekly-amount", "20000.00", "velocity.limits.daily-count", "3"));
    }

    @Test
    void bindsDecimalsAndPublishesSingleImmutablePolicy() {
        try (var context = context(defaults())) {
            context.refresh();
            assertThat(context.getBean(VelocityPolicy.class)).isEqualTo(new VelocityPolicy(500000, 2000000, 3))
                    .isSameAs(context.getBean(VelocityLimitProperties.class).policy());
        }
    }

    @ParameterizedTest
    @CsvSource({"daily-amount,0", "daily-amount,-1", "weekly-amount,0", "weekly-amount,-1",
            "daily-count,0", "daily-count,-1", "daily-amount,10.001", "weekly-amount,20000.001",
            "weekly-amount,4999.99", "daily-amount,92233720368547758.08",
            "weekly-amount,92233720368547758.08", "daily-amount,abc", "daily-count,1.5"})
    void invalidConfigurationFailsContextStartup(String property, String value) {
        var values = defaults();
        values.put("velocity.limits." + property, value);
        try (var context = context(values)) {
            assertThatThrownBy(context::refresh).hasStackTraceContaining(property);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"daily-amount", "weekly-amount", "daily-count"})
    void missingConfigurationFailsStartup(String property) {
        var values = defaults();
        values.remove("velocity.limits." + property);
        try (var context = context(values)) {
            assertThatThrownBy(context::refresh).hasStackTraceContaining(property);
        }
    }

    @Test
    void allowsEqualDailyAndWeeklyAmounts() {
        var values = defaults();
        values.put("velocity.limits.weekly-amount", "5000.00");
        try (var context = context(values)) {
            context.refresh();
            assertThat(context.getBean(VelocityPolicy.class)).isEqualTo(new VelocityPolicy(500000, 500000, 3));
        }
    }
}
