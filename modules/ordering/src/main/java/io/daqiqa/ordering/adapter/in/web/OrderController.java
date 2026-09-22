package io.daqiqa.ordering.adapter.in.web;

import io.daqiqa.kernel.OrderId;
import io.daqiqa.ordering.application.CheckoutResponse;
import io.daqiqa.ordering.application.CheckoutService;
import io.daqiqa.ordering.application.IdempotencyKey;
import io.daqiqa.ordering.application.OrderQueries;
import io.daqiqa.ordering.application.StoredResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /v1/orders} and {@code GET /v1/orders/{orderId}} (contract: api/openapi/daqiqa-v1.yaml). */
@RestController
@RequestMapping(OrderController.PATH)
class OrderController {

    static final String PATH = "/v1/orders";
    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    /** Marks a replayed response, as Stripe does; the status and body are the stored ones, byte for byte. */
    static final String REPLAYED = "Idempotent-Replayed";

    private final CheckoutService checkout;
    private final OrderQueries orders;

    OrderController(CheckoutService checkout, OrderQueries orders) {
        this.checkout = checkout;
        this.orders = orders;
    }

    /**
     * Places an order. The header comes first so that a request without a key is refused as such, whatever its body.
     * The response body is written as the stored bytes, never re-serialised, so every replay is identical.
     */
    @PostMapping
    ResponseEntity<byte[]> placeOrder(
            @RequestHeader(IDEMPOTENCY_KEY) @Pattern(regexp = IdempotencyKey.FORMAT) String idempotencyKey,
            @Valid @RequestBody PlaceOrderRequest request) {
        CheckoutResponse result = checkout.placeOrder(idempotencyKey, request.toCommand());
        StoredResponse response = result.response();
        ResponseEntity.BodyBuilder reply = ResponseEntity.status(response.status())
                .contentType(response.status() < 400 ? MediaType.APPLICATION_JSON : MediaType.APPLICATION_PROBLEM_JSON);
        if (response.status() == HttpStatus.CREATED.value()) {
            reply.location(URI.create(PATH + "/" + result.orderId()));
        }
        if (result.replayed()) {
            reply.header(REPLAYED, "true");
        }
        return reply.body(response.body().getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/{orderId}")
    OrderJson get(@PathVariable UUID orderId) {
        return OrderJson.from(orders.get(OrderId.of(orderId)));
    }
}
