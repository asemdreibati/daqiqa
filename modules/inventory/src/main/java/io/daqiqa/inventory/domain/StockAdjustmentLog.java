package io.daqiqa.inventory.domain;

/** Outbound port: the append-only audit trail of stock adjustments. */
public interface StockAdjustmentLog {

    void append(StockAdjustment adjustment);
}
