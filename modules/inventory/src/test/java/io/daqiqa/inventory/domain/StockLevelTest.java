package io.daqiqa.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.StoreId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StockLevelTest {

    private static final StoreId MARINA = StoreId.of("DXB-MARINA");
    private static final Sku MILK = Sku.of("MILK-FULL-1L");

    @Test
    void whatCanBeSoldIsWhatIsOnHandMinusWhatIsReserved() {
        assertThat(new StockLevel(MARINA, MILK, 10, 4).available()).isEqualTo(6);
        assertThat(StockLevel.none(MARINA, MILK).available()).isZero();
    }

    @ParameterizedTest(name = "on hand {0}, reserved {1}")
    @CsvSource({"-1, 0", "0, -1", "3, 4"})
    void refusesLevelsOutsideZeroToOnHand(int onHand, int reserved) {
        assertThatIllegalArgumentException().isThrownBy(() -> new StockLevel(MARINA, MILK, onHand, reserved));
    }

    @Test
    void aDeliveryAddsToOnHandAndLeavesReservationsAlone() {
        assertThat(new StockLevel(MARINA, MILK, 10, 4).adjust(24)).isEqualTo(new StockLevel(MARINA, MILK, 34, 4));
    }

    @Test
    void damageMayRemoveEveryUnitThatIsNotPromised() {
        assertThat(new StockLevel(MARINA, MILK, 10, 4).adjust(-6)).isEqualTo(new StockLevel(MARINA, MILK, 4, 4));
    }

    @Test
    void refusesToLeaveFewerUnitsOnHandThanAreReservedForOrders() {
        StockLevel level = new StockLevel(MARINA, MILK, 10, 4);

        assertThatThrownBy(() -> level.adjust(-7))
                .isInstanceOfSatisfying(StockBelowReservedException.class, e -> {
                    assertThat(e.level()).isEqualTo(level);
                    assertThat(e.delta()).isEqualTo(-7);
                })
                .hasMessageContaining("would leave 3 on hand, but 4 are reserved");
    }

    @Test
    void refusesANegativeQuantityOnHandEvenWithNothingReserved() {
        assertThatThrownBy(() -> new StockLevel(MARINA, MILK, 2, 0).adjust(-3))
                .isInstanceOf(StockBelowReservedException.class);
    }

    @Test
    void anAdjustmentMustChangeSomething() {
        assertThatIllegalArgumentException().isThrownBy(() -> new StockLevel(MARINA, MILK, 2, 0).adjust(0));
    }

    @Test
    void failsLoudlyInsteadOfOverflowing() {
        assertThatThrownBy(() -> new StockLevel(MARINA, MILK, Integer.MAX_VALUE, 0).adjust(1))
                .isInstanceOf(ArithmeticException.class);
    }
}
