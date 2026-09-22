package io.daqiqa.network.adapter.out.h3;

import static io.daqiqa.network.StoreFixtures.DUBAI_MARINA;
import static io.daqiqa.network.StoreFixtures.offset;
import static org.assertj.core.api.Assertions.assertThat;

import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.network.domain.Store;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class H3CellGridTest {

    private static final H3CellGrid GRID = H3CellGrid.create();

    @Test
    void theCellOfTheCentreIsAlwaysCovered() {
        assertThat(GRID.cellsCovering(DUBAI_MARINA, Store.MIN_SERVICE_RADIUS_METERS))
                .contains(GRID.cellOf(DUBAI_MARINA));
    }

    @Test
    void listsEachCellOnce() {
        long[] cells = GRID.cellsCovering(DUBAI_MARINA, 3_000);

        assertThat(LongStream.of(cells).distinct().count()).isEqualTo(cells.length);
    }

    @Test
    void coversAboutTheCircleAndNotMuchMore() {
        // A 3 km circle is 28.3 km²; resolution-9 cells in Dubai are about 0.09 km². The margin adds one ring of cells.
        assertThat(GRID.cellsCovering(DUBAI_MARINA, 3_000).length).isBetween(300, 450);
    }

    /** A customer inside the service area but near its edge must never be told that nobody delivers there. */
    @Property(tries = 60)
    void neverMissesACellThatTheServiceAreaTouchesInTheGulf(
            @ForAll @DoubleRange(min = 24.0, max = 26.5) double latitude,
            @ForAll @DoubleRange(min = 46.0, max = 56.5) double longitude,
            @ForAll @IntRange(min = Store.MIN_SERVICE_RADIUS_METERS, max = Store.MAX_SERVICE_RADIUS_METERS) int radius,
            @ForAll Random random) {
        assertCoversEveryPointOfTheCircle(GeoPoint.of(latitude, longitude), radius, random);
    }

    /** The margins are derived from H3's geometry, not tuned to one city. */
    @Property(tries = 30)
    void neverMissesACellThatTheServiceAreaTouchesAnywhereOnEarth(
            @ForAll @DoubleRange(min = -80, max = 80) double latitude,
            @ForAll @DoubleRange(min = -180, max = 180) double longitude,
            @ForAll @IntRange(min = Store.MIN_SERVICE_RADIUS_METERS, max = Store.MAX_SERVICE_RADIUS_METERS) int radius,
            @ForAll Random random) {
        assertCoversEveryPointOfTheCircle(GeoPoint.of(latitude, longitude), radius, random);
    }

    private static void assertCoversEveryPointOfTheCircle(GeoPoint centre, int radius, Random random) {
        Set<Long> covering =
                LongStream.of(GRID.cellsCovering(centre, radius)).boxed().collect(Collectors.toSet());
        // The edge is where a cell-centre test would fail: probe all the way round, just inside the circle.
        for (int bearing = 0; bearing < 360; bearing++) {
            GeoPoint edge = offset(centre, bearing, radius - 0.01);
            assertThat(covering).as("edge point at bearing %d", bearing).contains(GRID.cellOf(edge));
        }
        for (int i = 0; i < 200; i++) {
            GeoPoint inside = offset(centre, random.nextDouble(360), radius * Math.sqrt(random.nextDouble()));
            assertThat(covering).as("inside point %s", inside).contains(GRID.cellOf(inside));
        }
    }
}
