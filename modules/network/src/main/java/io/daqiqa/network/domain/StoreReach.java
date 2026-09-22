package io.daqiqa.network.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * A store that can serve a point, with how far away the point is and how long the ride takes.
 *
 * @param distanceMeters great-circle distance from the store to the point
 * @param travelTime baseline rider travel time from {@link TravelTimeModel}
 */
public record StoreReach(Store store, double distanceMeters, Duration travelTime) {

    public StoreReach {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(travelTime, "travelTime");
    }
}
