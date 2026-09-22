package io.daqiqa.ordering.domain;

import io.daqiqa.kernel.PromiseId;
import io.daqiqa.kernel.StoreId;
import java.util.Objects;

/**
 * The delivery promise an order was placed under, carried by the order so that everything downstream (dispatch,
 * lateness, promise accuracy) is measured against exactly what the customer was shown.
 *
 * @param tier the delivery tier (for example {@code EXPRESS_15}). The promise context owns the tiers; an order only
 *     records which one it was sold under, so it keeps the name rather than depending on that context's model
 * @param etaMinutes minutes from placement to delivery that the customer was promised
 */
public record PromiseTerms(PromiseId promiseId, StoreId storeId, String tier, int etaMinutes) {

    public PromiseTerms {
        Objects.requireNonNull(promiseId, "promiseId");
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(tier, "tier");
        if (tier.isBlank()) {
            throw new IllegalArgumentException("A promise has a delivery tier");
        }
        if (etaMinutes < 1) {
            throw new IllegalArgumentException("A promised ETA is at least one minute, was " + etaMinutes);
        }
    }
}
