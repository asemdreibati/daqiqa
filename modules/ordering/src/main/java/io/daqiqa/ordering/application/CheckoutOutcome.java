package io.daqiqa.ordering.application;

import java.util.Locale;

/** How a checkout request ended, as counted by the {@code outcome} tag of the checkout metrics. */
enum CheckoutOutcome {
    CONFIRMED,
    OUT_OF_STOCK,
    PAYMENT_DECLINED,
    HOLD_EXPIRED,
    PROMISE_EXPIRED,
    PROMISE_INVALID,
    PROMISE_CART_MISMATCH,
    PROMISE_LOCATION_MISMATCH,
    STORE_UNAVAILABLE,
    UNKNOWN_SKU,
    /** Answered with the response stored by an earlier request with the same key. */
    REPLAYED,
    IDEMPOTENCY_REQUEST_IN_PROGRESS,
    IDEMPOTENCY_KEY_REUSED,
    /** An unexpected failure (a 5xx); the claim was released so the client can retry. */
    FAILED;

    String tag() {
        return name().toLowerCase(Locale.ROOT);
    }

    static CheckoutOutcome of(CheckoutRejection rejection) {
        return switch (rejection) {
            case CheckoutRejection.PromiseRejected promise ->
                switch (promise.reason()) {
                    case EXPIRED -> PROMISE_EXPIRED;
                    case CART_MISMATCH -> PROMISE_CART_MISMATCH;
                    case LOCATION_MISMATCH -> PROMISE_LOCATION_MISMATCH;
                    case STORE_UNAVAILABLE -> STORE_UNAVAILABLE;
                    case MALFORMED, TAMPERED -> PROMISE_INVALID;
                };
            case CheckoutRejection.UnknownSkus unknown -> UNKNOWN_SKU;
            case CheckoutRejection.OutOfStock outOfStock -> OUT_OF_STOCK;
            case CheckoutRejection.PaymentDeclined declined -> PAYMENT_DECLINED;
            case CheckoutRejection.HoldExpired expired -> HOLD_EXPIRED;
        };
    }
}
