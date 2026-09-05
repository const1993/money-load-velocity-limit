package com.example.moneyload.domain;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class LoadModelsTests {
    @Test
    void preservesLoadValues() {
        var attempt = new LoadAttempt("001", "002", new Money(0), Instant.EPOCH);
        assertThat(attempt.loadId()).isEqualTo("001");
        assertThat(attempt.customerId()).isEqualTo("002");
        assertThat(attempt.amount()).isEqualTo(new Money(0));
        assertThat(attempt.eventTimestamp()).isEqualTo(Instant.EPOCH);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsUnusableIdentifiers(String id) {
        assertThatIllegalArgumentException().isThrownBy(() -> new LoadAttempt(id, "c", new Money(1), Instant.EPOCH));
        assertThatIllegalArgumentException().isThrownBy(() -> new LoadAttempt("l", id, new Money(1), Instant.EPOCH));
    }

    @Test
    void rejectsMissingValues() {
        assertThatNullPointerException().isThrownBy(() -> new LoadAttempt("l", "c", null, Instant.EPOCH));
        assertThatNullPointerException().isThrownBy(() -> new LoadAttempt("l", "c", new Money(1), null));
        assertThatNullPointerException().isThrownBy(() -> new LoadDecision(null));
    }

    @ParameterizedTest
    @EnumSource(DecisionReason.class)
    void onlyAcceptedReasonMeansAccepted(DecisionReason reason) {
        assertThat(new LoadDecision(reason).accepted()).isEqualTo(reason == DecisionReason.ACCEPTED);
    }
}
