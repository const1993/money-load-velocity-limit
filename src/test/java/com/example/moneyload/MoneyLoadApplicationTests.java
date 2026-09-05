package com.example.moneyload;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyLoadApplicationTests {

    @Test
    void contextLoads() {
        try (var context = SpringApplication.run(MoneyLoadApplication.class, "--server.port=0")) {
            assertThat(context.isActive()).isTrue();
            assertThat(context.getBean(JdbcClient.class).sql("SELECT 1").query(Integer.class).single())
                    .isEqualTo(1);
            assertThat(context.getBean(Flyway.class).info().pending()).isEmpty();
        }
    }
}
