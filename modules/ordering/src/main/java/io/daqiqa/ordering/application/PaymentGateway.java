package io.daqiqa.ordering.application;

import io.daqiqa.kernel.Money;
import io.daqiqa.kernel.OrderId;

/** Outbound port: takes card payments. v1 has a simulated adapter; real providers plug in behind this interface. */
public interface PaymentGateway {

    /**
     * Authorizes {@code amount} for the order.
     *
     * <p>The order id is the provider's idempotency key (ADR-0008): authorizing the same order again, after a crash or
     * by a second instance that took the checkout over, returns the first decision and never charges twice.
     * Implementations may block; checkout calls them outside any database transaction.
     */
    PaymentResult authorize(OrderId orderId, Money amount);
}
