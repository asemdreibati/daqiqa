package io.daqiqa.ordering.domain;

import io.daqiqa.kernel.OrderId;

/** An order was asked to make a move its status does not allow, for example confirming an abandoned order. */
public final class IllegalOrderTransitionException extends IllegalStateException {

    public IllegalOrderTransitionException(OrderId orderId, OrderStatus from, OrderStatus to) {
        super("Order " + orderId + " cannot go from " + from + " to " + to);
    }
}
