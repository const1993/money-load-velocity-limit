package com.example.moneyload.adapter.outbound.persistence;

import com.example.moneyload.application.port.LoadResultRepository;
import com.example.moneyload.application.port.LoadResultRepository.StoredLoadResult;
import com.example.moneyload.application.port.VelocityRepository;
import com.example.moneyload.application.port.VelocityRepository.DailyBucket;
import com.example.moneyload.application.port.VelocityRepository.WeeklyBucket;
import com.example.moneyload.domain.DecisionReason;
import com.example.moneyload.domain.LoadAttempt;
import com.example.moneyload.domain.LoadDecision;
import com.example.moneyload.domain.Money;
import com.example.moneyload.domain.VelocityPolicy;
import java.time.Instant;
import java.time.LocalDate;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;

class JdbcRepositoriesTests {
    private static final Instant CREATED = Instant.parse("2026-09-05T12:00:00.123456789Z");
    private static final Instant UPDATED = CREATED.plusSeconds(1);
    private static final LocalDate DAY = LocalDate.of(2026, 9, 5);
    private static final LocalDate WEEK = LocalDate.of(2026, 8, 31);
    private static final VelocityPolicy POLICY = new VelocityPolicy(500_000, 2_000_000, 3);

    private EmbeddedDatabase database;
    private JdbcClient jdbc;
    private LoadResultRepository loads;
    private VelocityRepository velocity;

