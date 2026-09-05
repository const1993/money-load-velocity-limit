package com.example.moneyload.adapter.outbound.persistence;

import com.example.moneyload.application.port.LoadResultRepository;
import com.example.moneyload.domain.DecisionReason;
import com.example.moneyload.domain.LoadAttempt;
import com.example.moneyload.domain.LoadDecision;
import com.example.moneyload.domain.Money;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLoadResultRepository implements LoadResultRepository {
    private final JdbcClient jdbc;

    public JdbcLoadResultRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertDecision(LoadAttempt attempt, LoadDecision decision, Instant createdAt) {
        jdbc.sql("""
                INSERT INTO load_attempt
                    (customer_id, load_id, amount_cents, event_time, accepted, decision_reason, created_at)
                VALUES (:customer, :load, :amount, :eventTime, :accepted, :reason, :createdAt)
                """)
                .param("customer", attempt.customerId()).param("load", attempt.loadId())
                .param("amount", attempt.amount().cents())
                .param("eventTime", attempt.eventTimestamp().atOffset(ZoneOffset.UTC))
                .param("accepted", decision.accepted()).param("reason", decision.reason().name())
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC)).update();
    }

    @Override
    public Optional<StoredLoadResult> findResult(String customerId, String loadId) {
        return jdbc.sql("""
                SELECT customer_id, load_id, amount_cents, event_time, accepted, decision_reason, created_at
                FROM load_attempt WHERE customer_id = :customer AND load_id = :load
                """)
                .param("customer", customerId).param("load", loadId)
                .query((rs, rowNum) -> {
                    var decision = new LoadDecision(DecisionReason.valueOf(rs.getString("decision_reason")));
                    if (decision.accepted() != rs.getBoolean("accepted")) {
                        throw new IllegalStateException("Stored accepted flag disagrees with decision reason");
                    }
                    var attempt = new LoadAttempt(rs.getString("load_id"), rs.getString("customer_id"),
                            new Money(rs.getLong("amount_cents")),
                            rs.getObject("event_time", OffsetDateTime.class).toInstant());
                    return new StoredLoadResult(attempt, decision,
                            rs.getObject("created_at", OffsetDateTime.class).toInstant());
                }).optional();
    }
}
