package io.daqiqa.ordering.application;

import java.util.Objects;

/**
 * The HTTP response a checkout ended with, stored on its idempotency key. The first caller and every retry receive
 * exactly this status and these bytes.
 */
public record StoredResponse(int status, String body) {

    public StoredResponse {
        Objects.requireNonNull(body, "body");
        if (status < 200 || status > 599) {
            throw new IllegalArgumentException("Not an HTTP status: " + status);
        }
    }
}
