package io.daqiqa.ordering.domain;

import static io.daqiqa.ordering.domain.Orders.BREAD;
import static io.daqiqa.ordering.domain.Orders.MILK;
import static io.daqiqa.ordering.domain.Orders.aed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.daqiqa.kernel.LocalizedText;
import io.daqiqa.kernel.Money;
import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.SkuQuantity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

class BasketPricingTest {

    private static final Map<Sku, ProductPrice> CATALOG = Map.of(MILK.sku(), MILK, BREAD.sku(), BREAD);

    @Test
    void pricesEachLineAtTheCatalogPriceAndKeepsTheBilingualName() {
        PricingResult result =
                BasketPricing.price(List.of(new SkuQuantity(BREAD.sku(), 3), new SkuQuantity(MILK.sku(), 2)), CATALOG);

        assertThat(result)
                .isEqualTo(new PricingResult.Priced(
                        List.of(
                                new OrderLine(1, BREAD.sku(), BREAD.name(), 3, aed(300)),
                                new OrderLine(2, MILK.sku(), MILK.name(), 2, aed(695))),
                        aed(3 * 300 + 2 * 695)));
    }

    @Test
    void listsEverySkuTheCatalogDoesNotKnowInBasketOrder() {
        Sku ghost = Sku.of("GHOST-1");
        Sku phantom = Sku.of("PHANTOM-2");

        PricingResult result = BasketPricing.price(
                List.of(new SkuQuantity(ghost, 1), new SkuQuantity(MILK.sku(), 1), new SkuQuantity(phantom, 1)),
                CATALOG);

        assertThat(result).isEqualTo(new PricingResult.UnknownSkus(List.of(ghost, phantom)));
    }

    @Test
    void refusesAnEmptyOrUnmergedBasket() {
        assertThatIllegalArgumentException().isThrownBy(() -> BasketPricing.price(List.of(), CATALOG));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> BasketPricing.price(
                        List.of(new SkuQuantity(MILK.sku(), 1), new SkuQuantity(MILK.sku(), 2)), CATALOG));
    }

    @Test
    void refusesToAddUpPricesInDifferentCurrencies() {
        ProductPrice riyalBread = new ProductPrice(BREAD.sku(), BREAD.name(), Money.of(300, "SAR"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> BasketPricing.price(
                        List.of(new SkuQuantity(MILK.sku(), 1), new SkuQuantity(BREAD.sku(), 1)),
                        Map.of(MILK.sku(), MILK, BREAD.sku(), riyalBread)));
    }

    /** Whatever the basket, the lines follow it one to one and the total is the sum of quantity times unit price. */
    @Property
    void theTotalIsTheSumOfQuantityTimesUnitPrice(
            @ForAll @Size(min = 1, max = 50) List<@IntRange(min = 1, max = 99) Integer> quantities,
            @ForAll @Size(min = 50, max = 50) List<@IntRange(min = 1, max = 1_000_000) Integer> prices) {
        List<SkuQuantity> basket = new ArrayList<>();
        Map<Sku, ProductPrice> catalog = new HashMap<>();
        long expectedTotal = 0;
        for (int i = 0; i < quantities.size(); i++) {
            Sku sku = Sku.of("SKU-" + i);
            basket.add(new SkuQuantity(sku, quantities.get(i)));
            catalog.put(sku, new ProductPrice(sku, LocalizedText.of("Item " + i, "صنف " + i), aed(prices.get(i))));
            expectedTotal += (long) quantities.get(i) * prices.get(i);
        }

        PricingResult.Priced priced = (PricingResult.Priced) BasketPricing.price(basket, catalog);

        assertThat(priced.total()).isEqualTo(aed(expectedTotal));
        assertThat(priced.lines())
                .extracting(OrderLine::lineNo)
                .containsExactlyElementsOf(
                        IntStream.rangeClosed(1, basket.size()).boxed().toList());
        assertThat(priced.lines())
                .extracting(line -> new SkuQuantity(line.sku(), line.quantity()))
                .containsExactlyElementsOf(basket);
    }
}
