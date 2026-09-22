package io.daqiqa.ordering.application;

import io.daqiqa.kernel.OrderId;
import java.util.Objects;

/**
 * The right to process one checkout: an idempotency key claimed for a request, and the id of the order that request
 * creates. The order id is fixed when the key is first claimed, so every attempt with this key works on the same order.
 */
public record Claim(IdempotencyKey key, OrderId orderId) {

    public Claim {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(orderId, "orderId");
    }
}
