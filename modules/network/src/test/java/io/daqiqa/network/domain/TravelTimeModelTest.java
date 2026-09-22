package io.daqiqa.network.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import org.junit.jupiter.api.Test;

class TravelTimeModelTest {

    /** The v1 defaults: road factor 1.3, 7 m/s (≈ 25 km/h), one minute to hand over. */
    private static final TravelTimeModel DEFAULTS = new TravelTimeModel(1.3, 7.0, Duration.ofSeconds(60));

    @Test
    void ridesTheEstimatedRoadDistanceAtTheConfiguredSpeedThenHandsOver() {
        // 2 km straight line → 2.6 km of road → 371.4 s riding, rounded up to 372 s, plus 60 s handoff.
        assertThat(DEFAULTS.travelTime(2_000)).isEqualTo(Duration.ofSeconds(432));
    }

    @Test
    void roundsTheRidingTimeUpToAWholeSecond() {
        TravelTimeModel model = new TravelTimeModel(1.0, 10.0, Duration.ZERO);

        assertThat(model.travelTime(100)).isEqualTo(Duration.ofSeconds(10));
        assertThat(model.travelTime(100.01)).isEqualTo(Duration.ofSeconds(11));
    }

    @Test
    void aCustomerNextDoorStillWaitsForTheHandoff() {
        assertThat(DEFAULTS.travelTime(0)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void rejectsParametersThatCannotDescribeARide() {
        Duration handoff = Duration.ofSeconds(60);
        assertThatIllegalArgumentException().isThrownBy(() -> new TravelTimeModel(0.9, 7.0, handoff));
        assertThatIllegalArgumentException().isThrownBy(() -> new TravelTimeModel(Double.NaN, 7.0, handoff));
        assertThatIllegalArgumentException().isThrownBy(() -> new TravelTimeModel(1.3, 0.0, handoff));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TravelTimeModel(1.3, Double.POSITIVE_INFINITY, handoff));
        assertThatIllegalArgumentException().isThrownBy(() -> new TravelTimeModel(1.3, 7.0, Duration.ofSeconds(-1)));
    }

    @Test
    void rejectsImpossibleDistances() {
        assertThatIllegalArgumentException().isThrownBy(() -> DEFAULTS.travelTime(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> DEFAULTS.travelTime(Double.NaN));
    }

    @Property
    void aLongerRideNeverTakesLessTimeAndNeverSkipsTheHandoff(
            @ForAll @DoubleRange(min = 0, max = 20_000) double distance,
            @ForAll @DoubleRange(min = 0, max = 5_000) double extra) {
        Duration nearer = DEFAULTS.travelTime(distance);
        Duration farther = DEFAULTS.travelTime(distance + extra);

        assertThat(farther).isGreaterThanOrEqualTo(nearer);
        assertThat(nearer).isGreaterThanOrEqualTo(DEFAULTS.handoff());
    }
}
