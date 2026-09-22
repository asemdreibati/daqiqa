package io.daqiqa.promise.domain;

import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.StoreId;
import io.daqiqa.promise.api.DeliveryTier;
import java.util.List;
import java.util.Objects;

/**
 * "Remove these items to get it faster": without {@code skus}, {@code storeId} can deliver the rest of the basket in
 * {@code etaMinutes}.
 *
 * @param skus the items to remove, in SKU order; never the whole basket
 */
public record Suggestion(StoreId storeId, List<Sku> skus, int etaMinutes, DeliveryTier tier) {

    public Suggestion {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(tier, "tier");
        skus = List.copyOf(skus);
        if (skus.isEmpty()) {
            throw new IllegalArgumentException("A suggestion removes at least one item");
        }
    }
}
