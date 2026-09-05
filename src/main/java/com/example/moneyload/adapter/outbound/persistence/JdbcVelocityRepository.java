package com.example.moneyload.adapter.outbound.persistence;

import com.example.moneyload.application.port.VelocityRepository;
import com.example.moneyload.domain.Money;
import com.example.moneyload.domain.VelocityPolicy;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcVelocityRepository implements VelocityRepository {
    private final JdbcClient jdbc;

    public JdbcVelocityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void ensureDailyBucket(String customerId, LocalDate dateUtc, Instant now) {
        try {
            jdbc.sql("""
                    INSERT INTO daily_velocity
                        (customer_id, date_utc, accepted_amount_cents, accepted_count, updated_at)
                    VALUES (:customer, :date, 0, 0, :now)
                    """).param("customer", customerId).param("date", dateUtc)
                    .param("now", now.atOffset(ZoneOffset.UTC)).update();
        } catch (DuplicateKeyException alreadyExists) {
            // H2 keeps the caller's transaction usable after this statement fails.
            // Preserve the existing counters and processing timestamp.
        }
    }

    @Override
    public void ensureWeeklyBucket(String customerId, LocalDate weekStartUtc, Instant now) {
        try {
            jdbc.sql("""
                    INSERT INTO weekly_velocity
                        (customer_id, week_start_utc, accepted_amount_cents, updated_at)
                    VALUES (:customer, :date, 0, :now)
                    """).param("customer", customerId).param("date", weekStartUtc)
                    .param("now", now.atOffset(ZoneOffset.UTC)).update();
        } catch (DuplicateKeyException alreadyExists) {
            // H2-specific duplicate handling; never reset an existing bucket.
        }
    }

    @Override
    public boolean tryIncrementDaily(String customerId, LocalDate dateUtc, Money amount,
                                     VelocityPolicy policy, Instant now) {
        // Subtraction avoids overflow when testing a sum near Long.MAX_VALUE.
        return jdbc.sql("""
                UPDATE daily_velocity
                SET accepted_amount_cents = accepted_amount_cents + :amount,
                    accepted_count = accepted_count + 1, updated_at = :now
                WHERE customer_id = :customer AND date_utc = :date
                    AND accepted_amount_cents <= :amountLimit - :amount
                    AND accepted_count < :countLimit
                """).param("amount", amount.cents()).param("amountLimit", policy.dailyAmountLimitCents())
                .param("countLimit", policy.dailyCountLimit()).param("customer", customerId)
                .param("date", dateUtc).param("now", now.atOffset(ZoneOffset.UTC)).update() == 1;
    }

    @Override
    public boolean tryIncrementWeekly(String customerId, LocalDate weekStartUtc, Money amount,
                                      VelocityPolicy policy, Instant now) {
        return jdbc.sql("""
                UPDATE weekly_velocity
                SET accepted_amount_cents = accepted_amount_cents + :amount, updated_at = :now
                WHERE customer_id = :customer AND week_start_utc = :date
                    AND accepted_amount_cents <= :amountLimit - :amount
                """).param("amount", amount.cents()).param("amountLimit", policy.weeklyAmountLimitCents())
                .param("customer", customerId).param("date", weekStartUtc)
                .param("now", now.atOffset(ZoneOffset.UTC)).update() == 1;
    }

    @Override
    public Optional<DailyBucket> findDailyBucket(String customerId, LocalDate dateUtc) {
        return jdbc.sql("""
                SELECT accepted_amount_cents, accepted_count, updated_at
                FROM daily_velocity WHERE customer_id = :customer AND date_utc = :date
                """).param("customer", customerId).param("date", dateUtc)
                .query((rs, rowNum) -> new DailyBucket(rs.getLong("accepted_amount_cents"),
                        rs.getInt("accepted_count"), rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    @Override
    public Optional<WeeklyBucket> findWeeklyBucket(String customerId, LocalDate weekStartUtc) {
        return jdbc.sql("""
                SELECT accepted_amount_cents, updated_at
                FROM weekly_velocity WHERE customer_id = :customer AND week_start_utc = :date
                """).param("customer", customerId).param("date", weekStartUtc)
                .query((rs, rowNum) -> new WeeklyBucket(rs.getLong("accepted_amount_cents"),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .optional();
    }
}
