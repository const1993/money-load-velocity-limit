package com.example.moneyload.adapter.inbound.rest;

import com.example.moneyload.MoneyLoadApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.*;

class LoadRestIntegrationTests {
    @Test
    void realHttpReplayReturnsCommittedDecisionAndIncrementsOnlyOnce() throws Exception {
        try (var context = SpringApplication.run(MoneyLoadApplication.class, "--server.port=0",
                "--spring.datasource.url=jdbc:h2:mem:rest-smoke;DB_CLOSE_ON_EXIT=FALSE");
             var client = HttpClient.newHttpClient()) {
            var uri = URI.create("http://localhost:" + context.getEnvironment().getProperty("local.server.port") + "/v1/loads");
            String body = """
                    {"id":"1","customer_id":"rest","load_amount":"$100.00","time":"2018-01-01T00:00:00Z"}
                    """;
            for (String payload : new String[]{body, body.replace("$100.00", "$99999.00")}) {
                var response = client.send(HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                        .header("X-Request-Id", "integration-123").POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).isEqualTo("{\"id\":\"1\",\"customer_id\":\"rest\",\"accepted\":true}");
                assertThat(response.headers().firstValue("X-Request-Id")).contains("integration-123");
            }
            var jdbc = context.getBean(JdbcClient.class);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM load_attempt").query(Long.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT accepted_count FROM daily_velocity").query(Integer.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT accepted_amount_cents FROM daily_velocity").query(Long.class).single()).isEqualTo(10000);
        }
    }
}
