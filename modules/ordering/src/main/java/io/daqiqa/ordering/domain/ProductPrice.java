package io.daqiqa.ordering.domain;

import io.daqiqa.kernel.LocalizedText;
import io.daqiqa.kernel.Money;
import io.daqiqa.kernel.Sku;
import java.util.Objects;

/** What the catalog says a product is called and costs, at the moment a basket is priced. */
public record ProductPrice(Sku sku, LocalizedText name, Money unitPrice) {

    public ProductPrice {
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(unitPrice, "unitPrice");
    }
}
