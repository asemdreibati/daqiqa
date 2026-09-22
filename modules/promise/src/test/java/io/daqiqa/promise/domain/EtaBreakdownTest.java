package io.daqiqa.promise.domain;

import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EtaBreakdownTest {

    @ParameterizedTest(name = "{0} s is {1} min")
    @CsvSource({"1, 1", "59, 1", "60, 1", "61, 2", "600, 10", "601, 11", "3600, 60", "3601, 61"})
    void theEtaIsTheTotalRoundedUpToTheMinute(int totalSeconds, int etaMinutes) {
        EtaBreakdown breakdown = new EtaBreakdown(0, 0, 0, totalSeconds, 0);

        assertThat(breakdown.etaMinutes()).isEqualTo(etaMinutes);
    }

    @Test
    void addsUpEveryPart() {
        EtaBreakdown breakdown = new EtaBreakdown(60, 69, 60, 330, 90);

        assertThat(breakdown.totalSeconds()).isEqualTo(609);
        assertThat(breakdown.etaMinutes()).isEqualTo(11);
    }

    @Test
    void roundsEveryPartUpToTheSecondSoThePartsAddUpToTheEta() {
        EtaBreakdown breakdown =
                EtaBreakdown.of(ofMillis(59_001), ofSeconds(69), ofMillis(1), ofMillis(330_999), Duration.ZERO);

        assertThat(breakdown).isEqualTo(new EtaBreakdown(60, 69, 1, 331, 0));
    }

    @Test
    void aPromiseIsNeverForLessThanAMinute() {
        assertThat(new EtaBreakdown(0, 0, 0, 0, 0).etaMinutes()).isEqualTo(1);
    }

    @Test
    void rejectsNegativeParts() {
        assertThatIllegalArgumentException().isThrownBy(() -> new EtaBreakdown(0, -1, 0, 0, 0));
    }
}
