package com.example.moneyload;

import com.example.moneyload.domain.DecisionReason;
import com.example.moneyload.domain.VelocityPolicy;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MoneyLoadApplicationTests {
    private ConfigurableApplicationContext context;
    private JdbcClient jdbc;

    @BeforeAll
    void startApplication() {
        context = SpringApplication.run(MoneyLoadApplication.class, "--server.port=0",
                "--spring.datasource.url=jdbc:h2:mem:schema-tests;DB_CLOSE_ON_EXIT=FALSE");
        jdbc = context.getBean(JdbcClient.class);
    }

    @AfterAll
    void stopApplication() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void contextLoads() {
        assertThat(context.isActive()).isTrue();
        assertThat(context.getBean(VelocityPolicy.class)).isEqualTo(new VelocityPolicy(500000, 2000000, 3));
        assertThat(jdbc.sql("SELECT 1").query(Integer.class).single()).isEqualTo(1);
        var migrations = context.getBean(Flyway.class).info();
        assertThat(migrations.pending()).isEmpty();
        assertThat(migrations.applied()).hasSize(1);
        assertThat(migrations.current().getVersion().getVersion()).isEqualTo("1");
    }

    @Test
    void loadIdIsUniqueWithinEachCustomer() {
        insertLoad("customer-a", "shared-load", 100, true, "ACCEPTED");
        insertLoad("customer-b", "shared-load", 100, false, "DAILY_COUNT_LIMIT_EXCEEDED");
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertLoad("customer-a", "shared-load", 200, false,
                        "DAILY_AMOUNT_LIMIT_EXCEEDED"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM load_attempt WHERE load_id = 'shared-load'")
                .query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void committedLoadsRequireConsistentBusinessDecisions() {
        for (var reason : DecisionReason.values()) {
            insertLoad("decisions", reason.name(), 0, reason == DecisionReason.ACCEPTED, reason.name());
        }
        assertInvalidLoad(-1, true, "ACCEPTED");
        assertInvalidLoad(100, false, "ACCEPTED");
        assertInvalidLoad(100, true, "DAILY_COUNT_LIMIT_EXCEEDED");
        assertInvalidLoad(100, false, "TECHNICAL_FAILURE");
        assertInvalidLoad(100, null, "ACCEPTED");
        assertInvalidLoad(100, true, null);
    }

    @Test
    void dailyBucketsHaveCompositeKeysAndNonNegativeCounters() {
        insertDaily("2026-09-05", 0, 0);
        insertDaily("2026-09-06", Long.MAX_VALUE, 3);
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertDaily("2026-09-05", 0, 0));
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertDaily("2026-09-07", -1, 0));
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertDaily("2026-09-07", 0, -1));
    }

    @Test
    void weeklyBucketsHaveCompositeKeysAndNonNegativeAmounts() {
        insertWeekly("2026-08-31", 0);
        insertWeekly("2026-09-07", Long.MAX_VALUE);
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertWeekly("2026-08-31", 0));
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertWeekly("2026-09-14", -1));
    }

    private void assertInvalidLoad(long amount, Boolean accepted, String reason) {
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertLoad("invalid", "invalid", amount, accepted, reason));
    }

    private void insertLoad(String customer, String load, long amount, Boolean accepted, String reason) {
        jdbc.sql("""
                INSERT INTO load_attempt
                    (customer_id, load_id, amount_cents, event_time, accepted, decision_reason, created_at)
                VALUES (?, ?, ?, TIMESTAMP WITH TIME ZONE '2026-09-05 12:00:00+00:00', ?, ?,
                    TIMESTAMP WITH TIME ZONE '2026-09-05 12:00:01+00:00')
                """)
                .param(customer).param(load).param(amount).param(accepted).param(reason).update();
    }

    private void insertDaily(String date, long amount, int count) {
        jdbc.sql("""
                INSERT INTO daily_velocity
                    (customer_id, date_utc, accepted_amount_cents, accepted_count, updated_at)
                VALUES ('customer-a', CAST(? AS DATE), ?, ?,
                    TIMESTAMP WITH TIME ZONE '2026-09-05 12:00:01+00:00')
                """).param(date).param(amount).param(count).update();
    }

    private void insertWeekly(String date, long amount) {
        jdbc.sql("""
                INSERT INTO weekly_velocity
                    (customer_id, week_start_utc, accepted_amount_cents, updated_at)
                VALUES ('customer-a', CAST(? AS DATE), ?,
                    TIMESTAMP WITH TIME ZONE '2026-09-05 12:00:01+00:00')
                """).param(date).param(amount).update();
    }
}
