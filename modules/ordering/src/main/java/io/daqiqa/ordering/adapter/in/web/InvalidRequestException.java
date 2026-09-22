package io.daqiqa.ordering.adapter.in.web;

import org.jspecify.annotations.Nullable;

/**
 * A request that is well-formed JSON but breaks a rule of the value objects, such as one SKU spread over lines adding
 * up to more than a line may hold (400 {@code INVALID_REQUEST}: the rule is about the request as a whole, not a field).
 */
final class InvalidRequestException extends RuntimeException {

    InvalidRequestException(@Nullable String message) {
        super(message == null ? "Invalid request" : message);
    }
}
