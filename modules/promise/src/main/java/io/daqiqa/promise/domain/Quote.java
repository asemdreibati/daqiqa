package io.daqiqa.promise.domain;

import io.daqiqa.kernel.StoreId;
import io.daqiqa.promise.api.DeliveryTier;
import java.util.Objects;

/**
 * The promise the calculator recommends: the store that delivers the whole basket soonest, and how soon.
 *
 * <p>{@link DeliveryTier} is this context's published enum; the domain uses it directly so that the tier limits have a
 * single source of truth.
 */
public record Quote(StoreId storeId, DeliveryTier tier, EtaBreakdown breakdown) {

    public Quote {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(breakdown, "breakdown");
        if (DeliveryTier.forEta(breakdown.etaMinutes()).orElse(null) != tier) {
            throw new IllegalArgumentException(
                    "An ETA of " + breakdown.etaMinutes() + " minutes is not in tier " + tier);
        }
    }

    public int etaMinutes() {
        return breakdown.etaMinutes();
    }
}
