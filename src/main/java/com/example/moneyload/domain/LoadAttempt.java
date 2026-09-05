package com.example.moneyload.domain;

import java.time.Instant;
import java.util.Objects;

public record LoadAttempt(String loadId, String customerId, Money amount, Instant eventTimestamp) {
    public LoadAttempt {
        if (loadId == null || loadId.isBlank()) {
            throw new IllegalArgumentException("loadId must not be blank");
        }
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("customerId must not be blank");
        }
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(eventTimestamp, "eventTimestamp");
    }
}
