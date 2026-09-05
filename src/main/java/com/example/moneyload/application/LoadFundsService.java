package com.example.moneyload.application;

import com.example.moneyload.domain.LoadAttempt;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class LoadFundsService {
    private final LoadFundsTransaction transaction;

    public LoadFundsService(LoadFundsTransaction transaction) {
        this.transaction = transaction;
    }

    public LoadOutcome process(LoadAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        try {
            return transaction.process(attempt);
        } catch (LoadFundsTransaction.DuplicateResultInsert collision) {
            // The proxy has completed rollback before a separate recovery transaction starts.
            // This retrieves the winning result without retrying the load.
            return transaction.recoverDuplicate(attempt, collision.failure());
        }
    }
}
