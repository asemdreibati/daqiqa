package io.daqiqa.network.api;

import io.daqiqa.kernel.StoreId;
import java.time.Duration;

/**
 * One store able to serve a location.
 *
 * @param storeId the store
 * @param estimatedTravelTime baseline rider travel time from the store to the location (no traffic adjustment)
 * @param distanceMeters straight-line distance from the store to the location
 */
public record StoreCoverage(StoreId storeId, Duration estimatedTravelTime, int distanceMeters) {}
