package com.example.moneyload.application;

import com.example.moneyload.domain.DecisionReason;
import com.example.moneyload.domain.LoadAttempt;
import com.example.moneyload.domain.VelocityBuckets;
import java.time.LocalDate;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class LoadFundsService {
    private static final Logger log = LoggerFactory.getLogger(LoadFundsService.class);
    private final LoadFundsTransaction transaction;

    public LoadFundsService(LoadFundsTransaction transaction) {
        this.transaction = transaction;
    }

    public LoadOutcome process(LoadAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        LoadOutcome outcome;
        try {
            outcome = transaction.process(attempt);
        } catch (LoadFundsTransaction.DuplicateResultInsert collision) {
            // The proxy has completed rollback before a separate recovery transaction starts.
            // This retrieves the winning result without retrying the load.
            outcome = transaction.recoverDuplicate(attempt, collision.failure());
        }
        logOutcome(attempt, VelocityBuckets.daily(attempt.eventTimestamp()),
                VelocityBuckets.weekly(attempt.eventTimestamp()), outcome);
        return outcome;
    }

    private void logOutcome(LoadAttempt attempt, LocalDate day, LocalDate week, LoadOutcome outcome) {
        String decision = switch (outcome) {
            case LoadOutcome.Completed completed -> completed.accepted() ? "ACCEPTED" : "DECLINED";
            case LoadOutcome.Duplicate ignored -> "DUPLICATE";
        };
        DecisionReason reason = switch (outcome) {
            case LoadOutcome.Completed completed -> completed.result().decision().reason();
            case LoadOutcome.Duplicate duplicate -> duplicate.originalResult().decision().reason();
        };
        // Logged only after transaction completion, never before a potentially failing commit.
        log.atDebug().addKeyValue("event", "load_decision")
                .addKeyValue("load_id", attempt.loadId()).addKeyValue("customer_id", attempt.customerId())
                .addKeyValue("daily_bucket", day).addKeyValue("weekly_bucket", week)
                .addKeyValue("amount_cents", attempt.amount().cents())
                .addKeyValue("decision", decision).addKeyValue("decision_reason", reason)
                .log("Load processing completed");
    }

}
