package io.daqiqa.ordering.application;

/** The idempotency key was already used for a different request (422 {@code IDEMPOTENCY_KEY_REUSED}). */
public final class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException() {
        super("This Idempotency-Key was already used for a different request; use a new key for a new order");
    }
}
