package com.example.moneyload.application.port;

import com.example.moneyload.domain.LoadAttempt;
import com.example.moneyload.domain.LoadDecision;
import java.time.Instant;
import java.util.Optional;

public interface LoadResultRepository {
    /**
     * Inserts a completed decision, without committing the caller's transaction.
     * A duplicate key raises a persistence exception. The caller must roll back
     * the entire attempt before recovering a committed result in a new transaction.
     */
    void insertDecision(LoadAttempt attempt, LoadDecision decision, Instant createdAt);

    /** A lookup is not a reservation; database uniqueness arbitrates competing inserts. */
    Optional<StoredLoadResult> findResult(String customerId, String loadId);

    record StoredLoadResult(LoadAttempt attempt, LoadDecision decision, Instant createdAt) {
    }
}
