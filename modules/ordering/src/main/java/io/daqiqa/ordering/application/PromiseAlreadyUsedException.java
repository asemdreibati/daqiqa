package io.daqiqa.ordering.application;

/**
 * A checkout under the same promise, with another idempotency key, committed its order while this one was holding the
 * stock. Rolls T1 back, the reservation with it, before the rejection is stored.
 */
final class PromiseAlreadyUsedException extends RuntimeException {

    PromiseAlreadyUsedException() {
        super("Another order already uses this promise");
    }
}
