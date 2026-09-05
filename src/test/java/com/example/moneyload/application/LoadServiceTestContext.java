package com.example.moneyload.application;

import com.example.moneyload.application.port.LoadResultRepository;
import com.example.moneyload.application.port.VelocityRepository;
import com.example.moneyload.domain.VelocityPolicy;
import java.time.Clock;
import com.example.moneyload.observability.LoadDecisionLoggingAspect;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** Real annotation-driven transaction proxies with test-supplied dependencies. */
final class LoadServiceTestContext {
    private LoadServiceTestContext() {
    }

    static AnnotationConfigApplicationContext create(LoadResultRepository loads, VelocityRepository velocity,
                                                      VelocityPolicy policy, Clock clock,
                                                      PlatformTransactionManager manager) {
        var context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfiguration.class);
        context.registerBean(LoadResultRepository.class, () -> loads);
        context.registerBean(VelocityRepository.class, () -> velocity);
        context.registerBean(VelocityPolicy.class, () -> policy);
        context.registerBean(Clock.class, () -> clock);
        context.registerBean(PlatformTransactionManager.class, () -> manager);
        context.register(LoadFundsTransaction.class, LoadFundsService.class, LoadDecisionLoggingAspect.class);
        context.refresh();
        return context;
    }

    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration {
    }
}
