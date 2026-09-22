package io.daqiqa.promise.application;

import io.daqiqa.kernel.PromiseId;
import io.daqiqa.promise.domain.Quote;
import java.time.Instant;
import java.util.Objects;

/**
 * A quote that was signed and handed to the customer.
 *
 * @param expiresAt when the token stops being accepted at checkout (whole seconds, exactly as in the token)
 * @param token what checkout verifies; opaque to clients
 */
public record IssuedPromise(PromiseId promiseId, Quote quote, Instant expiresAt, String token) {

    public IssuedPromise {
        Objects.requireNonNull(promiseId, "promiseId");
        Objects.requireNonNull(quote, "quote");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(token, "token");
    }
}
