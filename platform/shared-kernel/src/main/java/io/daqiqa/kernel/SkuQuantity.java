package io.daqiqa.kernel;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A quantity of one SKU: a cart line, a reservation line, an order line. */
public record SkuQuantity(Sku sku, int quantity) {

    /** Upper bound per line; quick commerce baskets are small and this stops abuse early. */
    public static final int MAX_QUANTITY = 99;

    public SkuQuantity {
        Objects.requireNonNull(sku, "sku");
        if (quantity < 1 || quantity > MAX_QUANTITY) {
            throw new IllegalArgumentException(
                    "Quantity for " + sku + " must be between 1 and " + MAX_QUANTITY + ", was " + quantity);
        }
    }

    public static SkuQuantity of(String sku, int quantity) {
        return new SkuQuantity(Sku.of(sku), quantity);
    }

    /**
     * Merges lines for the same SKU (preserving first-seen order), so that {@code [A×1, B×2, A×3]} becomes
     * {@code [A×4, B×2]}. Every context that accepts carts normalises them with this method.
     */
    public static List<SkuQuantity> merge(Collection<SkuQuantity> lines) {
        Map<Sku, Integer> merged = new LinkedHashMap<>();
        for (SkuQuantity line : lines) {
            merged.merge(line.sku(), line.quantity(), Math::addExact);
        }
        return merged.entrySet().stream()
                .map(e -> new SkuQuantity(e.getKey(), e.getValue()))
                .toList();
    }
}
