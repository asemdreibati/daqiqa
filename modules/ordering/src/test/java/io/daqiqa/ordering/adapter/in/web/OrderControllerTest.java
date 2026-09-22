package io.daqiqa.ordering.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import io.daqiqa.kernel.CustomerId;
import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.kernel.LocalizedText;
import io.daqiqa.kernel.Money;
import io.daqiqa.kernel.OrderId;
import io.daqiqa.kernel.PromiseId;
import io.daqiqa.kernel.ReservationId;
import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.kernel.StoreId;
import io.daqiqa.ordering.application.CheckoutInProgressException;
import io.daqiqa.ordering.application.CheckoutResponse;
import io.daqiqa.ordering.application.CheckoutService;
import io.daqiqa.ordering.application.IdempotencyKeyReusedException;
import io.daqiqa.ordering.application.OrderNotFoundException;
import io.daqiqa.ordering.application.OrderQueries;
import io.daqiqa.ordering.application.PlaceOrderCommand;
import io.daqiqa.ordering.application.StoredResponse;
import io.daqiqa.ordering.domain.BasketPricing;
import io.daqiqa.ordering.domain.Order;
import io.daqiqa.ordering.domain.PaymentMethod;
import io.daqiqa.ordering.domain.PricingResult;
import io.daqiqa.ordering.domain.ProductPrice;
import io.daqiqa.ordering.domain.PromiseTerms;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** The wire contract of the order endpoints: status codes, headers, problem codes; the use cases are mocked. */
@WebMvcTest(OrderController.class)
class OrderControllerTest {

    private static final String KEY = "0192f7c4-aaaa-7cc2-9d3a-000000000001";
    private static final String CUSTOMER = "0192f7c4-7c1e-7cc2-9d3a-2f1a6c0e9b11";
    private static final String VALID = """
            {"promiseToken": "v1.k1.payload.signature", "customerId": "%s",
             "items": [{"sku": "MILK-FULL-1L", "quantity": 2}, {"sku": "BREAD-ARABIC-6PK", "quantity": 1}],
             "deliveryLocation": {"latitude": 25.0772, "longitude": 55.139}, "paymentMethod": "CARD"}""".formatted(CUSTOMER);

    @Autowired
    MockMvcTester mvc;

    @MockitoBean
    CheckoutService checkout;

    @MockitoBean
    OrderQueries orders;

    @Test
    void placesTheOrderAndAnswersWithTheStoredBodyAndTheOrdersLocation() {
        OrderId orderId = OrderId.newId();
        String stored = "{\"orderId\":\"" + orderId + "\",\"name\":{\"ar\":\"حليب كامل الدسم\"}}";
        given(checkout.placeOrder(eq(KEY), any()))
                .willReturn(new CheckoutResponse(orderId, new StoredResponse(201, stored), false));

        assertThat(placeOrder(KEY, VALID))
                .hasStatus(HttpStatus.CREATED)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .hasHeader(HttpHeaders.LOCATION, "/v1/orders/" + orderId)
                .doesNotContainHeader(OrderController.REPLAYED)
                .body()
                .isEqualTo(stored.getBytes(StandardCharsets.UTF_8));
        then(checkout)
                .should()
                .placeOrder(
                        KEY,
                        new PlaceOrderCommand(
                                CustomerId.of(CUSTOMER),
                                "v1.k1.payload.signature",
                                List.of(SkuQuantity.of("MILK-FULL-1L", 2), SkuQuantity.of("BREAD-ARABIC-6PK", 1)),
                                GeoPoint.of(25.0772, 55.139),
                                PaymentMethod.CARD));
    }

