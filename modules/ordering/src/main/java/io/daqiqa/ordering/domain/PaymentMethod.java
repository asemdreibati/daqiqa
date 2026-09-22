package io.daqiqa.ordering.domain;

/** How the customer pays. */
public enum PaymentMethod {
    /** Paid by card at checkout: the order waits for the payment before it is confirmed. */
    CARD,
    /** Paid to the rider: nothing to wait for, so the order is confirmed as soon as its stock is held. */
    CASH_ON_DELIVERY;

    /** The status a new order paid this way starts in. */
    public OrderStatus initialStatus() {
        return this == CARD ? OrderStatus.AWAITING_PAYMENT : OrderStatus.CONFIRMED;
    }
}
