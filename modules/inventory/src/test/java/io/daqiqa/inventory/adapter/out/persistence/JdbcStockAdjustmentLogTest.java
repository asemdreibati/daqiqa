package io.daqiqa.inventory.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.daqiqa.inventory.domain.AdjustmentReason;
import io.daqiqa.inventory.domain.StockAdjustment;
import io.daqiqa.inventory.support.InventoryTables.AdjustmentRow;
import io.daqiqa.kernel.Ids;
import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.StoreId;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

class JdbcStockAdjustmentLogTest extends PostgresRepositoryTest {

    private final JdbcStockAdjustmentLog log = new JdbcStockAdjustmentLog(jdbc);

    @Test
    void appendsAnEntryToTheAuditTrail() {
        StockAdjustment delivery = new StockAdjustment(
                Ids.newTimeOrderedId(),
                StoreId.of("DXB-MARINA"),
                Sku.of("MILK-FULL-1L"),
                24,
                AdjustmentReason.RECEIVED,
                30,
                Instant.parse("2026-09-22T10:00:00Z"));

        log.append(delivery);

        assertThat(tables.adjustments())
                .containsExactly(new AdjustmentRow(
                        delivery.id(), "DXB-MARINA", "MILK-FULL-1L", 24, "RECEIVED", 30, delivery.at()));
    }

    @Test
    void theAuditTrailCannotBeRewritten() {
        log.append(new StockAdjustment(
                Ids.newTimeOrderedId(),
                StoreId.of("DXB-MARINA"),
                Sku.of("MILK-FULL-1L"),
                -2,
                AdjustmentReason.DAMAGED,
                8,
                Instant.parse("2026-09-22T10:00:00Z")));

        assertThatThrownBy(() -> jdbc.sql("UPDATE inventory.stock_adjustment SET delta = -1")
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(
                        () -> jdbc.sql("DELETE FROM inventory.stock_adjustment").update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }
}
