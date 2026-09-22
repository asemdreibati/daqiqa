package io.daqiqa.inventory.domain;

/** Why a reservation stopped holding stock. Recorded on the reservation (column {@code release_reason}). */
public enum EndReason {
    /** The order was cancelled. */
    ORDER_CANCELLED,
    /** The payment was declined. */
    PAYMENT_FAILED,
    /** The order was never completed. */
    ORDER_ABANDONED,
    /** Nobody confirmed the hold in time. */
    EXPIRED,
    /** Released by hand from store operations. */
    OPERATOR
}
