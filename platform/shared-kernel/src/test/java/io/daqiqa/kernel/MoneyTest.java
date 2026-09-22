package io.daqiqa.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void addsAndMultipliesInMinorUnits() {
        Money price = Money.of(1_250, "AED");

        assertThat(price.times(3).plus(Money.of(5, "AED"))).isEqualTo(Money.of(3_755, "AED"));
        assertThat(price.toMajorUnits()).isEqualByComparingTo(new BigDecimal("12.50"));
        assertThat(price).hasToString("12.50 AED");
    }

    @Test
    void refusesToMixCurrencies() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Money.of(100, "AED").plus(Money.of(100, "SAR")));
    }

    @Test
    void failsLoudlyOnOverflowInsteadOfWrappingAround() {
        assertThatThrownBy(() -> Money.of(Long.MAX_VALUE, "AED").plus(Money.of(1, "AED")))
                .isInstanceOf(ArithmeticException.class);
    }

    @Property
    void additionIsCommutative(
            @ForAll @LongRange(min = -1_000_000_000L, max = 1_000_000_000L) long a,
            @ForAll @LongRange(min = -1_000_000_000L, max = 1_000_000_000L) long b) {
        assertThat(Money.of(a, "SAR").plus(Money.of(b, "SAR")))
                .isEqualTo(Money.of(b, "SAR").plus(Money.of(a, "SAR")));
    }
}
