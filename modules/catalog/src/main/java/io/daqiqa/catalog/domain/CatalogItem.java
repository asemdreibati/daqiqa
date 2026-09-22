package io.daqiqa.catalog.domain;

import io.daqiqa.kernel.LocalizedText;
import io.daqiqa.kernel.Money;
import io.daqiqa.kernel.Sku;
import java.util.Objects;

/** A product in the catalog. Invariant: it always has a strictly positive price. */
public record CatalogItem(Sku sku, LocalizedText name, Category category, Money price) {

    public CatalogItem {
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(price, "price");
        if (price.amountMinor() <= 0) {
            throw new IllegalArgumentException("Price of " + sku + " must be positive, was " + price);
        }
    }
}
