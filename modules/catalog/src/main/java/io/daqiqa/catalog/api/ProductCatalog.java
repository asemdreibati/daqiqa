package io.daqiqa.catalog.api;

import io.daqiqa.kernel.Sku;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/** Product lookup for other contexts (ordering prices a basket with it). */
public interface ProductCatalog {

    Optional<Product> find(Sku sku);

    /** Products for the given SKUs in one round trip. Unknown SKUs are absent from the result. */
    Map<Sku, Product> findAll(Collection<Sku> skus);
}
