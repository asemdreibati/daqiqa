package io.daqiqa.inventory.domain;

import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.StoreId;
import java.util.Objects;

/**
 * How many units of one SKU a store has on hand, and how many of them are held for orders.
 *
 * <p>Invariant: {@code 0 <= reserved <= onHand}. {@link #available()} is what can still be sold.
 */
public record StockLevel(StoreId storeId, Sku sku, int onHand, int reserved) {

    public StockLevel {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(sku, "sku");
        if (onHand < 0 || reserved < 0 || reserved > onHand) {
            throw new IllegalArgumentException("Stock of " + sku + " at " + storeId
                    + " must satisfy 0 <= reserved <= on hand, was reserved " + reserved + " of " + onHand);
        }
    }

    /** What a store has of a SKU it never stocked. */
    public static StockLevel none(StoreId storeId, Sku sku) {
        return new StockLevel(storeId, sku, 0, 0);
    }

    public int available() {
        return onHand - reserved;
    }

    /**
     * The level after a stock change from store operations (a delivery, damage, a count correction).
     *
     * @throws StockBelowReservedException if fewer units would be on hand than are already held for orders (which
     *     includes a negative quantity on hand): those units are promised, the change must be investigated first
     */
    public StockLevel adjust(int delta) {
        if (delta == 0) {
            throw new IllegalArgumentException("An adjustment must change the quantity on hand");
        }
        int onHandAfter = Math.addExact(onHand, delta);
        if (onHandAfter < reserved) {
            throw new StockBelowReservedException(this, delta);
        }
        return new StockLevel(storeId, sku, onHandAfter, reserved);
    }
}
