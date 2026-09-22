package io.daqiqa.network.domain;

import io.daqiqa.kernel.GeoPoint;

/**
 * Outbound port: a discrete global grid that partitions the Earth into cells, used to find coverage candidates in
 * constant time. The production implementation is Uber's H3 at resolution 9 (ADR-0006).
 */
public interface CellGrid {

    /** The cell that contains {@code point}. */
    long cellOf(GeoPoint point);

    /**
     * Every cell that contains at least one point within {@code radiusMeters} of {@code centre}.
     *
     * <p>The result may include cells that only come close to the circle (callers check exact distances), but it
     * never misses one: a point inside the circle always lies in a returned cell.
     */
    long[] cellsCovering(GeoPoint centre, double radiusMeters);
}
