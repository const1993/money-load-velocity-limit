package com.example.moneyload.domain;

public record VelocityPolicy(long dailyAmountLimitCents, long weeklyAmountLimitCents, int dailyCountLimit) {
    public VelocityPolicy {
        if (dailyAmountLimitCents <= 0) {
            throw new IllegalArgumentException("daily-amount must be greater than zero");
        }
        if (weeklyAmountLimitCents <= 0) {
            throw new IllegalArgumentException("weekly-amount must be greater than zero");
        }
        if (dailyCountLimit <= 0) {
            throw new IllegalArgumentException("daily-count must be greater than zero");
        }
        if (weeklyAmountLimitCents < dailyAmountLimitCents) {
            throw new IllegalArgumentException("weekly-amount must be at least daily-amount");
        }
    }
}
