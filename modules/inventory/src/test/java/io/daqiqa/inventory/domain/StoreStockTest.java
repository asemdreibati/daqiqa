package io.daqiqa.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.kernel.StoreId;
import java.util.List;
import org.junit.jupiter.api.Test;

class StoreStockTest {

    private static final StoreId MARINA = StoreId.of("DXB-MARINA");
    private static final Sku MILK = Sku.of("MILK-FULL-1L");
    private static final Sku BREAD = Sku.of("BREAD-ARABIC-6PK");
    private static final Sku DATES = Sku.of("DATES-KHALAS-1KG");

    private final StoreStock stock =
            StoreStock.of(MARINA, List.of(new StockLevel(MARINA, MILK, 10, 7), new StockLevel(MARINA, BREAD, 5, 0)));

    @Test
    void aSkuTheStoreDoesNotStockHasNothingOnHand() {
        assertThat(stock.level(DATES)).isEqualTo(StockLevel.none(MARINA, DATES));
    }

    @Test
    void aBasketWithinWhatIsAvailableHasNoShortfall() {
        assertThat(stock.shortfalls(List.of(new SkuQuantity(MILK, 3), new SkuQuantity(BREAD, 5))))
                .isEmpty();
    }

    @Test
    void unitsReservedForOtherOrdersAreNotAvailable() {
        assertThat(stock.shortfalls(List.of(new SkuQuantity(MILK, 4)))).containsExactly(new Shortfall(MILK, 4, 3));
    }

    @Test
    void reportsEveryLineThatCannotBeCoveredInBasketOrder() {
        List<SkuQuantity> basket =
                List.of(new SkuQuantity(DATES, 1), new SkuQuantity(BREAD, 2), new SkuQuantity(MILK, 9));

        assertThat(stock.shortfalls(basket)).containsExactly(new Shortfall(DATES, 1, 0), new Shortfall(MILK, 9, 3));
    }

    @Test
    void holdsOnlyTheStockOfItsOwnStoreOnceEach() {
        StoreId jlt = StoreId.of("DXB-JLT");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> StoreStock.of(MARINA, List.of(new StockLevel(jlt, MILK, 1, 0))));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> StoreStock.of(
                        MARINA, List.of(new StockLevel(MARINA, MILK, 1, 0), new StockLevel(MARINA, MILK, 2, 0))));
    }
}
