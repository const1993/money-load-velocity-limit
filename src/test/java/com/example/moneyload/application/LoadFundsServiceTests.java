package com.example.moneyload.application;

import com.example.moneyload.application.port.LoadResultRepository;
import com.example.moneyload.application.port.LoadResultRepository.StoredLoadResult;
import com.example.moneyload.application.port.VelocityRepository;
import com.example.moneyload.application.port.VelocityRepository.DailyBucket;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LoadFundsServiceTests {
    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 6);
    private static final LocalDate WEEK = LocalDate.of(2026, 8, 31);
    private static final VelocityPolicy POLICY = new VelocityPolicy(500_000, 2_000_000, 3);
    private static final LoadAttempt ATTEMPT = new LoadAttempt("load", "customer", new Money(100_000),
            Instant.parse("2026-09-07T01:30:00+03:00"));

    private LoadResultRepository loads;
    private VelocityRepository velocity;
    private PlatformTransactionManager manager;
    private TransactionStatus status;
    private Object savepoint;
    private LoadFundsService service;
    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void setUp() {
        loads = mock(LoadResultRepository.class);
        velocity = mock(VelocityRepository.class);
        manager = mock(PlatformTransactionManager.class);
        status = mock(TransactionStatus.class);
        savepoint = new Object();
        when(manager.getTransaction(any())).thenReturn(status);
        when(status.createSavepoint()).thenReturn(savepoint);
        context = LoadServiceTestContext.create(loads, velocity, POLICY, Clock.fixed(NOW, ZoneOffset.UTC), manager);
        service = context.getBean(LoadFundsService.class);
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    @Test
    void acceptsAndCommitsInDailyThenWeeklyOrderUsingEventUtcBuckets() {
        passesBoth();
        assertThat(service.process(ATTEMPT)).isEqualTo(completed(DecisionReason.ACCEPTED));

        var order = inOrder(manager, loads, status, velocity);
        order.verify(manager).getTransaction(argThat(def ->
                def != null && def.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW
                        && def.getIsolationLevel() == TransactionDefinition.ISOLATION_READ_COMMITTED));
        order.verify(loads).findResult("customer", "load");
        order.verify(status).createSavepoint();
        order.verify(velocity).ensureDailyBucket("customer", DAY, NOW);
        order.verify(velocity).ensureWeeklyBucket("customer", WEEK, NOW);
        order.verify(velocity).tryIncrementDaily("customer", DAY, ATTEMPT.amount(), POLICY, NOW);
        order.verify(velocity).tryIncrementWeekly("customer", WEEK, ATTEMPT.amount(), POLICY, NOW);
        order.verify(status).releaseSavepoint(savepoint);
        order.verify(loads).insertDecision(ATTEMPT, new LoadDecision(DecisionReason.ACCEPTED), NOW);
        order.verify(manager).commit(status);
        verify(status, never()).rollbackToSavepoint(any());
        verify(velocity, never()).findDailyBucket(anyString(), any());
    }

    @ParameterizedTest
    @CsvSource({"450000,1,DAILY_AMOUNT_LIMIT_EXCEEDED", "0,3,DAILY_COUNT_LIMIT_EXCEEDED",
            "450000,3,DAILY_AMOUNT_LIMIT_EXCEEDED"})
    void dailyFailureResolvesReasonAfterUpdateWithAmountPrecedence(long amount, int count, DecisionReason reason) {
        when(velocity.findDailyBucket("customer", DAY)).thenReturn(Optional.of(new DailyBucket(amount, count, NOW)));
        assertThat(service.process(ATTEMPT)).isEqualTo(completed(reason));
        var order = inOrder(velocity, status, loads, manager);
        order.verify(velocity).tryIncrementDaily("customer", DAY, ATTEMPT.amount(), POLICY, NOW);
        order.verify(velocity).findDailyBucket("customer", DAY);
        order.verify(status).rollbackToSavepoint(savepoint);
        order.verify(status).releaseSavepoint(savepoint);
        order.verify(loads).insertDecision(ATTEMPT, new LoadDecision(reason), NOW);
        order.verify(manager).commit(status);
        verify(velocity, never()).tryIncrementWeekly(anyString(), any(), any(), any(), any());
    }

    @Test
    void weeklyDeclineRollsBackSavepointBeforePersistingDecision() {
        when(velocity.tryIncrementDaily("customer", DAY, ATTEMPT.amount(), POLICY, NOW)).thenReturn(true);
        assertThat(service.process(ATTEMPT)).isEqualTo(completed(DecisionReason.WEEKLY_AMOUNT_LIMIT_EXCEEDED));
        var order = inOrder(velocity, status, loads, manager);
        order.verify(velocity).tryIncrementDaily("customer", DAY, ATTEMPT.amount(), POLICY, NOW);
        order.verify(velocity).tryIncrementWeekly("customer", WEEK, ATTEMPT.amount(), POLICY, NOW);
        order.verify(status).rollbackToSavepoint(savepoint);
        order.verify(status).releaseSavepoint(savepoint);
        order.verify(loads).insertDecision(ATTEMPT, new LoadDecision(DecisionReason.WEEKLY_AMOUNT_LIMIT_EXCEEDED), NOW);
        order.verify(manager).commit(status);
    }

    @Test
    void duplicateReturnsOriginalResultWithoutTouchingVelocity() {
        var original = new StoredLoadResult(ATTEMPT, new LoadDecision(DecisionReason.DAILY_COUNT_LIMIT_EXCEEDED), NOW);
        when(loads.findResult("customer", "load")).thenReturn(Optional.of(original));
        assertThat(service.process(ATTEMPT)).isEqualTo(new LoadOutcome.Duplicate(original));
        verifyNoInteractions(velocity);
        verify(status, never()).createSavepoint();
        verify(loads, never()).insertDecision(any(), any(), any());
        verify(manager).commit(status);
    }

    @Test
    void duplicateLookupIncludesCustomerId() {
        var original = new StoredLoadResult(ATTEMPT, new LoadDecision(DecisionReason.ACCEPTED), NOW);
        when(loads.findResult("customer", "load")).thenReturn(Optional.of(original));
        var other = new LoadAttempt("load", "another-customer", ATTEMPT.amount(), ATTEMPT.eventTimestamp());
        when(velocity.tryIncrementDaily("another-customer", DAY, other.amount(), POLICY, NOW)).thenReturn(true);
        when(velocity.tryIncrementWeekly("another-customer", WEEK, other.amount(), POLICY, NOW)).thenReturn(true);
        assertThat(service.process(ATTEMPT)).isInstanceOf(LoadOutcome.Duplicate.class);
        assertThat(service.process(other)).isEqualTo(new LoadOutcome.Completed(
                new StoredLoadResult(other, new LoadDecision(DecisionReason.ACCEPTED), NOW)));
        verify(loads).findResult("another-customer", "load");
    }

    @Test
    void repositoryFailurePropagatesAndRollsBackWholeTransaction() {
        var failure = new DataAccessResourceFailureException("Database unavailable");
        when(velocity.tryIncrementDaily("customer", DAY, ATTEMPT.amount(), POLICY, NOW)).thenReturn(true);
        when(velocity.tryIncrementWeekly("customer", WEEK, ATTEMPT.amount(), POLICY, NOW)).thenThrow(failure);
        assertThatThrownBy(() -> service.process(ATTEMPT)).isSameAs(failure);
        verify(manager).rollback(status);
        verify(manager, never()).commit(any());
        verify(loads, never()).insertDecision(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "rollback", "release"})
    void savepointFailuresPropagateAndPreventDecisionPersistence(String operation) {
        var failure = new TransactionSystemException("Savepoint failure");
        when(velocity.findDailyBucket("customer", DAY)).thenReturn(Optional.of(new DailyBucket(500_000, 1, NOW)));
        switch (operation) {
            case "create" -> when(status.createSavepoint()).thenThrow(failure);
            case "rollback" -> doThrow(failure).when(status).rollbackToSavepoint(savepoint);
            case "release" -> doThrow(failure).when(status).releaseSavepoint(savepoint);
            default -> throw new AssertionError(operation);
        }
        assertThatThrownBy(() -> service.process(ATTEMPT)).isSameAs(failure);
        verify(manager).rollback(status);
        verify(loads, never()).insertDecision(any(), any(), any());
    }

    @Test
    void commitFailureIsNotReturnedAsBusinessOutcome() {
        passesBoth();
        var failure = new TransactionSystemException("Commit outcome unknown");
        doThrow(failure).when(manager).commit(status);
        assertThatThrownBy(() -> service.process(ATTEMPT)).isSameAs(failure);
        verify(loads, times(1)).findResult("customer", "load");
    }

    @Test
    void finalInsertCollisionRollsBackBeforeReadingCommittedOriginal() {
        passesBoth();
        var original = new StoredLoadResult(ATTEMPT, new LoadDecision(DecisionReason.ACCEPTED), NOW.minusSeconds(1));
        when(loads.findResult("customer", "load")).thenReturn(Optional.empty()).thenReturn(Optional.of(original));
        doThrow(new DuplicateKeyException("Already committed")).when(loads).insertDecision(any(), any(), any());
        assertThat(service.process(ATTEMPT)).isEqualTo(new LoadOutcome.Duplicate(original));
        var order = inOrder(loads, manager);
        order.verify(loads).insertDecision(any(), any(), any());
        order.verify(manager).rollback(status);
        order.verify(manager).getTransaction(any());
        order.verify(loads).findResult("customer", "load");
        order.verify(manager).commit(status);
        verify(velocity, times(1)).tryIncrementDaily(anyString(), any(), any(), any(), any());
    }

    @Test
    void duplicateCollisionWithoutRecoverableResultPropagates() {
        passesBoth();
        var failure = new DuplicateKeyException("Missing winning result");
        doThrow(failure).when(loads).insertDecision(any(), any(), any());
        assertThatThrownBy(() -> service.process(ATTEMPT)).isSameAs(failure);
        verify(manager, times(2)).rollback(status);
    }

    @Test
    void inconsistentDailySnapshotIsTechnicalFailure() {
        when(velocity.findDailyBucket("customer", DAY)).thenReturn(Optional.of(new DailyBucket(0, 0, NOW)));
        assertThatIllegalStateException().isThrownBy(() -> service.process(ATTEMPT));
        verify(manager).rollback(status);
        verify(loads, never()).insertDecision(any(), any(), any());
    }

    private void passesBoth() {
        when(velocity.tryIncrementDaily("customer", DAY, ATTEMPT.amount(), POLICY, NOW)).thenReturn(true);
        when(velocity.tryIncrementWeekly("customer", WEEK, ATTEMPT.amount(), POLICY, NOW)).thenReturn(true);
    }

    private LoadOutcome.Completed completed(DecisionReason reason) {
        return new LoadOutcome.Completed(new StoredLoadResult(ATTEMPT, new LoadDecision(reason), NOW));
    }
}
