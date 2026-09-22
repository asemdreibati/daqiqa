package io.daqiqa.ordering.domain;

import io.daqiqa.kernel.Money;
import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.SkuQuantity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Prices a basket at the catalog's current prices. Pure: the caller fetches the prices, so pricing does no I/O and
 * its rules are tested exhaustively on their own.
 */
public final class BasketPricing {

    private BasketPricing() {}

    /**
     * @param basket the merged basket (one line per SKU), in the order the customer built it
     * @param catalog current names and prices by SKU; may contain SKUs that are not in the basket
     * @return priced lines numbered from 1 in basket order and their total, or the SKUs the catalog does not know
     * @throws IllegalArgumentException if the basket is empty, repeats a SKU or mixes currencies
     */
    public static PricingResult price(List<SkuQuantity> basket, Map<Sku, ProductPrice> catalog) {
        if (basket.isEmpty()) {
            throw new IllegalArgumentException("Cannot price an empty basket");
        }
        if (basket.stream().map(SkuQuantity::sku).distinct().count() != basket.size()) {
            throw new IllegalArgumentException("Merge the basket before pricing it: a SKU appears twice");
        }
        List<Sku> unknown = basket.stream()
                .map(SkuQuantity::sku)
                .filter(sku -> !catalog.containsKey(sku))
                .toList();
        if (!unknown.isEmpty()) {
            return new PricingResult.UnknownSkus(unknown);
        }
        List<OrderLine> lines = new ArrayList<>(basket.size());
        for (SkuQuantity item : basket) {
            ProductPrice product = catalog.get(item.sku());
            lines.add(
                    new OrderLine(lines.size() + 1, item.sku(), product.name(), item.quantity(), product.unitPrice()));
        }
        return new PricingResult.Priced(lines, totalOf(lines));
    }

    /** Sum of the line totals. {@link Money#plus} refuses to mix currencies, so a mixed basket fails loudly. */
    static Money totalOf(List<OrderLine> lines) {
        return lines.stream().map(OrderLine::lineTotal).reduce(Money::plus).orElseThrow();
    }
}
