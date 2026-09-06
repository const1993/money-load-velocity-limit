package com.example.moneyload.application;

import com.example.moneyload.adapter.outbound.persistence.JdbcLoadResultRepository;
import com.example.moneyload.adapter.outbound.persistence.JdbcVelocityRepository;
import com.example.moneyload.application.port.LoadResultRepository;
import com.example.moneyload.application.port.VelocityRepository;
import com.example.moneyload.domain.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.*;

/** Real H2 races; these invariants do not establish PostgreSQL locking/isolation behavior. */
@Timeout(30)
class LoadFundsConcurrencyTests {
    private static final Instant EVENT = Instant.parse("2026-09-05T12:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 5);
    private static final LocalDate WEEK = LocalDate.of(2026, 8, 31);
    private HikariDataSource database;
    private AnnotationConfigApplicationContext context;
    private JdbcClient jdbc;
    private LoadFundsService service;
    private LoadResultRepository loads;
    private VelocityRepository velocity;
    private TransactionGate gate;

    @BeforeEach
    void setUp() {
        var config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:race-" + UUID.randomUUID() + ";LOCK_TIMEOUT=5000;DB_CLOSE_ON_EXIT=FALSE");
        config.setUsername("sa");
        config.setMaximumPoolSize(12);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5000);
        database = new HikariDataSource(config);
        Flyway.configure().dataSource(database).load().migrate();
        jdbc = JdbcClient.create(database);
        loads = new JdbcLoadResultRepository(jdbc);
        velocity = new JdbcVelocityRepository(jdbc);
        gate = new TransactionGate(jdbc);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(LoadResultRepository.class, () -> loads);
        context.registerBean(VelocityRepository.class, () -> velocity);
        context.registerBean(VelocityPolicy.class, () -> new VelocityPolicy(500_000, 2_000_000, 3));
        context.registerBean(Clock.class, () -> Clock.fixed(EVENT, ZoneOffset.UTC));
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(database));
        context.registerBean(TransactionGate.class, () -> gate);
        context.register(LoadFundsService.class, LoadFundsTransaction.class);
        context.refresh();
        service = context.getBean(LoadFundsService.class);
        assertThat(AopUtils.isAopProxy(context.getBean(LoadFundsTransaction.class))).isTrue();
    }

    @AfterEach
    void close() {
        if (context != null) { context.close(); }
        if (database != null) { database.close(); }
    }

    @RepeatedTest(5)
    void dailyAmountBoundary() throws Exception {
        seed("a", 400_000, 1, 400_000);
        var results = race(List.of(load("a", "one", 100_000), load("a", "two", 100_000)));
        assertReasons(results, 1, DecisionReason.DAILY_AMOUNT_LIMIT_EXCEEDED);
        assertBuckets("a", 500_000, 2, 500_000);
        assertRecordCount(2);
    }

    @RepeatedTest(5)
    void dailyCountBoundary() throws Exception {
        seed("a", 200, 2, 200);
        var results = race(List.of(load("a", "one", 100), load("a", "two", 100)));
        assertReasons(results, 1, DecisionReason.DAILY_COUNT_LIMIT_EXCEEDED);
        assertBuckets("a", 300, 3, 300);
        assertRecordCount(2);
    }

    @RepeatedTest(5)
    void weeklyBoundaryRestoresDeclinedDailyIncrement() throws Exception {
        seed("a", 0, 0, 1_900_000);
        var results = race(List.of(load("a", "one", 100_000), load("a", "two", 100_000)));
        assertReasons(results, 1, DecisionReason.WEEKLY_AMOUNT_LIMIT_EXCEEDED);
        assertBuckets("a", 100_000, 1, 2_000_000);
        assertRecordCount(2);
    }

    @RepeatedTest(3)
    void duplicateRaceAppliesVelocityExactlyOnce() throws Exception {
        var load = load("a", "same", 10_000);
        var results = race(java.util.Collections.nCopies(12, load));
        for (var result : results) {
            assertThat(stored(result).decision().reason()).isEqualTo(DecisionReason.ACCEPTED);
        }
        assertBuckets("a", 10_000, 1, 10_000);
        assertRecordCount(1);
    }

    @RepeatedTest(3)
    void sameLoadIdForDifferentCustomersIsIndependent() throws Exception {
        var results = race(List.of(load("a", "123", 10_000), load("b", "123", 10_000)));
        assertReasons(results, 2, DecisionReason.DAILY_COUNT_LIMIT_EXCEEDED);
        assertBuckets("a", 10_000, 1, 10_000);
        assertBuckets("b", 10_000, 1, 10_000);
        assertRecordCount(2);
    }

    @RepeatedTest(3)
    void differentCustomersHaveIndependentState() throws Exception {
        var requests = IntStream.range(0, 8).mapToObj(i -> load("customer-" + i, "load", 100 + i)).toList();
        var results = race(requests);
        assertReasons(results, 8, DecisionReason.DAILY_COUNT_LIMIT_EXCEEDED);
        for (int i = 0; i < 8; i++) { assertBuckets("customer-" + i, 100 + i, 1, 100 + i); }
        assertRecordCount(8);
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4, 3, 4, 3, 4})
    void initialBucketCreationAndCountLimit(int count) throws Exception {
        var requests = IntStream.range(0, count).mapToObj(i -> load("a", "load-" + i, 10_000)).toList();
        var results = race(requests);
        assertReasons(results, 3, DecisionReason.DAILY_COUNT_LIMIT_EXCEEDED);
        assertBuckets("a", 30_000, 3, 30_000);
        assertRecordCount(count);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM daily_velocity").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM weekly_velocity").query(Long.class).single()).isEqualTo(1);
    }

    private List<LoadOutcome> race(List<LoadAttempt> requests) throws Exception {
        var barrier = new CyclicBarrier(requests.size());
        var executor = Executors.newFixedThreadPool(requests.size());
        var futures = new ArrayList<java.util.concurrent.Future<LoadOutcome>>();
        try {
            for (var request : requests) {
                futures.add(executor.submit(() -> {
                    gate.barrier.set(barrier);
                    try { return service.process(request); }
                    finally { gate.barrier.remove(); }
                }));
            }
            var results = new ArrayList<LoadOutcome>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            for (var future : futures) {
                results.add(future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            }
            assertThat(gate.sessions).hasSize(requests.size());
            assertThat(gate.sessions.stream().distinct().count()).isEqualTo(requests.size());
            // Compare every caller's returned result to authoritative committed state.
            for (int i = 0; i < requests.size(); i++) {
                var request = requests.get(i);
                assertThat(loads.findResult(request.customerId(), request.loadId())).contains(stored(results.get(i)));
            }
            return results;
        } finally {
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).as("worker termination").isTrue();
        }
    }

    private void seed(String customer, long amount, int count, long weekly) {
        // Setup uses autocommit connections: no test transaction is hidden from workers.
        jdbc.sql("INSERT INTO daily_velocity VALUES (?, ?, ?, ?, ?)")
                .param(customer).param(DAY).param(amount).param(count).param(EVENT.atOffset(ZoneOffset.UTC)).update();
        jdbc.sql("INSERT INTO weekly_velocity VALUES (?, ?, ?, ?)")
                .param(customer).param(WEEK).param(weekly).param(EVENT.atOffset(ZoneOffset.UTC)).update();
    }

    private LoadAttempt load(String customer, String id, long cents) {
        return new LoadAttempt(id, customer, new Money(cents), EVENT);
    }

    private LoadResultRepository.StoredLoadResult stored(LoadOutcome outcome) {
        return switch (outcome) {
            case LoadOutcome.Completed completed -> completed.result();
            case LoadOutcome.Duplicate duplicate -> duplicate.originalResult();
        };
    }

    private void assertReasons(List<LoadOutcome> outcomes, long accepted, DecisionReason decline) {
        assertThat(outcomes).allMatch(outcome -> outcome instanceof LoadOutcome.Completed);
        assertThat(outcomes.stream().filter(outcome -> stored(outcome).decision().accepted()).count()).isEqualTo(accepted);
        assertThat(outcomes.stream().filter(outcome -> !stored(outcome).decision().accepted())
                .map(outcome -> stored(outcome).decision().reason()).toList())
                .containsExactlyElementsOf(java.util.Collections.nCopies(outcomes.size() - (int) accepted, decline));
    }

    private void assertBuckets(String customer, long amount, int count, long weekly) {
        assertThat(velocity.findDailyBucket(customer, DAY)).contains(new VelocityRepository.DailyBucket(amount, count, EVENT));
        assertThat(velocity.findWeeklyBucket(customer, WEEK)).contains(new VelocityRepository.WeeklyBucket(weekly, EVENT));
    }

    private void assertRecordCount(long count) {
        assertThat(jdbc.sql("SELECT COUNT(*) FROM load_attempt").query(Long.class).single()).isEqualTo(count);
    }

    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @EnableTransactionManagement(proxyTargetClass = true, order = 0)
    static class Transactions { }

    @Aspect
    @Order(1) // Run inside the real transaction interceptor, before any business SQL.
    static class TransactionGate {
        private final JdbcClient jdbc;
        private final ThreadLocal<CyclicBarrier> barrier = new ThreadLocal<>();
        private final ConcurrentLinkedQueue<Integer> sessions = new ConcurrentLinkedQueue<>();

        TransactionGate(JdbcClient jdbc) { this.jdbc = jdbc; }

        @Before("execution(* com.example.moneyload.application.LoadFundsTransaction.process(..))")
        public void awaitConcurrentTransactions() throws Exception {
            var start = barrier.get();
            if (start != null) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(TransactionAspectSupport.currentTransactionStatus().isNewTransaction()).isTrue();
                sessions.add(jdbc.sql("SELECT SESSION_ID()").query(Integer.class).single());
                start.await(5, TimeUnit.SECONDS);
            }
        }
    }
}
