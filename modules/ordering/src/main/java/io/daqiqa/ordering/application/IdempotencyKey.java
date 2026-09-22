package io.daqiqa.ordering.application;

import io.daqiqa.kernel.CustomerId;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A client's {@code Idempotency-Key}, scoped to the customer who sent it (ADR-0008): two customers may pick the same
 * key without ever seeing each other's responses.
 */
public record IdempotencyKey(CustomerId customerId, String value) {

    /** The format the API contract allows: 8 to 64 URL-safe characters (a UUID is ideal). */
    public static final String FORMAT = "^[A-Za-z0-9_-]{8,64}$";

    private static final Pattern PATTERN = Pattern.compile(FORMAT);

    public IdempotencyKey {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(value, "value");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Malformed idempotency key");
        }
    }
}
