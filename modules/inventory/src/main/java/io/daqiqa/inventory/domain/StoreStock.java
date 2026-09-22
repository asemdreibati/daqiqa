package io.daqiqa.inventory.domain;

import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.kernel.StoreId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The stock one store has of a set of SKUs, as read at one moment (under row locks when it decides a reservation).
 * SKUs the store has no row for have nothing on hand.
 */
public final class StoreStock {

    private final StoreId storeId;
    private final Map<Sku, StockLevel> levels;

    private StoreStock(StoreId storeId, Map<Sku, StockLevel> levels) {
        this.storeId = storeId;
        this.levels = levels;
    }

    public static StoreStock of(StoreId storeId, Collection<StockLevel> levels) {
        Objects.requireNonNull(storeId, "storeId");
        Map<Sku, StockLevel> bySku = new HashMap<>();
        for (StockLevel level : levels) {
            if (!level.storeId().equals(storeId)) {
                throw new IllegalArgumentException("Stock of " + level.storeId() + " is not stock of " + storeId);
            }
            if (bySku.put(level.sku(), level) != null) {
                throw new IllegalArgumentException("Two stock levels for " + level.sku() + " at " + storeId);
            }
        }
        return new StoreStock(storeId, Map.copyOf(bySku));
    }

    /** The level of {@code sku}; zero on hand when the store does not stock it. */
    public StockLevel level(Sku sku) {
        StockLevel level = levels.get(sku);
        return level != null ? level : StockLevel.none(storeId, sku);
    }

    /**
     * The lines this stock cannot cover, in basket order. A basket is held only when this is empty: all or nothing.
     * Reserved units are not available: they are promised to other orders.
     */
    public List<Shortfall> shortfalls(Collection<SkuQuantity> lines) {
        List<Shortfall> shortfalls = new ArrayList<>();
        for (SkuQuantity line : lines) {
            int available = level(line.sku()).available();
            if (available < line.quantity()) {
                shortfalls.add(new Shortfall(line.sku(), line.quantity(), available));
            }
        }
        return List.copyOf(shortfalls);
    }
}
