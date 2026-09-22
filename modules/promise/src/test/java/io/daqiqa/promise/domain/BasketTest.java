package io.daqiqa.promise.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.SkuQuantity;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class BasketTest {

    @Test
    void mergesDuplicateLinesAndSortsBySku() {
        Basket basket = Basket.of(List.of(
                SkuQuantity.of("MILK-FULL-1L", 1),
                SkuQuantity.of("BREAD-ARABIC-6PK", 2),
                SkuQuantity.of("MILK-FULL-1L", 3)));

        assertThat(basket.lines())
                .containsExactly(SkuQuantity.of("BREAD-ARABIC-6PK", 2), SkuQuantity.of("MILK-FULL-1L", 4));
        assertThat(basket.skus()).containsExactly(Sku.of("BREAD-ARABIC-6PK"), Sku.of("MILK-FULL-1L"));
        assertThat(basket.totalUnits()).isEqualTo(6);
    }

    @Test
    void anEmptyBasketCannotBePromised() {
        assertThatExceptionOfType(InvalidBasketException.class).isThrownBy(() -> Basket.of(List.of()));
    }

    @Test
    void holdsAtMostFiftyDifferentSkus() {
        List<SkuQuantity> fifty = IntStream.range(0, Basket.MAX_LINES)
                .mapToObj(i -> SkuQuantity.of("SKU-" + i, 1))
                .toList();

        assertThat(Basket.of(fifty).lines()).hasSize(Basket.MAX_LINES);
        assertThatExceptionOfType(InvalidBasketException.class)
                .isThrownBy(() -> Basket.of(IntStream.rangeClosed(0, Basket.MAX_LINES)
                        .mapToObj(i -> SkuQuantity.of("SKU-" + i, 1))
                        .toList()));
    }

    @Test
    void mergedDuplicatesMustStayWithinTheLimitPerSku() {
        assertThatExceptionOfType(InvalidBasketException.class)
                .isThrownBy(() ->
                        Basket.of(List.of(SkuQuantity.of("MILK-FULL-1L", 60), SkuQuantity.of("MILK-FULL-1L", 40))))
                .withMessageContaining("MILK-FULL-1L has 100 units");
    }
}
