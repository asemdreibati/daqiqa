package io.daqiqa.inventory.domain;

import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.kernel.StoreId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Units of one SKU held at one store: what goes back on sale when reservations end. */
public record HeldUnits(StoreId storeId, Sku sku, int quantity) {

    public HeldUnits {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(sku, "sku");
        if (quantity < 1) {
            throw new IllegalArgumentException("Held units must be positive, was " + quantity);
        }
    }

    /**
     * Everything the given reservations hold, summed per store and SKU, sorted by store then SKU (the order in which
     * stock rows are locked).
     */
    public static List<HeldUnits> heldBy(Collection<Reservation> reservations) {
        Map<StockKey, Integer> totals = new TreeMap<>();
        for (Reservation reservation : reservations) {
            for (SkuQuantity line : reservation.lines()) {
                totals.merge(new StockKey(reservation.storeId(), line.sku()), line.quantity(), Math::addExact);
            }
        }
        return totals.entrySet().stream()
                .map(total ->
                        new HeldUnits(total.getKey().storeId(), total.getKey().sku(), total.getValue()))
                .toList();
    }

    /** The stock row these units are held in. */
    public StockKey key() {
        return new StockKey(storeId, sku);
    }
}
