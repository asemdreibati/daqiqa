package io.daqiqa.ordering.adapter.in.web;

import static io.daqiqa.ordering.testsupport.FakeProductCatalog.BREAD;
import static io.daqiqa.ordering.testsupport.FakeProductCatalog.MILK;
import static org.assertj.core.api.Assertions.assertThat;

import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.ordering.domain.PaymentMethod;
import io.daqiqa.ordering.testsupport.OrderingIntegrationTest;
import io.daqiqa.ordering.testsupport.ScriptedPaymentGateway.SimulatedCrash;
import io.daqiqa.promise.api.DeliveryTier;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

/** The whole stack over HTTP: what a client really receives, byte for byte, from the first request and its retries. */
@AutoConfigureMockMvc
class CheckoutHttpTest extends OrderingIntegrationTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JsonMapper json;

    @Test
    void placesAnOrderThatItsLocationReturnsAndThatRetriesReplayByteForByte() {
        String body = requestBody(PaymentMethod.CARD, line(MILK, 2), line(BREAD, 1));

        MvcTestResult created = post(KEY, body);
        assertThat(created).hasStatus(HttpStatus.CREATED).hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON);
        String location = created.getResponse().getHeader(HttpHeaders.LOCATION);
        byte[] order = created.getResponse().getContentAsByteArray();

        assertThat(location).matches("/v1/orders/[0-9a-f-]{36}");
        assertThat(mvc.get().uri(location))
                .hasStatusOk()
                .body()
                .as("GET shows the order as placed")
                .isEqualTo(order);
        assertThat(post(KEY, body))
                .hasStatus(HttpStatus.CREATED)
                .hasHeader(OrderController.REPLAYED, "true")
                .hasHeader(HttpHeaders.LOCATION, location)
                .body()
                .isEqualTo(order);
    }

    @Test
    void storesAProblemShapedExactlyLikeTheOnesSpringRendersAndReplaysIt() {
        inventory.stock(STORE, MILK.sku(), 1);
        String body = requestBody(PaymentMethod.CARD, line(MILK, 3));

        MvcTestResult soldOut = post(KEY, body);

        assertThat(soldOut)
                .hasStatus(HttpStatus.CONFLICT)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"type": "urn:daqiqa:problem:out-of-stock", "title": "Out of stock", "status": 409,
                         "instance": "/v1/orders", "code": "OUT_OF_STOCK",
                         "shortages": [{"sku": "MILK-FULL-1L", "requested": 3, "available": 1}]}""");
        MvcTestResult rendered = mvc.post()
                .uri("/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
        assertThat(fields(soldOut)).isEqualTo(fields(rendered) + ",shortages");
        assertThat(post(KEY, body))
                .hasStatus(HttpStatus.CONFLICT)
                .hasHeader(OrderController.REPLAYED, "true")
                .body()
                .isEqualTo(soldOut.getResponse().getContentAsByteArray());
    }

    @Test
    void tellsARetryWhenToComeBackWhileTheFirstRequestIsStillRunning() {
        payments.crash(); // the first request's instance stops answering in the middle of the payment
        String body = requestBody(PaymentMethod.CARD, line(MILK, 1));
        assertThat(post(KEY, body)).hasFailed().failure().hasRootCauseInstanceOf(SimulatedCrash.class);

        MvcTestResult retry = post(KEY, body);

        assertThat(retry)
                .hasStatus(HttpStatus.CONFLICT)
                .hasHeader(HttpHeaders.RETRY_AFTER, "2")
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("IDEMPOTENCY_REQUEST_IN_PROGRESS");
    }

    private MvcTestResult post(String idempotencyKey, String body) {
        return mvc.post()
                .uri("/v1/orders")
                .header(OrderController.IDEMPOTENCY_KEY, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    /** A request under a promise freshly issued for exactly this basket. */
    private String requestBody(PaymentMethod paymentMethod, SkuQuantity... basket) {
        String token = promises.issue(STORE, DeliveryTier.EXPRESS_15, 12, List.of(basket), HOME);
        String items = List.of(basket).stream()
                .map(line -> "{\"sku\": \"%s\", \"quantity\": %d}".formatted(line.sku(), line.quantity()))
                .collect(Collectors.joining(", "));
        return """
                {"promiseToken": "%s", "customerId": "%s", "items": [%s],
                 "deliveryLocation": {"latitude": %s, "longitude": %s}, "paymentMethod": "%s"}""".formatted(token, CUSTOMER, items, HOME.latitude(), HOME.longitude(), paymentMethod);
    }

    /** The top-level field names of a JSON response, in order. */
    private String fields(MvcTestResult result) {
        return String.join(
                ",", json.readTree(result.getResponse().getContentAsByteArray()).propertyNames());
    }
}
