package io.daqiqa.inventory.domain;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.daqiqa.kernel.Ids;
import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.StoreId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class StockAdjustmentTest {

    @Test
    void anAuditEntryRecordsARealChangeThatLeftStockOnHand() {
        assertThatIllegalArgumentException().isThrownBy(() -> entry(0, 5));
        assertThatIllegalArgumentException().isThrownBy(() -> entry(-3, -1));
    }

    private static StockAdjustment entry(int delta, int onHandAfter) {
        return new StockAdjustment(
                Ids.newTimeOrderedId(),
                StoreId.of("DXB-MARINA"),
                Sku.of("MILK-FULL-1L"),
                delta,
                AdjustmentReason.CORRECTION,
                onHandAfter,
                Instant.parse("2026-09-22T10:00:00Z"));
    }
}
