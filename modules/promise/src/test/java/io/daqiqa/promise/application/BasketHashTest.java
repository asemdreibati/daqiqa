package io.daqiqa.promise.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.daqiqa.kernel.SkuQuantity;
import java.util.List;
import org.junit.jupiter.api.Test;

class BasketHashTest {

    @Test
    void hashesTheCanonicalBasketAsDocumented() throws Exception {
        String hash = BasketHash.of(List.of(SkuQuantity.of("MILK-FULL-1L", 2), SkuQuantity.of("BREAD-ARABIC-6PK", 1)));

        assertThat(hash).isEqualTo(Tokens.sha256("BREAD-ARABIC-6PK:1,MILK-FULL-1L:2"));
    }

    @Test
    void theSameBasketHasTheSameHashHoweverItIsListed() {
        String hash = BasketHash.of(List.of(SkuQuantity.of("MILK-FULL-1L", 2), SkuQuantity.of("BREAD-ARABIC-6PK", 1)));

        assertThat(BasketHash.of(List.of(
                        SkuQuantity.of("BREAD-ARABIC-6PK", 1),
                        SkuQuantity.of("MILK-FULL-1L", 1),
                        SkuQuantity.of("MILK-FULL-1L", 1))))
                .isEqualTo(hash);
        assertThat(BasketHash.of(List.of(SkuQuantity.of("MILK-FULL-1L", 2)))).isNotEqualTo(hash);
    }
}
