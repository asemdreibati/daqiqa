package io.daqiqa.catalog.domain;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import io.daqiqa.kernel.LocalizedText;
import io.daqiqa.kernel.Money;
import io.daqiqa.kernel.Sku;
import org.junit.jupiter.api.Test;

class CatalogItemTest {

    private static final LocalizedText NAME = LocalizedText.of("Bananas 1kg", "موز 1 كغ");

    @Test
    void acceptsAPositivePrice() {
        assertThatNoException()
                .isThrownBy(() ->
                        new CatalogItem(Sku.of("BANANA-1KG"), NAME, Category.FRUITS_VEGETABLES, Money.of(650, "AED")));
    }

    @Test
    void rejectsFreeOrNegativelyPricedItems() {
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        new CatalogItem(Sku.of("BANANA-1KG"), NAME, Category.FRUITS_VEGETABLES, Money.of(0, "AED")));
    }
}
