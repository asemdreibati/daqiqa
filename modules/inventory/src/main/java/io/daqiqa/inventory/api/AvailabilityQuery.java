package io.daqiqa.inventory.api;

import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.StoreId;
import java.util.Collection;

/**
 * Fast read of sellable stock, used on the promise hot path.
 *
 * <p>The answer may be a few milliseconds stale. That is fine for showing a promise, never for guaranteeing one:
 * the guarantee is {@link InventoryReservations#reserve(ReservationRequest)}.
 */
public interface AvailabilityQuery {

    /** One round trip for every store × SKU combination requested. */
    StockSnapshot availability(Collection<StoreId> stores, Collection<Sku> skus);
}
