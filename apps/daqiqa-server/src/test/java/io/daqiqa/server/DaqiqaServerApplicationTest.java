package io.daqiqa.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.daqiqa.testing.postgres.PostgresTestDatabase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/** Boots the whole server against a real PostgreSQL with all migrations and demo data applied. */
@Tag("integration")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "daqiqa.messaging.publisher=logging")
@ActiveProfiles("dev")
class DaqiqaServerApplicationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.shared().registerWith(registry);
    }

    @Value("${local.server.port}")
    int port;

    private RestClient http() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
                .build();
    }

    @Test
    void isHealthy() {
        ResponseEntity<String> health =
                http().get().uri("/actuator/health").retrieve().toEntity(String.class);

        assertThat(health.getStatusCode().value()).isEqualTo(200);
        assertThat(health.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void servesTheDemoCatalogInBothLanguages() {
        String body =
                http().get().uri("/v1/products/DATES-KHALAS-1KG").retrieve().body(String.class);

        assertThat(body).contains("Khalas Dates 1kg").contains("تمر خلاص 1 كغ");
    }

    @Test
    void exposesPrometheusMetricsAndTheOpenApiDocument() {
        assertThat(http().get()
                        .uri("/actuator/prometheus")
                        .retrieve()
                        .toEntity(String.class)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(http().get().uri("/v3/api-docs").retrieve().body(String.class))
                .contains("/v1/products");
    }
}
