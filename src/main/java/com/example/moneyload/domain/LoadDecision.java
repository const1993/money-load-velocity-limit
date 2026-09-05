package com.example.moneyload.domain;

import java.util.Objects;

public record LoadDecision(DecisionReason reason) {
    public LoadDecision {
        Objects.requireNonNull(reason, "reason");
    }

    public boolean accepted() {
        return reason == DecisionReason.ACCEPTED;
    }
}
