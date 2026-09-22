package io.daqiqa.ordering.application;

import java.util.Objects;

/**
 * The provider's decision on a card payment. A decline is an expected answer, not an exception. The order id, which
 * was the provider's idempotency key, is all it takes to find the payment again.
 */
public sealed interface PaymentResult {

    record Approved() implements PaymentResult {}

    /** @param reason safe to show the customer */
    record Declined(String reason) implements PaymentResult {

        public Declined {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
