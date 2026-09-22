package io.daqiqa.inventory.application;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import io.daqiqa.inventory.domain.AdjustmentReason;
import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.StoreId;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AdjustStockTest {

    private static final StoreId MARINA = StoreId.of("DXB-MARINA");
    private static final Sku MILK = Sku.of("MILK-FULL-1L");

    @ParameterizedTest
    @ValueSource(ints = {-AdjustStock.MAX_DELTA, -1, 1, AdjustStock.MAX_DELTA})
    void acceptsAnyNonZeroChangeWithinTheBound(int delta) {
        assertThatNoException().isThrownBy(() -> new AdjustStock(MARINA, MILK, delta, AdjustmentReason.CORRECTION));
    }

    @ParameterizedTest
    @ValueSource(ints = {-AdjustStock.MAX_DELTA - 1, 0, AdjustStock.MAX_DELTA + 1})
    void refusesNoChangeOrAChangeBeyondTheBound(int delta) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AdjustStock(MARINA, MILK, delta, AdjustmentReason.CORRECTION));
    }
}
