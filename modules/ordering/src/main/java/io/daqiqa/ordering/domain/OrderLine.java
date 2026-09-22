package io.daqiqa.ordering.domain;

import io.daqiqa.kernel.LocalizedText;
import io.daqiqa.kernel.Money;
import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.SkuQuantity;
import java.util.Objects;

/**
 * One line of an order. The name and unit price are snapshots taken when the order was priced: a later change in the
 * catalog never changes what a customer bought or what they owe.
 *
 * @param lineNo position in the basket, from 1
 */
public record OrderLine(int lineNo, Sku sku, LocalizedText name, int quantity, Money unitPrice) {

    public OrderLine {
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(unitPrice, "unitPrice");
        if (lineNo < 1) {
            throw new IllegalArgumentException("Line numbers start at 1, was " + lineNo);
        }
        if (quantity < 1 || quantity > SkuQuantity.MAX_QUANTITY) {
            throw new IllegalArgumentException(
                    "Quantity of " + sku + " must be between 1 and " + SkuQuantity.MAX_QUANTITY + ", was " + quantity);
        }
        if (unitPrice.amountMinor() <= 0) {
            throw new IllegalArgumentException("Unit price of " + sku + " must be positive, was " + unitPrice);
        }
    }

    /** Unit price times quantity. */
    public Money lineTotal() {
        return unitPrice.times(quantity);
    }
}
