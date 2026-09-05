package com.example.moneyload.application;

import com.example.moneyload.adapter.outbound.persistence.JdbcLoadResultRepository;
import com.example.moneyload.adapter.outbound.persistence.JdbcVelocityRepository;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.sql.SQLRecoverableException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LoadFundsServiceIntegrationTests {
    private static final Instant BEFORE = Instant.parse("2026-09-05T10:00:00.123456789Z");
    private static final Instant NOW = BEFORE.plusSeconds(60);
    private static final LocalDate DAY = LocalDate.of(2026, 9, 5);
    private static final LocalDate WEEK = LocalDate.of(2026, 8, 31);
    private static final VelocityPolicy POLICY = new VelocityPolicy(500_000, 2_000_000, 3);
    private static final LoadAttempt ATTEMPT = new LoadAttempt("load", "customer", new Money(100_000), BEFORE);

    private final List<AnnotationConfigApplicationContext> contexts = new ArrayList<>();
    private EmbeddedDatabase database;
    private LoadResultRepository loads;
    private VelocityRepository velocity;
    private LoadFundsService service;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        Flyway.configure().dataSource(database).load().migrate();
        var jdbc = JdbcClient.create(database);
        loads = new JdbcLoadResultRepository(jdbc);
        velocity = new JdbcVelocityRepository(jdbc);
        service = serviceUsing(loads);
    }

    @AfterEach
    void closeDatabase() {
        contexts.forEach(AnnotationConfigApplicationContext::close);
        if (database != null) {
            database.shutdown();
        }
    }

    @Test
    void weeklyDeclineRestoresDailyAmountCountAndTimestampAndPersistsDecision() {
        seedBuckets(400_000, 1, 1_950_000);
        assertDecision(service.process(ATTEMPT), DecisionReason.WEEKLY_AMOUNT_LIMIT_EXCEEDED);
        assertBuckets(400_000, 1, 1_950_000);
        assertStoredDecision(DecisionReason.WEEKLY_AMOUNT_LIMIT_EXCEEDED);
    }

    @ParameterizedTest
    @CsvSource({"450000,1,DAILY_AMOUNT_LIMIT_EXCEEDED", "300,3,DAILY_COUNT_LIMIT_EXCEEDED",
            "450000,3,DAILY_AMOUNT_LIMIT_EXCEEDED"})
    void dailyDeclinesLeaveBothBucketsExactlyUnchanged(long amount, int count, DecisionReason reason) {
        seedBuckets(amount, count, amount);
        assertDecision(service.process(ATTEMPT), reason);
        assertBuckets(amount, count, amount);
        assertStoredDecision(reason);
    }

    @Test
    void dailyDeclineRemovesNewEmptyBuckets() {
        var oversized = new LoadAttempt("oversized", "customer", new Money(Long.MAX_VALUE), BEFORE);
        assertDecision(service.process(oversized), DecisionReason.DAILY_AMOUNT_LIMIT_EXCEEDED);
        assertThat(velocity.findDailyBucket("customer", DAY)).isEmpty();
        assertThat(velocity.findWeeklyBucket("customer", WEEK)).isEmpty();
        assertThat(loads.findResult("customer", "oversized")).isPresent();
    }

    @Test
    void weeklyDeclineRemovesNewDailyBucketAndPreservesExistingWeeklyBucket() {
        velocity.ensureWeeklyBucket("customer", WEEK, BEFORE);
        assertThat(velocity.tryIncrementWeekly("customer", WEEK, new Money(1_950_000), POLICY, BEFORE)).isTrue();
        assertDecision(service.process(ATTEMPT), DecisionReason.WEEKLY_AMOUNT_LIMIT_EXCEEDED);
        assertThat(velocity.findDailyBucket("customer", DAY)).isEmpty();
        assertThat(velocity.findWeeklyBucket("customer", WEEK)).contains(new WeeklyBucket(1_950_000, BEFORE));
        assertStoredDecision(DecisionReason.WEEKLY_AMOUNT_LIMIT_EXCEEDED);
    }

    @Test
    void acceptanceCommitsBothBucketsAndResultAtExactAmountLimits() {
        seedBuckets(400_000, 2, 1_900_000);
        var outcome = service.process(ATTEMPT);
        assertDecision(outcome, DecisionReason.ACCEPTED);
        assertThat(((LoadOutcome.Completed) outcome).accepted()).isTrue();
        assertThat(velocity.findDailyBucket("customer", DAY)).contains(new DailyBucket(500_000, 3, NOW));
        assertThat(velocity.findWeeklyBucket("customer", WEEK)).contains(new WeeklyBucket(2_000_000, NOW));
        assertStoredDecision(DecisionReason.ACCEPTED);
    }

    @Test
    void acceptedAndDeclinedDuplicatesDoNotChangeVelocityEvenWhenPayloadChanges() {
        assertDecision(service.process(ATTEMPT), DecisionReason.ACCEPTED);
        var daily = velocity.findDailyBucket("customer", DAY);
        var weekly = velocity.findWeeklyBucket("customer", WEEK);
        var changed = new LoadAttempt("load", "customer", new Money(Long.MAX_VALUE), BEFORE.plusSeconds(86400));
        assertThat(service.process(changed)).isEqualTo(new LoadOutcome.Duplicate(
                loads.findResult("customer", "load").orElseThrow()));
        var declined = new LoadAttempt("declined", "customer", new Money(Long.MAX_VALUE), BEFORE);
        assertDecision(service.process(declined), DecisionReason.DAILY_AMOUNT_LIMIT_EXCEEDED);
        assertThat(service.process(declined)).isInstanceOf(LoadOutcome.Duplicate.class);
        assertThat(velocity.findDailyBucket("customer", DAY)).isEqualTo(daily);
        assertThat(velocity.findWeeklyBucket("customer", WEEK)).isEqualTo(weekly);
        assertThat(velocity.findDailyBucket("customer", DAY.plusDays(1))).isEmpty();
    }

    @Test
    void sameLoadIdForDifferentCustomersIsAcceptedIndependently() {
        assertDecision(service.process(ATTEMPT), DecisionReason.ACCEPTED);
        var other = new LoadAttempt("load", "other", ATTEMPT.amount(), BEFORE);
        assertDecision(service.process(other), DecisionReason.ACCEPTED);
        assertThat(loads.findResult("other", "load")).isPresent();
        assertThat(velocity.findDailyBucket("other", DAY)).contains(new DailyBucket(100_000, 1, NOW));
    }

    @Test
    void technicalFailureAfterResultInsertRollsBackResultAndBothIncrements() {
        seedBuckets(100_000, 1, 100_000);
        var failing = spy(loads);
        var failure = new DataAccessResourceFailureException("Failure after insert");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw failure;
        }).when(failing).insertDecision(any(), any(), any());
        assertThatThrownBy(() -> serviceUsing(failing).process(ATTEMPT)).isSameAs(failure);
        assertBuckets(100_000, 1, 100_000);
        assertThat(loads.findResult("customer", "load")).isEmpty();
    }

    @Test
    void lateDuplicateInsertRollsBackVelocityBeforeReturningOriginal() {
        seedBuckets(100_000, 1, 100_000);
        loads.insertDecision(ATTEMPT, new LoadDecision(DecisionReason.ACCEPTED), BEFORE);
        var original = loads.findResult("customer", "load").orElseThrow();
        var staleLookup = spy(loads);
        // Deterministically exercise the uniqueness path without threads or a race test.
        doReturn(Optional.empty(), Optional.of(original)).when(staleLookup).findResult("customer", "load");
        assertThat(serviceUsing(staleLookup).process(ATTEMPT)).isEqualTo(new LoadOutcome.Duplicate(original));
        assertBuckets(100_000, 1, 100_000);
        assertThat(loads.findResult("customer", "load")).contains(original);
    }

    private LoadFundsService serviceUsing(LoadResultRepository repository) {
        return serviceUsing(repository, new DataSourceTransactionManager(database));
    }

    @Test
    void failedCallRollsBackAndExplicitResubmissionUsesFreshTransaction() {
        var repository = spy(loads);
        var resources = new ArrayList<Object>();
        doAnswer(invocation -> {
            resources.add(TransactionSynchronizationManager.getResource(database));
            // At the start of either attempt, the failed insert and increments must be absent.
            assertThat(loads.findResult("customer", "load")).isEmpty();
            assertThat(velocity.findDailyBucket("customer", DAY)).isEmpty();
            assertThat(velocity.findWeeklyBucket("customer", WEEK)).isEmpty();
            return invocation.callRealMethod();
        }).when(repository).findResult("customer", "load");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new TransientDataAccessResourceException("Failure after insert");
        }).doCallRealMethod().when(repository).insertDecision(any(), any(), any());
        var processor = serviceUsing(repository);
        var outer = new TransactionTemplate(new DataSourceTransactionManager(database));
        outer.executeWithoutResult(status -> {
            Object outerResource = TransactionSynchronizationManager.getResource(database);
            assertThatThrownBy(() -> processor.process(ATTEMPT))
                    .isInstanceOf(TransientDataAccessResourceException.class);
            assertThat(resources).hasSize(1);
            assertThat(TransactionSynchronizationManager.getResource(database)).isSameAs(outerResource);
            assertDecision(processor.process(ATTEMPT), DecisionReason.ACCEPTED);
            assertThat(resources).hasSize(2);
            assertThat(resources.get(0)).isNotSameAs(resources.get(1)).isNotSameAs(outerResource);
            assertThat(resources.get(1)).isNotSameAs(outerResource);
            status.setRollbackOnly();
        });
        assertThat(velocity.findDailyBucket("customer", DAY)).contains(new DailyBucket(100_000, 1, NOW));
        assertThat(velocity.findWeeklyBucket("customer", WEEK)).contains(new WeeklyBucket(100_000, NOW));
        assertStoredDecision(DecisionReason.ACCEPTED);
        verify(repository, times(2)).insertDecision(any(), any(), any());
    }

    @ParameterizedTest
    @CsvSource({"100000,ACCEPTED", "600000,DAILY_AMOUNT_LIMIT_EXCEEDED"})
    void uncertainCommitFailsImmediatelyAndExplicitResubmissionRecoversStoredDecision(long cents, DecisionReason reason) {
        var repository = spy(loads);
        var manager = new DataSourceTransactionManager(database) {
            private boolean firstCommit = true;

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
                super.doCommit(status);
                if (firstCommit) {
                    firstCommit = false;
                    throw new TransactionSystemException("Commit acknowledgement lost", new SQLRecoverableException());
                }
            }
        };
        var processor = serviceUsing(repository, manager);
        var load = new LoadAttempt("load", "customer", new Money(cents), BEFORE);
        assertThatThrownBy(() -> processor.process(load)).isInstanceOf(TransactionSystemException.class);
        verify(repository, times(1)).findResult("customer", "load");
        var recovered = (LoadOutcome.Duplicate) processor.process(load);
        assertThat(recovered.originalResult().decision().reason()).isEqualTo(reason);
        verify(repository, times(2)).findResult("customer", "load");
        verify(repository, times(1)).insertDecision(any(), any(), any());
        if (reason == DecisionReason.ACCEPTED) {
            assertThat(velocity.findDailyBucket("customer", DAY)).contains(new DailyBucket(cents, 1, NOW));
            assertThat(velocity.findWeeklyBucket("customer", WEEK)).contains(new WeeklyBucket(cents, NOW));
        } else {
            assertThat(velocity.findDailyBucket("customer", DAY)).isEmpty();
            assertThat(velocity.findWeeklyBucket("customer", WEEK)).isEmpty();
        }
        // A separate input call still exposes a genuine duplicate to future adapters.
        assertThat(processor.process(load)).isInstanceOf(LoadOutcome.Duplicate.class);
    }

    private LoadFundsService serviceUsing(LoadResultRepository repository, DataSourceTransactionManager manager) {
        var context = LoadServiceTestContext.create(repository, velocity, POLICY, Clock.fixed(NOW, ZoneOffset.UTC),
                manager);
        contexts.add(context);
        return context.getBean(LoadFundsService.class);
    }

    private void seedBuckets(long dailyAmount, int count, long weeklyAmount) {
        velocity.ensureDailyBucket("customer", DAY, BEFORE);
        velocity.ensureWeeklyBucket("customer", WEEK, BEFORE);
        for (int i = 0; i < count; i++) {
            assertThat(velocity.tryIncrementDaily("customer", DAY, new Money(i == 0 ? dailyAmount : 0), POLICY, BEFORE))
                    .isTrue();
        }
        assertThat(velocity.tryIncrementWeekly("customer", WEEK, new Money(weeklyAmount), POLICY, BEFORE)).isTrue();
    }

    private void assertBuckets(long amount, int count, long weeklyAmount) {
        assertThat(velocity.findDailyBucket("customer", DAY)).contains(new DailyBucket(amount, count, BEFORE));
        assertThat(velocity.findWeeklyBucket("customer", WEEK)).contains(new WeeklyBucket(weeklyAmount, BEFORE));
    }

    private void assertStoredDecision(DecisionReason reason) {
        assertThat(loads.findResult("customer", "load"))
                .contains(new StoredLoadResult(ATTEMPT, new LoadDecision(reason), NOW));
    }

    private void assertDecision(LoadOutcome outcome, DecisionReason reason) {
        assertThat(outcome).isInstanceOfSatisfying(LoadOutcome.Completed.class,
                completed -> assertThat(completed.result().decision().reason()).isEqualTo(reason));
    }
}
