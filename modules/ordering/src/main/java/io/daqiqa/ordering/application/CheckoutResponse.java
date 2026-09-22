package io.daqiqa.ordering.application;

import io.daqiqa.kernel.OrderId;
import java.util.Objects;

/**
 * What {@link CheckoutService#placeOrder} answers.
 *
 * @param orderId the order this checkout created or would have created (the one its key is bound to)
 * @param response the stored response: always the same bytes for the same key
 * @param replayed whether this answer comes from an earlier request with the same key
 */
public record CheckoutResponse(OrderId orderId, StoredResponse response, boolean replayed) {

    public CheckoutResponse {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(response, "response");
    }
}
