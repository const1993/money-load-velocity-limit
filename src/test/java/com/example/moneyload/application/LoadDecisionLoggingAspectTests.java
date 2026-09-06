package com.example.moneyload.application;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.moneyload.application.port.LoadResultRepository;
import com.example.moneyload.application.port.LoadResultRepository.StoredLoadResult;
import com.example.moneyload.application.port.VelocityRepository;
import com.example.moneyload.application.port.VelocityRepository.DailyBucket;
import com.example.moneyload.domain.*;
import com.example.moneyload.observability.LoadDecisionLoggingAspect;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LoadDecisionLoggingAspectTests {
    private static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");
    private static final LoadAttempt ATTEMPT = new LoadAttempt("load", "customer", new Money(100), NOW);
    // Capture the production logger to assert its events.
    @SuppressWarnings("LoggerInitializedWithForeignClass")
    private final Logger logger = (Logger) LoggerFactory.getLogger(LoadDecisionLoggingAspect.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;
    private AnnotationConfigApplicationContext context;
    private LoadResultRepository loads;
    private VelocityRepository velocity;
    private PlatformTransactionManager manager;
    private TransactionStatus status;
    private LoadFundsService service;

    @BeforeEach
    void setUp() {
        loads = mock(LoadResultRepository.class);
        velocity = mock(VelocityRepository.class);
        manager = mock(PlatformTransactionManager.class);
        status = mock(TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(status);
        when(status.createSavepoint()).thenReturn(new Object());
        when(velocity.tryIncrementDaily(anyString(), any(), any(), any(), any())).thenReturn(true);
        when(velocity.tryIncrementWeekly(anyString(), any(), any(), any(), any())).thenReturn(true);
        context = LoadServiceTestContext.create(loads, velocity, new VelocityPolicy(500_000, 2_000_000, 3),
                Clock.fixed(NOW, ZoneOffset.UTC), manager);
        service = context.getBean(LoadFundsService.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void close() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
        appender.stop();
        context.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPTED", "DECLINED", "DUPLICATE"})
    void logsStructuredOutcomeOnlyAfterCommit(String decision) {
        DecisionReason reason = DecisionReason.ACCEPTED;
        if (decision.equals("DECLINED")) {
            reason = DecisionReason.DAILY_COUNT_LIMIT_EXCEEDED;
            when(velocity.tryIncrementDaily(anyString(), any(), any(), any(), any())).thenReturn(false);
            when(velocity.findDailyBucket(anyString(), any())).thenReturn(Optional.of(new DailyBucket(0, 3, NOW)));
        } else if (decision.equals("DUPLICATE")) {
            reason = DecisionReason.WEEKLY_AMOUNT_LIMIT_EXCEEDED;
            when(loads.findResult("customer", "load")).thenReturn(Optional.of(
                    new StoredLoadResult(ATTEMPT, new LoadDecision(reason), NOW)));
        }
        doAnswer(_ -> {
            assertThat(appender.list).isEmpty();
            return null;
        }).when(manager).commit(status);

        service.process(ATTEMPT);

        verify(manager).commit(status);
        assertThat(appender.list).hasSize(1);
        var event = appender.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
        var fields = event.getKeyValuePairs().stream().collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
        assertThat(fields).containsEntry("event", "load_decision")
                .containsEntry("load_id", "load").containsEntry("customer_id", "customer")
                .containsEntry("amount_cents", 100L).containsEntry("decision", decision)
                .containsEntry("decision_reason", reason)
                .containsEntry("daily_bucket", LocalDate.of(2026, 9, 5))
                .containsEntry("weekly_bucket", LocalDate.of(2026, 8, 31));
    }

    @ParameterizedTest
    @ValueSource(strings = {"repository", "commit"})
    void technicalFailureDoesNotProduceDecisionLog(String stage) {
        var failure = new TransactionSystemException("Technical failure");
        if (stage.equals("commit")) {
            doThrow(failure).when(manager).commit(status);
        } else {
            when(velocity.tryIncrementDaily(anyString(), any(), any(), any(), any())).thenThrow(failure);
        }
        assertThatThrownBy(() -> service.process(ATTEMPT)).isSameAs(failure);
        assertThat(appender.list).isEmpty();
    }
}
