package io.daqiqa.inventory.domain;

import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.StoreId;
import java.util.Comparator;
import java.util.Objects;

/**
 * Identifies one stock row: a SKU at a store. Ordered by store, then SKU, which is the order in which every
 * transaction locks stock rows.
 */
public record StockKey(StoreId storeId, Sku sku) implements Comparable<StockKey> {

    private static final Comparator<StockKey> LOCK_ORDER =
            Comparator.comparing((StockKey key) -> key.storeId.value()).thenComparing(key -> key.sku.value());

    public StockKey {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(sku, "sku");
    }

    @Override
    public int compareTo(StockKey other) {
        return LOCK_ORDER.compare(this, other);
    }
}
