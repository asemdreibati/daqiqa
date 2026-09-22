package io.daqiqa.ordering.adapter.in.web;

import org.jspecify.annotations.Nullable;

/** A request that is well-formed JSON but breaks a rule of the value objects (400 {@code VALIDATION_FAILED}). */
final class InvalidRequestException extends RuntimeException {

    InvalidRequestException(@Nullable String message) {
        super(message == null ? "Invalid request" : message);
    }
}
