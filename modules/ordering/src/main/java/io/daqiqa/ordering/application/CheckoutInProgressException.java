package io.daqiqa.ordering.application;

import java.time.Duration;

/**
 * Another request with the same idempotency key is being processed right now (409
 * {@code IDEMPOTENCY_REQUEST_IN_PROGRESS}).
 */
public final class CheckoutInProgressException extends RuntimeException {

    private final Duration retryAfter;

    public CheckoutInProgressException(Duration retryAfter) {
        super("A request with this Idempotency-Key is still being processed; retry shortly");
        this.retryAfter = retryAfter;
    }

    /** When the claim's lease runs out: a retry then either replays the outcome or takes the claim over. */
    public Duration retryAfter() {
        return retryAfter;
    }
}