    @Test
    void marksAReplayedResponseAndSendsTheStoredBytesUnchanged() {
        OrderId orderId = OrderId.newId();
        String stored = "{\"orderId\":\"" + orderId + "\"}";
        given(checkout.placeOrder(eq(KEY), any()))
                .willReturn(new CheckoutResponse(orderId, new StoredResponse(201, stored), true));

        assertThat(placeOrder(KEY, VALID))
                .hasStatus(HttpStatus.CREATED)
                .hasHeader(OrderController.REPLAYED, "true")
                .hasHeader(HttpHeaders.LOCATION, "/v1/orders/" + orderId)
                .body()
                .isEqualTo(stored.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void sendsAStoredRejectionAsAProblemWithItsOwnStatus() {
        String stored = "{\"type\":\"urn:daqiqa:problem:out-of-stock\",\"status\":409,\"code\":\"OUT_OF_STOCK\"}";
        given(checkout.placeOrder(eq(KEY), any()))
                .willReturn(new CheckoutResponse(OrderId.newId(), new StoredResponse(409, stored), false));

        assertThat(placeOrder(KEY, VALID))
                .hasStatus(HttpStatus.CONFLICT)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .doesNotContainHeader(HttpHeaders.LOCATION)
                .body()
                .isEqualTo(stored.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void refusesAnOrderWithoutAnIdempotencyKey() {
        MvcTestResult result = mvc.post()
                .uri("/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID)
                .exchange();

        assertProblem(result, HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_MISSING");
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo("urn:daqiqa:problem:idempotency-key-missing");
        then(checkout).shouldHaveNoInteractions();
    }

    @Test
    void refusesAMalformedIdempotencyKey() {
        MvcTestResult result = placeOrder("short", VALID);

        assertProblem(result, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo(OrderController.IDEMPOTENCY_KEY);
        then(checkout).shouldHaveNoInteractions();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidFields")
    void refusesARequestWithInvalidFieldsBeforeClaimingTheKey(String problem, String body) {
        assertProblem(placeOrder(KEY, body), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat(placeOrder(KEY, body))
                .bodyJson()
                .extractingPath("$.errors")
                .asArray()
                .as("every VALIDATION_FAILED problem names the fields at fault")
                .isNotEmpty();
        then(checkout).shouldHaveNoInteractions();
    }

    static Stream<Arguments> invalidFields() {
        String items51 = IntStream.rangeClosed(1, 51)
                .mapToObj(i -> "{\"sku\": \"SKU-" + i + "\", \"quantity\": 1}")
                .collect(Collectors.joining(","));
        return Stream.of(
                Arguments.of("zero quantity", VALID.replace("\"quantity\": 2", "\"quantity\": 0")),
                Arguments.of("quantity above 99", VALID.replace("\"quantity\": 2", "\"quantity\": 100")),
                Arguments.of("malformed SKU", VALID.replace("MILK-FULL-1L", "milk")),
                Arguments.of("empty basket", VALID.replaceAll("\\[.*]", "[]")),
                Arguments.of("51 lines", VALID.replaceAll("\\[.*]", "[" + items51 + "]")),
                Arguments.of("latitude out of range", VALID.replace("25.0772", "91")),
                Arguments.of("blank promise token", VALID.replace("v1.k1.payload.signature", " ")),
                Arguments.of("missing payment method", VALID.replace(", \"paymentMethod\": \"CARD\"", "")));
    }

    /** A body we cannot even read as a request: no field is at fault, the request is. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("unreadableRequests")
    void refusesARequestItCannotReadBeforeClaimingTheKey(String problem, String body) {
        assertProblem(placeOrder(KEY, body), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        then(checkout).shouldHaveNoInteractions();
    }

    static Stream<Arguments> unreadableRequests() {
        return Stream.of(
                Arguments.of("unknown payment method", VALID.replace("\"CARD\"", "\"BITCOIN\"")),
                Arguments.of("customer id not a UUID", VALID.replace(CUSTOMER, "customer-42")),
                Arguments.of("not JSON", "{\"promiseToken\": "),
                Arguments.of(
                        "one SKU split over lines adding up to more than 99",
                        VALID.replace("BREAD-ARABIC-6PK\", \"quantity\": 1", "MILK-FULL-1L\", \"quantity\": 98")));
    }

    @Test
    void reportsEveryInvalidFieldInOneProblem() {
        String body = VALID.replace("\"quantity\": 2", "\"quantity\": 0").replace("25.0772", "91");

        assertThat(placeOrder(KEY, body))
                .bodyJson()
                .extractingPath("$.errors")
                .asArray()
                .as("one entry per field, sorted")
                .containsExactly(
                        Map.of("field", "deliveryLocation.latitude", "message", "must be less than or equal to 90"),
                        Map.of("field", "items[0].quantity", "message", "must be greater than or equal to 1"));
    }

    @Test
    void refusesAKeyAlreadyUsedForAnotherRequest() {
        given(checkout.placeOrder(eq(KEY), any())).willThrow(new IdempotencyKeyReusedException());

        assertProblem(placeOrder(KEY, VALID), HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void asksTheClientToRetrySoonWhileTheFirstRequestIsStillInProgress() {
        given(checkout.placeOrder(eq(KEY), any()))
                .willThrow(new CheckoutInProgressException(Duration.ofMillis(42_100)));

        MvcTestResult result = placeOrder(KEY, VALID);

        assertProblem(result, HttpStatus.CONFLICT, "IDEMPOTENCY_REQUEST_IN_PROGRESS");
        assertThat(result)
                .as("the first request usually finishes long before its lease ends")
                .hasHeader(HttpHeaders.RETRY_AFTER, "2");
    }

    @Test
    void neverAsksTheClientToWaitBeyondTheLease() {
        given(checkout.placeOrder(eq(KEY), any())).willThrow(new CheckoutInProgressException(Duration.ofMillis(300)));

        assertThat(placeOrder(KEY, VALID)).hasHeader(HttpHeaders.RETRY_AFTER, "1");
    }

    @Test
    void returnsAnOrderInTheContractsShape() {
        Order order = anOrder();
        given(orders.get(order.id())).willReturn(order);

        assertThat(mvc.get().uri("/v1/orders/{id}", order.id().value()))
                .hasStatusOk()
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"orderId": "%s", "status": "AWAITING_PAYMENT", "storeId": "DXB-MARINA",
                         "customerId": "%s", "promiseId": "%s", "tier": "STANDARD_30", "etaMinutes": 25,
                         "placedAt": "2026-09-22T12:00:00.250Z", "deliverBy": "2026-09-22T12:25:00.250Z",
                         "paymentMethod": "CARD",
                         "items": [{"sku": "DATES-KHALAS-1KG", "name": {"en": "Khalas Dates 1kg", "ar": "تمر خلاص 1 كغ"},
                                    "quantity": 2, "unitPrice": {"amountMinor": 3500, "currency": "AED"},
                                    "lineTotal": {"amountMinor": 7000, "currency": "AED"}}],
                         "total": {"amountMinor": 7000, "currency": "AED"}}""".formatted(
                                order.id(), order.customerId(), order.promise().promiseId()));
    }

    @Test
    void answersNotFoundForAnUnknownOrder() {
        OrderId unknown = OrderId.newId();
        given(orders.get(unknown)).willThrow(new OrderNotFoundException(unknown));

        assertProblem(
                mvc.get().uri("/v1/orders/{id}", unknown.value()).exchange(), HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND");
    }

    @Test
    void refusesAMalformedOrderId() {
        assertProblem(mvc.get().uri("/v1/orders/not-a-uuid").exchange(), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
    }

    private MvcTestResult placeOrder(String idempotencyKey, String body) {
        return mvc.post()
                .uri("/v1/orders")
                .header(OrderController.IDEMPOTENCY_KEY, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private static void assertProblem(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result)
                .hasStatus(status)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo(code);
    }

    private static Order anOrder() {
        ProductPrice dates = new ProductPrice(
                Sku.of("DATES-KHALAS-1KG"),
                LocalizedText.of("Khalas Dates 1kg", "تمر خلاص 1 كغ"),
                Money.of(3_500, "AED"));
        PricingResult.Priced basket = (PricingResult.Priced)
                BasketPricing.price(List.of(new SkuQuantity(dates.sku(), 2)), Map.of(dates.sku(), dates));
        return Order.place(
                OrderId.newId(),
                CustomerId.newId(),
                new PromiseTerms(PromiseId.newId(), StoreId.of("DXB-MARINA"), "STANDARD_30", 25),
                Instant.parse("2026-09-22T12:00:00.250Z"),
                GeoPoint.of(25.0772, 55.139),
                PaymentMethod.CARD,
                ReservationId.newId(),
                basket);
    }
}
