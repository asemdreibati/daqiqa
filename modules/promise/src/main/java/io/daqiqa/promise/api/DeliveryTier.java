package io.daqiqa.promise.api;

import java.util.Optional;

/** Delivery speed tiers offered to customers, from fastest to slowest. */
public enum DeliveryTier {
    EXPRESS_15(15),
    STANDARD_30(30),
    EXTENDED_60(60);

    private final int maxMinutes;

    DeliveryTier(int maxMinutes) {
        this.maxMinutes = maxMinutes;
    }

    public int maxMinutes() {
        return maxMinutes;
    }

    /** The fastest tier whose limit covers {@code etaMinutes}, or empty if even the slowest tier is exceeded. */
    public static Optional<DeliveryTier> forEta(int etaMinutes) {
        for (DeliveryTier tier : values()) {
            if (etaMinutes <= tier.maxMinutes) {
                return Optional.of(tier);
            }
        }
        return Optional.empty();
    }
}
