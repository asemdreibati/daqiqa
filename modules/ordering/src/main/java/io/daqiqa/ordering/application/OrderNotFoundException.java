package io.daqiqa.ordering.application;

import io.daqiqa.kernel.OrderId;

/** No order has this id (404 {@code ORDER_NOT_FOUND}). */
public final class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(OrderId orderId) {
        super("No order with id " + orderId);
    }
}
