package io.daqiqa.inventory.domain;

import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.kernel.StoreId;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Outbound port: stock levels per store and SKU. Implemented by a persistence adapter.
 *
 * <p>Lock order. Every method that locks several stock rows locks them in (store, SKU) order. Transactions that
 * share rows therefore queue behind each other instead of deadlocking. Callers must not lock stock rows of a second
 * basket in the same transaction, which would break that order.
 */
public interface StockLevelRepository {

    /**
     * Locks the rows of {@code skus} at {@code storeId} until the end of the current transaction, in SKU order, and
     * returns them. SKUs without a row are not locked and have nothing on hand.
     */
    StoreStock lockForUpdate(StoreId storeId, Collection<Sku> skus);

    /**
     * Locks the stock rows of {@code keys}, across stores, until the end of the current transaction: in one statement,
     * in (store, SKU) order. For work that needs rows beyond one basket; rows that do not exist are not locked.
     */
    void lockForUpdate(Collection<StockKey> keys);

    /** The current levels of {@code skus} at {@code storeId}, without locking. */
    StoreStock find(StoreId storeId, Collection<Sku> skus);

    /** Units that can be sold, per store and SKU, in one round trip. Combinations without a row are absent. */
    Map<StoreId, Map<Sku, Integer>> available(Collection<StoreId> storeIds, Collection<Sku> skus);

    /**
     * Adds {@code lines} to what is reserved at {@code storeId}. Callers lock the rows and check for shortfalls first;
     * the statement checks availability again, as defence in depth.
     *
     * @throws IllegalStateException if some line could not be covered after all (nothing may change under a held
     *     lock, so this is a bug); the transaction must roll back
     */
    void reserve(StoreId storeId, List<SkuQuantity> lines, Instant now);

    /**
     * Puts held units back on sale: locks their rows in (store, SKU) order, then subtracts them from what is reserved.
     *
     * @throws IllegalStateException if fewer units are reserved than returned: the books are inconsistent and the
     *     transaction must roll back rather than make them worse
     */
    void release(List<HeldUnits> units, Instant now);

    /** Locks the level of {@code sku} at {@code storeId}, first creating an empty one if the store never stocked it. */
    StockLevel lockOrCreate(StoreId storeId, Sku sku, Instant now);

    /**
     * Adds {@code delta} to what is on hand. Callers lock the row and check the result first (see
     * {@link StockLevel#adjust(int)}); the statement refuses to go below what is reserved, as defence in depth.
     *
     * @throws IllegalStateException if the row is missing or the change would go below what is reserved
     */
    void adjustOnHand(StoreId storeId, Sku sku, int delta, Instant now);
}
