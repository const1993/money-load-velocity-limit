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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/** Called through a Spring proxy so completion happens before returning to the service. */
@Component
public class LoadFundsTransaction {
    private final LoadResultRepository loads;
    private final VelocityRepository velocity;
    private final VelocityPolicy policy;
    private final Clock clock;

    public LoadFundsTransaction(LoadResultRepository loads, VelocityRepository velocity,
                                VelocityPolicy policy, Clock clock) {
        this.loads = loads;
        this.velocity = velocity;
        this.policy = policy;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public LoadOutcome process(LoadAttempt attempt) {
        var existing = loads.findResult(attempt.customerId(), attempt.loadId());
        if (existing.isPresent()) {
            return new LoadOutcome.Duplicate(existing.get());
        }

        var day = VelocityBuckets.daily(attempt.eventTimestamp());
        var week = VelocityBuckets.weekly(attempt.eventTimestamp());
        var status = TransactionAspectSupport.currentTransactionStatus();
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

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = true)
    public LoadOutcome.Duplicate recoverDuplicate(LoadAttempt attempt, DuplicateKeyException failure) {
        return new LoadOutcome.Duplicate(loads.findResult(attempt.customerId(), attempt.loadId())
                .orElseThrow(() -> failure));
    }

    static final class DuplicateResultInsert extends RuntimeException {
        private final DuplicateKeyException failure;

        private DuplicateResultInsert(DuplicateKeyException failure) {
            super(failure);
            this.failure = failure;
        }

        DuplicateKeyException failure() {
            return failure;
        }
    }
}
