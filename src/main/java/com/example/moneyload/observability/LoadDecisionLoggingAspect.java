package com.example.moneyload.observability;

import com.example.moneyload.application.LoadOutcome;
import com.example.moneyload.domain.DecisionReason;
import com.example.moneyload.domain.LoadAttempt;
import com.example.moneyload.domain.VelocityBuckets;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class LoadDecisionLoggingAspect {
    private static final Logger log = LoggerFactory.getLogger(LoadDecisionLoggingAspect.class);

    // The service returns only after its transactional collaborator completes.
    @AfterReturning(
            pointcut = "execution(* com.example.moneyload.application.LoadFundsService.process(..)) && args(attempt)",
            returning = "outcome", argNames = "attempt,outcome")
    public void logOutcome(LoadAttempt attempt, LoadOutcome outcome) {
        if (!log.isDebugEnabled()) {
            return;
        }
        var day = VelocityBuckets.daily(attempt.eventTimestamp());
        var week = VelocityBuckets.weekly(attempt.eventTimestamp());
        String decision = switch (outcome) {
            case LoadOutcome.Completed completed -> completed.accepted() ? "ACCEPTED" : "DECLINED";
            case LoadOutcome.Duplicate ignored -> "DUPLICATE";
        };
        DecisionReason reason = switch (outcome) {
            case LoadOutcome.Completed completed -> completed.result().decision().reason();
            case LoadOutcome.Duplicate duplicate -> duplicate.originalResult().decision().reason();
        };
        // Logged only after transaction completion, never before a potentially failing commit.
        log.atDebug().addKeyValue("event", "load_decision")
                .addKeyValue("load_id", attempt.loadId()).addKeyValue("customer_id", attempt.customerId())
                .addKeyValue("daily_bucket", day).addKeyValue("weekly_bucket", week)
                .addKeyValue("amount_cents", attempt.amount().cents())
                .addKeyValue("decision", decision).addKeyValue("decision_reason", reason)
                .log("Load processing completed");
    }
}
