package io.daqiqa.inventory.api;

import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.kernel.StoreId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Sellable quantity (on hand minus reserved) per store and SKU, at one point in time. Missing entries mean zero. */
public record StockSnapshot(Map<StoreId, Map<Sku, Integer>> available) {

    public StockSnapshot {
        available = Map.copyOf(available.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> Map.copyOf(e.getValue()))));
    }

    public int available(StoreId store, Sku sku) {
        return available.getOrDefault(store, Map.of()).getOrDefault(sku, 0);
    }

    /** Whether {@code store} currently has enough of every line. */
    public boolean canFulfil(StoreId store, Collection<SkuQuantity> lines) {
        return shortages(store, lines).isEmpty();
    }

    /** Lines {@code store} cannot currently cover, with what it does have. */
    public List<Shortage> shortages(StoreId store, Collection<SkuQuantity> lines) {
        List<Shortage> shortages = new ArrayList<>();
        for (SkuQuantity line : lines) {
            int available = available(store, line.sku());
            if (available < line.quantity()) {
                shortages.add(new Shortage(line.sku(), line.quantity(), available));
            }
        }
        return List.copyOf(shortages);
    }
}
