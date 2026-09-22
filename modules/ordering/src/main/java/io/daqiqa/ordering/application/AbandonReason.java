package io.daqiqa.ordering.application;

import java.util.Locale;

/** Why an order was abandoned; published as {@code OrderAbandonedV1.reason} and counted by it. */
enum AbandonReason {
    /** The payment never completed within the hold: the checkout stopped, or the client gave up. */
    HOLD_EXPIRED,
    /** The payment was approved, but only after the hold had expired: an authorization certainly exists. */
    PAID_AFTER_HOLD_EXPIRED;

    String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
