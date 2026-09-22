package io.daqiqa.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.daqiqa.testing.postgres.PostgresTestDatabase;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * The whole promise-to-order path through every real module, over HTTP, on real PostgreSQL with the Dubai demo data:
 * the proof that the independently built modules compose.
 */
@Tag("integration")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "daqiqa.messaging.publisher=logging")
@ActiveProfiles("dev")
class CheckoutEndToEndTest {

    private static final String STORE = "DXB-MARINA";
    private static final String NEAR_MARINA = "{\"latitude\": 25.0772, \"longitude\": 55.1390}";
    private static final String AL_BARSHA = "{\"latitude\": 25.1136, \"longitude\": 55.2003}";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.shared().registerWith(registry);
    }

    @Value("${local.server.port}")
    int port;

    private RestClient http;
    private final JdbcClient db =
            JdbcClient.create(PostgresTestDatabase.shared().dataSource());

    @BeforeEach
    void setUp() {
        http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
                .build();
        setStoreStatus(STORE, "OPEN");
    }

    @Test
    void aPromiseBecomesExactlyOneOrderThatHoldsItsStockAndAnnouncesItself() {
        stockUp("MILK-FULL-1L", 20);
        stockUp("BREAD-ARABIC-6PK", 20);
        int milkReservedBefore = reserved("MILK-FULL-1L");
        String basket = basket("MILK-FULL-1L", 2, "BREAD-ARABIC-6PK", 1);

        String promise = promise(basket, NEAR_MARINA);
        assertThat(JsonPath.<String>read(promise, "$.status")).isEqualTo("AVAILABLE");
        assertThat(JsonPath.<String>read(promise, "$.offer.storeId")).isEqualTo(STORE);
        assertThat(JsonPath.<String>read(promise, "$.offer.tier")).isEqualTo("EXPRESS_15");
        String token = JsonPath.read(promise, "$.offer.token");

        String key = UUID.randomUUID().toString();
        ResponseEntity<String> first = placeOrder(key, token, basket, NEAR_MARINA, "CASH_ON_DELIVERY");
        ResponseEntity<String> retry = placeOrder(key, token, basket, NEAR_MARINA, "CASH_ON_DELIVERY");

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        String orderId = JsonPath.read(first.getBody(), "$.orderId");
        assertThat(first.getHeaders().getLocation()).hasToString("/v1/orders/" + orderId);
        assertThat(JsonPath.<String>read(first.getBody(), "$.status")).isEqualTo("CONFIRMED");
        assertThat(JsonPath.<Integer>read(first.getBody(), "$.total.amountMinor"))
                .isEqualTo(2 * 695 + 300);

        // The retry is answered from the stored response, byte for byte: still one order, one hold.
        assertThat(retry.getStatusCode().value()).isEqualTo(201);
        assertThat(retry.getBody()).isEqualTo(first.getBody());
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(reserved("MILK-FULL-1L")).isEqualTo(milkReservedBefore + 2);

        String fetched = http.get().uri("/v1/orders/{id}", orderId).retrieve().body(String.class);
        assertThat(JsonPath.<String>read(fetched, "$.deliverBy")).isNotBlank();
        assertThat(db.sql("""
                        SELECT count(*) FROM messaging.outbox_event
                        WHERE type = 'daqiqa.ordering.order-placed.v1' AND payload ->> 'orderId' = :orderId""").param("orderId", orderId).query(Long.class).single())
                .as("exactly one OrderPlaced event, committed with the order")
                .isEqualTo(1);
    }

    @Test
    void aDeclinedCardLeavesNothingHeld() {
        stockUp("BABY-FORMULA-800G", 20);
        int reservedBefore = reserved("BABY-FORMULA-800G");
        // 11 × 95.00 AED = 1,045.00 AED: above the simulated gateway's 1,000.00 AED limit.
        String basket = basket("BABY-FORMULA-800G", 11);
        String token = JsonPath.read(promise(basket, NEAR_MARINA), "$.offer.token");

        ResponseEntity<String> response = placeOrder(UUID.randomUUID().toString(), token, basket, NEAR_MARINA, "CARD");

        assertThat(response.getStatusCode().value()).isEqualTo(402);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("PAYMENT_DECLINED");
        assertThat(reserved("BABY-FORMULA-800G")).isEqualTo(reservedBefore);
    }

    @Test
    void aPromiseOnlyHoldsForTheAddressItWasMadeFor() {
        stockUp("WATER-1-5L", 20);
        String basket = basket("WATER-1-5L", 1);
        String token = JsonPath.read(promise(basket, NEAR_MARINA), "$.offer.token");

        ResponseEntity<String> response = placeOrder(UUID.randomUUID().toString(), token, basket, AL_BARSHA, "CARD");

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("PROMISE_LOCATION_MISMATCH");
    }

    @Test
    void pausingAStoreStopsNewPromisesAndCheckoutsUnderOldOnes() {
        stockUp("EGGS-12", 20);
        String basket = basket("EGGS-12", 1);
        String token = JsonPath.read(promise(basket, NEAR_MARINA), "$.offer.token");

        setStoreStatus(STORE, "PAUSED");
        ResponseEntity<String> checkout = placeOrder(UUID.randomUUID().toString(), token, basket, NEAR_MARINA, "CARD");
        String newPromise = promise(basket, NEAR_MARINA);

        assertThat(checkout.getStatusCode().value()).isEqualTo(409);
        assertThat(JsonPath.<String>read(checkout.getBody(), "$.code")).isEqualTo("STORE_UNAVAILABLE");
        assertThat(newPromise).doesNotContain("\"storeId\":\"" + STORE + "\"");
    }

    @Test
    void aTamperedTokenIsRefused() {
        stockUp("LABAN-1L", 20);
        String basket = basket("LABAN-1L", 1);
        String token = JsonPath.read(promise(basket, NEAR_MARINA), "$.offer.token");
        String[] parts = token.split("\\.");
        char flipped = parts[2].charAt(0) == 'A' ? 'B' : 'A';
        String tampered = String.join(".", parts[0], parts[1], flipped + parts[2].substring(1), parts[3]);

        ResponseEntity<String> response =
                placeOrder(UUID.randomUUID().toString(), tampered, basket, NEAR_MARINA, "CASH_ON_DELIVERY");

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("PROMISE_INVALID");
    }

    private String promise(String items, String location) {
        return http.post()
                .uri("/v1/promises")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"location\": " + location + ", \"items\": " + items + "}")
                .retrieve()
                .body(String.class);
    }

    private ResponseEntity<String> placeOrder(
            String key, String token, String items, String location, String paymentMethod) {
        String body = """
                {"promiseToken": "%s", "customerId": "019ceb9e-93a9-76c4-9863-875e34487ca3", "items": %s,
                 "deliveryLocation": %s, "paymentMethod": "%s"}""".formatted(token, items, location, paymentMethod);
        return http.post()
                .uri("/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .body(body)
                .retrieve()
                .toEntity(String.class);
    }

    private void stockUp(String sku, int delta) {
        ResponseEntity<String> response = http.post()
                .uri("/v1/stores/{store}/stock/adjustments", STORE)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"sku\": \"%s\", \"delta\": %d, \"reason\": \"RECEIVED\"}".formatted(sku, delta))
                .retrieve()
                .toEntity(String.class);
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
    }

    private int reserved(String sku) {
        String body = http.get()
                .uri("/v1/stores/{store}/stock?sku={sku}", STORE, sku)
                .retrieve()
                .body(String.class);
        return JsonPath.read(body, "$.items[0].reserved");
    }

    private void setStoreStatus(String store, String status) {
        ResponseEntity<String> response = http.put()
                .uri("/v1/stores/{store}/status", store)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"status\": \"" + status + "\"}")
                .retrieve()
                .toEntity(String.class);
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
    }

    private static String basket(Object... skuThenQuantity) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < skuThenQuantity.length; i += 2) {
            if (i > 0) {
                json.append(", ");
            }
            json.append("{\"sku\": \"%s\", \"quantity\": %d}".formatted(skuThenQuantity[i], skuThenQuantity[i + 1]));
        }
        return json.append(']').toString();
    }
}
