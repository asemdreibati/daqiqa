package io.daqiqa.inventory.support;

import io.daqiqa.catalog.api.Product;
import io.daqiqa.catalog.api.ProductCatalog;
import io.daqiqa.kernel.LocalizedText;
import io.daqiqa.kernel.Money;
import io.daqiqa.kernel.Sku;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Stands in for the catalog context: knows exactly the SKUs a test adds. Thread-safe. */
public final class FakeProductCatalog implements ProductCatalog {

    private final Map<Sku, Product> products = new ConcurrentHashMap<>();

    public void add(Sku... skus) {
        for (Sku sku : List.of(skus)) {
            products.put(
                    sku,
                    new Product(sku, LocalizedText.of("Item " + sku, "منتج " + sku), "SNACKS", Money.of(500, "AED")));
        }
    }

    public void clear() {
        products.clear();
    }

    @Override
    public Optional<Product> find(Sku sku) {
        return Optional.ofNullable(products.get(sku));
    }

    @Override
    public Map<Sku, Product> findAll(Collection<Sku> skus) {
        return skus.stream()
                .filter(products::containsKey)
                .distinct()
                .collect(Collectors.toUnmodifiableMap(Function.identity(), products::get));
    }
}
