package io.daqiqa.ordering.domain;

import io.daqiqa.kernel.Money;
import io.daqiqa.kernel.Sku;
import java.util.List;
import java.util.Objects;

/** Outcome of pricing a basket. An unknown SKU is an expected answer, not an exception. */
public sealed interface PricingResult {

    /** Every line priced; {@code total} is the sum of the line totals, in the basket's single currency. */
    record Priced(List<OrderLine> lines, Money total) implements PricingResult {

        public Priced {
            lines = List.copyOf(lines);
            Objects.requireNonNull(total, "total");
        }
    }

    /** These SKUs are not in the catalog, so the basket cannot be sold; listed in basket order. */
    record UnknownSkus(List<Sku> skus) implements PricingResult {

        public UnknownSkus {
            skus = List.copyOf(skus);
            if (skus.isEmpty()) {
                throw new IllegalArgumentException("At least one SKU is unknown");
            }
        }
    }
}
