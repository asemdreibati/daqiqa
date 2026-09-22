package io.daqiqa.promise.domain;

import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.SkuQuantity;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * What the customer wants delivered, normalised: one line per SKU (duplicates merged), in SKU order.
 *
 * <p>Normalising once means the calculator, the token's basket hash and checkout all see the same basket however the
 * client listed it.
 */
public record Basket(List<SkuQuantity> lines) {

    /** The API accepts at most this many lines. */
    public static final int MAX_LINES = 50;

    /**
     * @throws InvalidBasketException if the basket is empty, has more than {@value #MAX_LINES} different SKUs, or
     *     merging duplicates takes a SKU over {@value SkuQuantity#MAX_QUANTITY} units
     */
    public Basket {
        Objects.requireNonNull(lines, "lines");
        Map<Sku, Integer> quantities = new TreeMap<>(Comparator.comparing(Sku::value));
        for (SkuQuantity line : lines) {
            quantities.merge(line.sku(), line.quantity(), Math::addExact);
        }
        if (quantities.isEmpty()) {
            throw new InvalidBasketException("A basket needs at least one line");
        }
        if (quantities.size() > MAX_LINES) {
            throw new InvalidBasketException(
                    "A basket holds at most " + MAX_LINES + " different SKUs, got " + quantities.size());
        }
        quantities.forEach((sku, quantity) -> {
            if (quantity > SkuQuantity.MAX_QUANTITY) {
                throw new InvalidBasketException("Once duplicate lines are merged, " + sku + " has " + quantity
                        + " units; at most " + SkuQuantity.MAX_QUANTITY + " are allowed");
            }
        });
        lines = quantities.entrySet().stream()
                .map(entry -> new SkuQuantity(entry.getKey(), entry.getValue()))
                .toList();
    }

    public static Basket of(Collection<SkuQuantity> lines) {
        return new Basket(List.copyOf(lines));
    }

    public int totalUnits() {
        return lines.stream().mapToInt(SkuQuantity::quantity).sum();
    }

    public List<Sku> skus() {
        return lines.stream().map(SkuQuantity::sku).toList();
    }
}
