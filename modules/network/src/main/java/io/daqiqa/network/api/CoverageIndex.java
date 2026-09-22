package io.daqiqa.network.api;

import io.daqiqa.kernel.GeoPoint;
import java.util.List;

/**
 * Answers "which stores can deliver here, and how long is the ride?". Sits on the hot path of every promise, so
 * implementations must answer from memory (H3 cell lookup), not from the database.
 */
public interface CoverageIndex {

    /**
     * Stores whose service area contains {@code point} and that currently accept orders, fastest first.
     *
     * @return an empty list when the point is outside every service area
     */
    List<StoreCoverage> storesServing(GeoPoint point);
}
