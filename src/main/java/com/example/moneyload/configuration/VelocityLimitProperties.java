package com.example.moneyload.configuration;

import com.example.moneyload.domain.Money;
import com.example.moneyload.domain.VelocityPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "velocity.limits")
public final class VelocityLimitProperties {
    private final VelocityPolicy policy;

    @ConstructorBinding
    public VelocityLimitProperties(BigDecimal dailyAmount, BigDecimal weeklyAmount, Integer dailyCount) {
        if (dailyCount == null) {
            throw new IllegalArgumentException("velocity.limits.daily-count is required");
        }
        policy = new VelocityPolicy(toCents("daily-amount", dailyAmount),
                toCents("weekly-amount", weeklyAmount), dailyCount);
    }

    public VelocityPolicy policy() {
        return policy;
    }

    private static long toCents(String property, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("velocity.limits." + property + " must be greater than zero");
        }
        try {
            return Money.fromDecimal(amount).cents();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("velocity.limits." + property
                    + " must contain whole cents and fit in a long", exception);
        }
    }
}
