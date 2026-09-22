package io.daqiqa.inventory.adapter.out.persistence;

import static io.daqiqa.inventory.adapter.out.persistence.SqlValues.timestamp;

import io.daqiqa.inventory.domain.StockAdjustment;
import io.daqiqa.inventory.domain.StockAdjustmentLog;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcStockAdjustmentLog implements StockAdjustmentLog {

    private final JdbcClient jdbc;

    JdbcStockAdjustmentLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(StockAdjustment adjustment) {
        jdbc.sql("""
                        INSERT INTO inventory.stock_adjustment
                               (id, store_id, sku, delta, reason, on_hand_after, created_at)
                        VALUES (:id, :storeId, :sku, :delta, :reason, :onHandAfter, :at)""")
                .param("id", adjustment.id())
                .param("storeId", adjustment.storeId().value())
                .param("sku", adjustment.sku().value())
                .param("delta", adjustment.delta())
                .param("reason", adjustment.reason().name())
                .param("onHandAfter", adjustment.onHandAfter())
                .param("at", timestamp(adjustment.at()))
                .update();
    }
}
