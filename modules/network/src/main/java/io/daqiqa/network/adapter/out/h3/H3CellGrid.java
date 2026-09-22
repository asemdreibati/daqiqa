package io.daqiqa.network.adapter.out.h3;

import com.uber.h3core.H3Core;
import com.uber.h3core.LengthUnit;
import com.uber.h3core.util.LatLng;
import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.network.domain.CellGrid;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * {@link CellGrid} on Uber's H3 hexagonal grid at resolution 9 (cells of about 0.1 km², ADR-0006).
 *
 * <p>Why H3: hexagons have one kind of neighbour, all at the same distance, so "every cell within r metres" is a grid
 * disk around one cell, filtered by distance, with no special cases at cell corners (unlike geohash squares).
 *
 * <p>{@link #cellsCovering} must never miss a cell that the circle touches, or a customer inside a store's radius but
 * near its edge would be told nobody delivers there. A cell centre within {@code radius} is not enough: a cell whose
 * centre lies just outside the circle can still contain points inside it. So the filter keeps every cell whose centre
 * is within {@code radius + cellRadius}, where {@code cellRadius} bounds the distance from a cell's centre to any of
 * its points. The few extra cells only cost an exact distance check on lookup.
 */
public final class H3CellGrid implements CellGrid {

    public static final int RESOLUTION = 9;

    /**
     * Upper bound of the centre-to-vertex distance of a cell, as a multiple of the average edge length. Measured at
     * resolution 9: at most 1.0 in the Gulf and 1.1 anywhere on Earth; 1.5 leaves a wide margin.
     */
    private static final double CELL_RADIUS_IN_EDGES = 1.5;

    /**
     * Lower bound of how much farther from the origin each successive ring of a grid disk reaches, as a multiple of
     * the average edge length. Measured: 1.36 in Dubai (a regular hexagon grid gives 1.5), never below 1.08 anywhere
     * on Earth; 0.75 leaves a wide margin.
     */
    private static final double RING_SPACING_IN_EDGES = 0.75;

    private final H3Core h3;
    private final double cellRadiusMeters;
    private final double ringSpacingMeters;

    /** @param h3 the H3 library; it loads native code, so create it once and share it (it is thread-safe) */
    public H3CellGrid(H3Core h3) {
        this.h3 = h3;
        double edgeMeters = h3.getHexagonEdgeLengthAvg(RESOLUTION, LengthUnit.m);
        this.cellRadiusMeters = CELL_RADIUS_IN_EDGES * edgeMeters;
        this.ringSpacingMeters = RING_SPACING_IN_EDGES * edgeMeters;
    }

    /** Loads the H3 native library. */
    public static H3CellGrid create() {
        try {
            return new H3CellGrid(H3Core.newInstance());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load the H3 native library", e);
        }
    }

    @Override
    public long cellOf(GeoPoint point) {
        return h3.latLngToCell(point.latitude(), point.longitude(), RESOLUTION);
    }

    @Override
    public long[] cellsCovering(GeoPoint centre, double radiusMeters) {
        // Cells that can contain a point within the radius have their centre within this reach of the circle's centre.
        double reach = radiusMeters + cellRadiusMeters;
        // Measured from the centre of the origin cell (up to cellRadius away from the circle's centre), those cells lie
        // within reach + cellRadius; each ring of the disk extends at least ringSpacing further out.
        int rings = (int) Math.ceil((reach + cellRadiusMeters) / ringSpacingMeters);
        return h3.gridDisk(cellOf(centre), rings).stream()
                .mapToLong(Long::longValue)
                .filter(cell -> centreOf(cell).distanceMetersTo(centre) <= reach)
                .toArray();
    }

    private GeoPoint centreOf(long cell) {
        LatLng centre = h3.cellToLatLng(cell);
        return GeoPoint.of(centre.lat, centre.lng);
    }
}
