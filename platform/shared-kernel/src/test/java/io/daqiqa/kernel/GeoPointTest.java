package io.daqiqa.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import org.junit.jupiter.api.Test;

class GeoPointTest {

    private static final GeoPoint DUBAI_MARINA = GeoPoint.of(25.0805, 55.1403);
    private static final GeoPoint BURJ_KHALIFA = GeoPoint.of(25.1972, 55.2744);

    @Test
    void measuresCityScaleDistances() {
        // Straight-line distance Dubai Marina ↔ Burj Khalifa is ≈ 18.8 km.
        assertThat(DUBAI_MARINA.distanceMetersTo(BURJ_KHALIFA)).isCloseTo(18_800, within(300.0));
    }

    @Test
    void rejectsOutOfRangeAndNaNCoordinates() {
        assertThatIllegalArgumentException().isThrownBy(() -> GeoPoint.of(91, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> GeoPoint.of(0, -181));
        assertThatIllegalArgumentException().isThrownBy(() -> GeoPoint.of(Double.NaN, 0));
    }

    @Property
    void distanceIsSymmetricAndNonNegative(
            @ForAll @DoubleRange(min = -89, max = 89) double lat1,
            @ForAll @DoubleRange(min = -179, max = 179) double lng1,
            @ForAll @DoubleRange(min = -89, max = 89) double lat2,
            @ForAll @DoubleRange(min = -179, max = 179) double lng2) {
        GeoPoint a = GeoPoint.of(lat1, lng1);
        GeoPoint b = GeoPoint.of(lat2, lng2);

        assertThat(a.distanceMetersTo(b)).isGreaterThanOrEqualTo(0).isCloseTo(b.distanceMetersTo(a), within(1e-6));
        assertThat(a.distanceMetersTo(a)).isZero();
    }
}
