package io.daqiqa.promise.domain;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class PickTimeModelTest {

    private final PickTimeModel model = new PickTimeModel(ofSeconds(45), ofSeconds(8), ofSeconds(600));

    @Test
    void takesABaseTimePlusTimePerUnit() {
        assertThat(model.pickTime(1)).isEqualTo(ofSeconds(53));
        assertThat(model.pickTime(3)).isEqualTo(ofSeconds(69));
    }

    @Test
    void stopsGrowingAtTheCapBecauseBigBasketsAreSplitBetweenPickers() {
        assertThat(model.pickTime(69)).isEqualTo(ofSeconds(597));
        assertThat(model.pickTime(70)).isEqualTo(ofSeconds(600));
        assertThat(model.pickTime(4_950)).isEqualTo(ofSeconds(600));
    }

    @Test
    void rejectsImpossibleModelsAndBaskets() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PickTimeModel(ofSeconds(-1), ofSeconds(8), ofSeconds(600)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PickTimeModel(ofSeconds(45), ofSeconds(8), ofSeconds(30)));
        assertThatIllegalArgumentException().isThrownBy(() -> model.pickTime(0));
    }
}
