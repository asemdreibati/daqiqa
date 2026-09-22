package io.daqiqa.inventory.domain;

import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.StoreId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One entry of the stock audit trail: who changed what, by how much, and what was left on hand. */
public record StockAdjustment(
        UUID id, StoreId storeId, Sku sku, int delta, AdjustmentReason reason, int onHandAfter, Instant at) {

    public StockAdjustment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(at, "at");
        if (delta == 0) {
            throw new IllegalArgumentException("An adjustment must change the quantity on hand");
        }
        if (onHandAfter < 0) {
            throw new IllegalArgumentException("On hand cannot be negative, was " + onHandAfter);
        }
    }
}
