package io.daqiqa.ordering.domain;

/**
 * Where an order is in its life. The allowed moves are defined here and enforced by {@link Order}:
 *
 * <pre>
 * AWAITING_PAYMENT ──▶ CONFIRMED ──▶ (wave 2: PICKING → OUT_FOR_DELIVERY → DELIVERED)
 *        │   └───────▶ PAYMENT_FAILED
 *        └───────────▶ ABANDONED
 * </pre>
 */
public enum OrderStatus {
    /** The stock is held; the card payment has not completed yet. */
    AWAITING_PAYMENT,
    /** Paid, or cash on delivery: the store must now pick it and a rider deliver it. */
    CONFIRMED,
    /** The card was declined; the stock went back on sale. */
    PAYMENT_FAILED,
    /** The payment never completed within the hold; the stock went back on sale. */
    ABANDONED;

    /** Whether an order in this status may move to {@code next}. */
    public boolean canBecome(OrderStatus next) {
        return switch (this) {
            case AWAITING_PAYMENT -> next == CONFIRMED || next == PAYMENT_FAILED || next == ABANDONED;
            case CONFIRMED, PAYMENT_FAILED, ABANDONED -> false;
        };
    }
}