    @BeforeEach
    void initializeSchemaAndAdapters() {
        database = new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2).build();
        Flyway.configure().dataSource(database).load().migrate();
        jdbc = JdbcClient.create(database);
        loads = new JdbcLoadResultRepository(jdbc);
        velocity = new JdbcVelocityRepository(jdbc);
    }

    @AfterEach
    void closeDatabase() {
        if (database != null) {
            database.shutdown();
        }
    }

    @ParameterizedTest
    @EnumSource(DecisionReason.class)
    void roundTripsCompletedResults(DecisionReason reason) {
        var attempt = new LoadAttempt("load-id", "customer-id", new Money(12345), CREATED.minusSeconds(60));
        var decision = new LoadDecision(reason);
        assertThat(loads.findResult(attempt.customerId(), attempt.loadId())).isEmpty();

        loads.insertDecision(attempt, decision, CREATED);

        assertThat(loads.findResult(attempt.customerId(), attempt.loadId()))
                .contains(new StoredLoadResult(attempt, decision, CREATED));
        assertThat(loads.findResult(attempt.customerId(), attempt.loadId()).orElseThrow().decision().accepted())
                .isEqualTo(reason == DecisionReason.ACCEPTED);
    }

    @Test
    void duplicateResultDoesNotOverwriteOriginalAndLoadIdsAreScopedByCustomer() {
        var original = new LoadAttempt("same-load", "first", new Money(100), CREATED);
        var accepted = new LoadDecision(DecisionReason.ACCEPTED);
        loads.insertDecision(original, accepted, CREATED);
        assertThatExceptionOfType(DuplicateKeyException.class).isThrownBy(() -> loads.insertDecision(
                new LoadAttempt("same-load", "first", new Money(200), UPDATED),
                new LoadDecision(DecisionReason.DAILY_AMOUNT_LIMIT_EXCEEDED), UPDATED));
        var other = new LoadAttempt("same-load", "second", new Money(300), UPDATED);
        loads.insertDecision(other, accepted, UPDATED);

        assertThat(loads.findResult("first", "same-load")).contains(new StoredLoadResult(original, accepted, CREATED));
        assertThat(loads.findResult("second", "same-load")).contains(new StoredLoadResult(other, accepted, UPDATED));
    }

    @Test
    void dailyEnsurePreservesExistingStateAndTimestamp() {
        assertThat(velocity.findDailyBucket("customer", DAY)).isEmpty();
        velocity.ensureDailyBucket("customer", DAY, CREATED);
        assertThat(velocity.findDailyBucket("customer", DAY)).contains(new DailyBucket(0, 0, CREATED));
        assertThat(velocity.tryIncrementDaily("customer", DAY, new Money(100), POLICY, UPDATED)).isTrue();
        velocity.ensureDailyBucket("customer", DAY, UPDATED.plusSeconds(1));
        assertThat(velocity.findDailyBucket("customer", DAY)).contains(new DailyBucket(100, 1, UPDATED));
    }

    @Test
    void dailyAmountAcceptsExactLimitAndRejectsOneCentBeyondWithoutChangingState() {
        velocity.ensureDailyBucket("customer", DAY, CREATED);
        assertThat(velocity.tryIncrementDaily("customer", DAY, new Money(100), POLICY, UPDATED)).isTrue();
        assertThat(velocity.tryIncrementDaily("customer", DAY, new Money(499_900), POLICY, UPDATED)).isTrue();
        assertThat(velocity.tryIncrementDaily("customer", DAY, new Money(1), POLICY, UPDATED.plusSeconds(1))).isFalse();
        assertThat(velocity.findDailyBucket("customer", DAY)).contains(new DailyBucket(500_000, 2, UPDATED));
    }

    @Test
    void dailyCountAcceptsThirdLoadAndRejectsFourthWithoutChangingState() {
        velocity.ensureDailyBucket("customer", DAY, CREATED);
        for (int i = 0; i < 3; i++) {
            assertThat(velocity.tryIncrementDaily("customer", DAY, new Money(0), POLICY, UPDATED)).isTrue();
        }
        assertThat(velocity.tryIncrementDaily("customer", DAY, new Money(0), POLICY, UPDATED.plusSeconds(1))).isFalse();
        assertThat(velocity.findDailyBucket("customer", DAY)).contains(new DailyBucket(0, 3, UPDATED));
    }

    @Test
    void weeklyEnsurePreservesExistingStateAndTimestamp() {
        assertThat(velocity.findWeeklyBucket("customer", WEEK)).isEmpty();
        velocity.ensureWeeklyBucket("customer", WEEK, CREATED);
        assertThat(velocity.findWeeklyBucket("customer", WEEK)).contains(new WeeklyBucket(0, CREATED));
        assertThat(velocity.tryIncrementWeekly("customer", WEEK, new Money(100), POLICY, UPDATED)).isTrue();
        velocity.ensureWeeklyBucket("customer", WEEK, UPDATED.plusSeconds(1));
        assertThat(velocity.findWeeklyBucket("customer", WEEK)).contains(new WeeklyBucket(100, UPDATED));
    }

    @Test
    void weeklyAmountAcceptsExactLimitAndRejectsOneCentBeyondWithoutChangingState() {
        velocity.ensureWeeklyBucket("customer", WEEK, CREATED);
        assertThat(velocity.tryIncrementWeekly("customer", WEEK, new Money(500_000), POLICY, UPDATED)).isTrue();
        assertThat(velocity.tryIncrementWeekly("customer", WEEK, new Money(1_500_000), POLICY, UPDATED)).isTrue();
        assertThat(velocity.tryIncrementWeekly("customer", WEEK, new Money(1), POLICY, UPDATED.plusSeconds(1))).isFalse();
        assertThat(velocity.findWeeklyBucket("customer", WEEK)).contains(new WeeklyBucket(2_000_000, UPDATED));
    }

    @Test
    void oversizedRequestsAreRejectedWithoutArithmeticOverflow() {
        velocity.ensureDailyBucket("customer", DAY, CREATED);
        velocity.ensureWeeklyBucket("customer", WEEK, CREATED);
        assertThat(velocity.tryIncrementDaily("customer", DAY, new Money(Long.MAX_VALUE), POLICY, UPDATED)).isFalse();
        assertThat(velocity.tryIncrementWeekly("customer", WEEK, new Money(Long.MAX_VALUE), POLICY, UPDATED)).isFalse();
        assertThat(velocity.findDailyBucket("customer", DAY)).contains(new DailyBucket(0, 0, CREATED));
        assertThat(velocity.findWeeklyBucket("customer", WEEK)).contains(new WeeklyBucket(0, CREATED));
    }

    @Test
    void fullLongAndIntegerCountersRejectFurtherIncrementsWithoutOverflow() {
        var maximum = new VelocityPolicy(Long.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE);
        velocity.ensureDailyBucket("customer", DAY, CREATED);
        velocity.ensureWeeklyBucket("customer", WEEK, CREATED);
        assertThat(velocity.tryIncrementDaily("customer", DAY, new Money(Long.MAX_VALUE), maximum, UPDATED)).isTrue();
        assertThat(velocity.tryIncrementWeekly("customer", WEEK, new Money(Long.MAX_VALUE), maximum, UPDATED)).isTrue();
        assertThat(velocity.tryIncrementDaily("customer", DAY, new Money(1), maximum, CREATED)).isFalse();
        assertThat(velocity.tryIncrementWeekly("customer", WEEK, new Money(1), maximum, CREATED)).isFalse();
        jdbc.sql("UPDATE daily_velocity SET accepted_count = :count").param("count", Integer.MAX_VALUE).update();
        assertThat(velocity.tryIncrementDaily("customer", DAY, new Money(0), maximum, CREATED)).isFalse();
        assertThat(velocity.findDailyBucket("customer", DAY))
                .contains(new DailyBucket(Long.MAX_VALUE, Integer.MAX_VALUE, UPDATED));
        assertThat(velocity.findWeeklyBucket("customer", WEEK)).contains(new WeeklyBucket(Long.MAX_VALUE, UPDATED));
    }

    @Test
    void incrementsOnlyAffectTheRequestedCustomerAndBucket() {
        for (String customer : new String[]{"first", "second"}) {
            velocity.ensureDailyBucket(customer, DAY, CREATED);
            velocity.ensureDailyBucket(customer, DAY.plusDays(1), CREATED);
            velocity.ensureWeeklyBucket(customer, WEEK, CREATED);
            velocity.ensureWeeklyBucket(customer, WEEK.plusWeeks(1), CREATED);
        }
        assertThat(velocity.tryIncrementDaily("first", DAY, new Money(100), POLICY, UPDATED)).isTrue();
        assertThat(velocity.tryIncrementWeekly("first", WEEK, new Money(100), POLICY, UPDATED)).isTrue();
        assertThat(velocity.findDailyBucket("second", DAY)).contains(new DailyBucket(0, 0, CREATED));
        assertThat(velocity.findDailyBucket("first", DAY.plusDays(1))).contains(new DailyBucket(0, 0, CREATED));
        assertThat(velocity.findWeeklyBucket("second", WEEK)).contains(new WeeklyBucket(0, CREATED));
        assertThat(velocity.findWeeklyBucket("first", WEEK.plusWeeks(1))).contains(new WeeklyBucket(0, CREATED));
    }

    @Test
    void loadInsertParticipatesInCallerRollback() {
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(database));
        transaction.executeWithoutResult(status -> {
            loads.insertDecision(new LoadAttempt("load", "customer", new Money(100), CREATED),
                    new LoadDecision(DecisionReason.ACCEPTED), CREATED);
            status.setRollbackOnly();
        });
        assertThat(loads.findResult("customer", "load")).isEmpty();
    }

    @Test
    void repeatedEnsuresKeepH2TransactionUsableAndVelocityWorkRollsBack() {
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(database));
        transaction.executeWithoutResult(status -> {
            velocity.ensureDailyBucket("customer", DAY, CREATED);
            velocity.ensureDailyBucket("customer", DAY, UPDATED);
            velocity.ensureWeeklyBucket("customer", WEEK, CREATED);
            velocity.ensureWeeklyBucket("customer", WEEK, UPDATED);
            assertThat(velocity.tryIncrementDaily("customer", DAY, new Money(100), POLICY, UPDATED)).isTrue();
            assertThat(velocity.tryIncrementWeekly("customer", WEEK, new Money(100), POLICY, UPDATED)).isTrue();
            status.setRollbackOnly();
        });
        assertThat(velocity.findDailyBucket("customer", DAY)).isEmpty();
        assertThat(velocity.findWeeklyBucket("customer", WEEK)).isEmpty();
    }

    @Test
    void nonDuplicateIntegrityFailuresPropagate() {
        String tooLong = "x".repeat(256);
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> velocity.ensureDailyBucket(tooLong, DAY, CREATED));
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> velocity.ensureWeeklyBucket(tooLong, WEEK, CREATED));
        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() -> loads.insertDecision(
                new LoadAttempt("load", tooLong, new Money(100), CREATED),
                new LoadDecision(DecisionReason.ACCEPTED), CREATED));
    }

    @Test
    void brokenSchemaIsATechnicalFailureRatherThanALimitRejection() {
        jdbc.sql("DROP TABLE daily_velocity").update();
        jdbc.sql("DROP TABLE weekly_velocity").update();
        assertThatExceptionOfType(DataAccessException.class)
                .isThrownBy(() -> velocity.tryIncrementDaily("customer", DAY, new Money(1), POLICY, UPDATED));
        assertThatExceptionOfType(DataAccessException.class)
                .isThrownBy(() -> velocity.tryIncrementWeekly("customer", WEEK, new Money(1), POLICY, UPDATED));
    }
}
