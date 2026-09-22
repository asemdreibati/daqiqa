package io.daqiqa.promise.domain;

import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.kernel.StoreId;

/** Sellable units per store and SKU, as read for one promise. Combinations that were not read have none. */
@FunctionalInterface
public interface Stock {

    /** Nothing anywhere: what a location no store covers can be offered. */
    Stock NONE = (store, sku) -> 0;

    int available(StoreId store, Sku sku);

    /** Whether {@code store} has the whole quantity of {@code line}. */
    default boolean covers(StoreId store, SkuQuantity line) {
        return available(store, line.sku()) >= line.quantity();
    }
}
