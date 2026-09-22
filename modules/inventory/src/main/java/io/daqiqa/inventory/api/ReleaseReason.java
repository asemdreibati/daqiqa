package io.daqiqa.inventory.api;

/** Why held stock goes back on sale. */
public enum ReleaseReason {
    ORDER_CANCELLED,
    PAYMENT_FAILED,
    ORDER_ABANDONED,
    EXPIRED,
    OPERATOR
}
