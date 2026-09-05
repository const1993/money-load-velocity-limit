package com.example.moneyload.application;

import com.example.moneyload.application.port.LoadResultRepository;
import com.example.moneyload.application.port.LoadResultRepository.StoredLoadResult;
import com.example.moneyload.application.port.VelocityRepository;
import com.example.moneyload.domain.DecisionReason;
import com.example.moneyload.domain.LoadAttempt;
import com.example.moneyload.domain.LoadDecision;
import com.example.moneyload.domain.VelocityBuckets;
import com.example.moneyload.domain.VelocityPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LoadFundsService {
    private static final Logger log = LoggerFactory.getLogger(LoadFundsService.class);

    private final LoadResultRepository loads;
    private final VelocityRepository velocity;
    private final VelocityPolicy policy;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public LoadFundsService(LoadResultRepository loads, VelocityRepository velocity, VelocityPolicy policy,
                            Clock clock, PlatformTransactionManager transactionManager) {
        this.loads = loads;
        this.velocity = velocity;
        this.policy = policy;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        // Own the complete attempt, even when invoked by a transactional caller.
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public LoadOutcome process(LoadAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        var day = VelocityBuckets.daily(attempt.eventTimestamp());
        var week = VelocityBuckets.weekly(attempt.eventTimestamp());
        LoadOutcome outcome;
        try {
            outcome = transaction.execute(status -> processInTransaction(attempt, day, week, status));
        } catch (DuplicateResultInsert collision) {
            // The failed transaction is fully rolled back before this lookup.
            // This recovers the winning result; it does not retry the load.
            outcome = transaction.execute(status -> new LoadOutcome.Duplicate(
                    loads.findResult(attempt.customerId(), attempt.loadId()).orElseThrow(() -> collision.failure)));
        }
        logOutcome(attempt, day, week, outcome);
        return outcome;
    }

    private LoadOutcome processInTransaction(LoadAttempt attempt, LocalDate day, LocalDate week,
                                              TransactionStatus status) {
        var existing = loads.findResult(attempt.customerId(), attempt.loadId());
        if (existing.isPresent()) {
            return new LoadOutcome.Duplicate(existing.get());
        }

        Instant now = clock.instant();
        Object savepoint = status.createSavepoint();
        velocity.ensureDailyBucket(attempt.customerId(), day, now);
        velocity.ensureWeeklyBucket(attempt.customerId(), week, now);

        DecisionReason reason;
        if (!velocity.tryIncrementDaily(attempt.customerId(), day, attempt.amount(), policy, now)) {
            reason = dailyDeclineReason(attempt, day);
        } else if (!velocity.tryIncrementWeekly(attempt.customerId(), week, attempt.amount(), policy, now)) {
            reason = DecisionReason.WEEKLY_AMOUNT_LIMIT_EXCEEDED;
        } else {
            reason = DecisionReason.ACCEPTED;
        }

        if (reason != DecisionReason.ACCEPTED) {
            status.rollbackToSavepoint(savepoint);
        }
        status.releaseSavepoint(savepoint);

        var decision = new LoadDecision(reason);
        try {
            loads.insertDecision(attempt, decision, now);
        } catch (DuplicateKeyException failure) {
            // Only uniqueness failure from the final result insert is a duplicate.
            throw new DuplicateResultInsert(failure);
        }
        return new LoadOutcome.Completed(new StoredLoadResult(attempt, decision, now));
    }

    private DecisionReason dailyDeclineReason(LoadAttempt attempt, LocalDate day) {
        var bucket = velocity.findDailyBucket(attempt.customerId(), day)
                .orElseThrow(() -> new IllegalStateException("Daily bucket missing after failed increment"));
        // This post-update snapshot may include newer commits. Amount wins if both
        // rules fail; the snapshot never authorizes an increment. Avoid sum overflow.
        if (bucket.acceptedAmountCents() > policy.dailyAmountLimitCents() - attempt.amount().cents()) {
            return DecisionReason.DAILY_AMOUNT_LIMIT_EXCEEDED;
        }
        if (bucket.acceptedCount() >= policy.dailyCountLimit()) {
            return DecisionReason.DAILY_COUNT_LIMIT_EXCEEDED;
        }
        throw new IllegalStateException("Failed daily increment has no violated limit in the bucket snapshot");
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

    private static final class DuplicateResultInsert extends RuntimeException {
        private final DuplicateKeyException failure;

        private DuplicateResultInsert(DuplicateKeyException failure) {
            super(failure);
            this.failure = failure;
        }
    }
}
