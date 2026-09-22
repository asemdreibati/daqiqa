package io.daqiqa.catalog.domain;

import io.daqiqa.kernel.Sku;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port: where catalog items are stored. Implemented by a persistence adapter. */
public interface CatalogItemRepository {

    Optional<CatalogItem> findBySku(Sku sku);

    List<CatalogItem> findAllBySku(Collection<Sku> skus);

    /**
     * Keyset pagination: up to {@code limit} items with a SKU greater than {@code after}, in SKU order, optionally in
     * one category. Unlike OFFSET, cost does not grow with the page number and pages never skip or repeat rows when
     * the table changes between requests.
     */
    List<CatalogItem> browse(@Nullable Category category, @Nullable Sku after, int limit);
}
