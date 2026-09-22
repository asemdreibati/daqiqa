package io.daqiqa.promise.domain;

import io.daqiqa.kernel.StoreId;
import java.time.Duration;
import java.util.Objects;

/**
 * A store that can reach the customer, as the coverage index reported it.
 *
 * @param travelTime baseline ride from the store to the customer
 * @param distanceMeters straight-line distance; between equally fast stores, the nearer one is preferred
 */
public record Candidate(StoreId storeId, Duration travelTime, int distanceMeters) {

    public Candidate {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(travelTime, "travelTime");
        if (travelTime.isNegative() || distanceMeters < 0) {
            throw new IllegalArgumentException("Travel time and distance of " + storeId + " cannot be negative");
        }
    }
}
