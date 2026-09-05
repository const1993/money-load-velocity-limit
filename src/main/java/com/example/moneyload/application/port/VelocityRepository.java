package com.example.moneyload.application.port;

import com.example.moneyload.domain.Money;
import com.example.moneyload.domain.VelocityPolicy;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

public interface VelocityRepository {
    void ensureDailyBucket(String customerId, LocalDate dateUtc, Instant now);

    void ensureWeeklyBucket(String customerId, LocalDate weekStartUtc, Instant now);

    /**
     * Requires an existing bucket. Returns false when no row satisfies the limits;
     * technical failures propagate. Participates in the caller's transaction.
     */
    boolean tryIncrementDaily(String customerId, LocalDate dateUtc, Money amount,
                              VelocityPolicy policy, Instant now);

    /** Same contract as the daily increment, for the weekly amount limit. */
    boolean tryIncrementWeekly(String customerId, LocalDate weekStartUtc, Money amount,
                               VelocityPolicy policy, Instant now);

    /**
     * A snapshot for later decline-reason resolution, not an authorization to increment.
     * Another transaction may change the bucket between a failed update and this read;
     * this does not identify the exact predicate that failed at update time.
     */
    Optional<DailyBucket> findDailyBucket(String customerId, LocalDate dateUtc);

    Optional<WeeklyBucket> findWeeklyBucket(String customerId, LocalDate weekStartUtc);

    record DailyBucket(long acceptedAmountCents, int acceptedCount, Instant updatedAt) {
    }

    record WeeklyBucket(long acceptedAmountCents, Instant updatedAt) {
    }
}
