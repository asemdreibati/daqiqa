package io.daqiqa.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.daqiqa.testing.postgres.PostgresTestDatabase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * What the assembled server serves beside the modules' endpoints: the contract itself, and the failures that belong to
 * no module — an unknown path, a method an endpoint does not have, a database that cannot answer now.
 */
@Tag("integration")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "daqiqa.messaging.publisher=logging")
@ActiveProfiles("dev")
class ServerApiTest {

    private static final String STORE = "DXB-MARINA";
    private static final String SKU = "DATES-KHALAS-1KG";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.shared().registerWith(registry);
    }

    @Value("${local.server.port}")
    int port;

    private RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
                .build();
    }

    @Test
    void swaggerUiShowsTheContractWeHandWrite() throws IOException {
        String configuration =
                http.get().uri("/v3/api-docs/swagger-config").retrieve().body(String.class);
        byte[] served = http.get().uri("/openapi/daqiqa-v1.yaml").retrieve().body(byte[].class);

        assertThat(JsonPath.<String>read(configuration, "$.url")).isEqualTo("/openapi/daqiqa-v1.yaml");
        assertThat(served)
                .as("byte for byte the repository's contract, not the document generated from the code")
                .isEqualTo(Files.readAllBytes(contract()));
    }

    @Test
    void aModuleStillAnswersItsOwnFailuresBeforeTheServerWideHandler() {
        assertProblem(get("/v1/products/NOPE-1"), 404, "PRODUCT_NOT_FOUND");
    }

    @Test
    void whatNoEndpointServesIsANotFoundProblem() {
        assertProblem(get("/v1/nothing-here"), 404, "NOT_FOUND");
    }

    @Test
    void aMethodAnEndpointDoesNotHaveIsAMethodNotAllowedProblem() {
        ResponseEntity<String> response =
                http.delete().uri("/v1/products/{sku}", SKU).retrieve().toEntity(String.class);

        assertProblem(response, 405, "METHOD_NOT_ALLOWED");
        assertThat(response.getHeaders().getFirst(HttpHeaders.ALLOW)).contains("GET");
    }

    /** A request stuck behind a hot row fails fast and says "again in a moment"; it never hangs, and it is not a bug. */
    @Test
    void aLockWaitThatTimesOutIsATemporaryUnavailability() throws SQLException {
        try (Connection holder = PostgresTestDatabase.shared().dataSource().getConnection()) {
            holder.setAutoCommit(false);
            try (Statement lock = holder.createStatement()) {
                lock.execute("SELECT 1 FROM inventory.stock_level WHERE store_id = '" + STORE + "' AND sku = '" + SKU
                        + "' FOR UPDATE");
                ResponseEntity<String> response = http.post()
                        .uri("/v1/stores/{store}/stock/adjustments", STORE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"sku\": \"%s\", \"delta\": 1, \"reason\": \"RECEIVED\"}".formatted(SKU))
                        .retrieve()
                        .toEntity(String.class);

                assertProblem(response, 503, "TEMPORARILY_UNAVAILABLE");
                assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                        .isEqualTo("1");
            } finally {
                holder.rollback();
            }
        }
    }

    private ResponseEntity<String> get(String path) {
        return http.get().uri(path).retrieve().toEntity(String.class);
    }

    /** An RFC 9457 problem with the contract's stable code, and a type URN derived from it. */
    private static void assertProblem(ResponseEntity<String> response, int status, String code) {
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType())
                .isNotNull()
                .satisfies(type -> assertThat(type.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                        .isTrue());
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo(code);
        assertThat(JsonPath.<String>read(response.getBody(), "$.type"))
                .isEqualTo("urn:daqiqa:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    /** The contract in the repository, found from wherever the tests run. */
    private static Path contract() {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("settings.gradle.kts"))) {
            directory = directory.getParent();
        }
        assertThat(directory)
                .as("the repository root above " + System.getProperty("user.dir"))
                .isNotNull();
        return directory.resolve("api/openapi/daqiqa-v1.yaml");
    }
}
