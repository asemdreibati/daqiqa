package io.daqiqa.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.daqiqa.contracts.events.StockAdjustedV1;
import io.daqiqa.inventory.domain.AdjustmentReason;
import io.daqiqa.inventory.domain.StockBelowReservedException;
import io.daqiqa.inventory.domain.StockLevel;
import io.daqiqa.inventory.support.Concurrently;
import io.daqiqa.inventory.support.InventoryIntegrationTest;
import io.daqiqa.inventory.support.InventoryTables.AdjustmentRow;
import io.daqiqa.inventory.support.InventoryTables.StockRow;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class StockServiceTest extends InventoryIntegrationTest {

    @Autowired
    StockService stock;

    @Test
    void aFirstDeliveryCreatesTheStockLevelAndIsRecordedAndAnnounced() {
        stores.add(MARINA);
        catalog.add(MILK);

        StockLevel level = stock.adjust(new AdjustStock(MARINA, MILK, 24, AdjustmentReason.RECEIVED));

        assertThat(level).isEqualTo(new StockLevel(MARINA, MILK, 24, 0));
        assertThat(tables.stockOf(MARINA, MILK)).isEqualTo(new StockRow(24, 0, 1));
        List<AdjustmentRow> audit = tables.adjustments();
        assertThat(audit).hasSize(1);
        AdjustmentRow entry = audit.getFirst();
        assertThat(entry)
                .isEqualTo(
                        new AdjustmentRow(entry.id(), "DXB-MARINA", MILK.value(), 24, "RECEIVED", 24, clock.instant()));
        assertThat(outbox.committedEventsOfType(StockAdjustedV1.class))
                .as("the event id is the audit entry's id: one fact, one id")
                .containsExactly(new StockAdjustedV1(
                        entry.id(), clock.instant(), "DXB-MARINA", MILK.value(), 24, "RECEIVED", 24, 0));
    }

    @Test
    void damageMayRemoveEveryUnitThatIsNotPromisedToAnOrder() {
        stores.add(MARINA);
        catalog.add(MILK);
        tables.stock(MARINA, MILK, 10, 4);

        StockLevel level = stock.adjust(new AdjustStock(MARINA, MILK, -6, AdjustmentReason.DAMAGED));

        assertThat(level).isEqualTo(new StockLevel(MARINA, MILK, 4, 4));
        assertThat(outbox.committedEventsOfType(StockAdjustedV1.class))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.onHandAfter()).isEqualTo(4);
                    assertThat(event.reservedAfter()).isEqualTo(4);
                });
    }

    @Test
    void refusesToLeaveFewerUnitsThanAreReservedAndWritesNothing() {
        stores.add(MARINA);
        catalog.add(MILK);
        tables.stock(MARINA, MILK, 10, 4);

        assertThatThrownBy(() -> stock.adjust(new AdjustStock(MARINA, MILK, -7, AdjustmentReason.CYCLE_COUNT)))
                .isInstanceOf(StockBelowReservedException.class);

        assertThat(tables.stockOf(MARINA, MILK)).isEqualTo(new StockRow(10, 4, 0));
        assertThat(tables.adjustments()).isEmpty();
        assertThat(outbox.committedEvents()).isEmpty();
    }

    @Test
    void refusesToRemoveUnitsOfASkuTheStoreDoesNotHaveAndCreatesNothing() {
        stores.add(MARINA);
        catalog.add(MILK);

        assertThatThrownBy(() -> stock.adjust(new AdjustStock(MARINA, MILK, -1, AdjustmentReason.CORRECTION)))
                .isInstanceOf(StockBelowReservedException.class);

        assertThat(tables.stockOf(MARINA, MILK)).isNull();
    }

    @Test
    void refusesAnUnknownStoreOrAnUnknownSku() {
        stores.add(MARINA);
        catalog.add(MILK);

        assertThatThrownBy(() -> stock.adjust(new AdjustStock(JLT, MILK, 5, AdjustmentReason.RECEIVED)))
                .isInstanceOfSatisfying(
                        StoreNotFoundException.class,
                        e -> assertThat(e.storeId()).isEqualTo(JLT));
        assertThatThrownBy(() -> stock.adjust(new AdjustStock(MARINA, BREAD, 5, AdjustmentReason.RECEIVED)))
                .isInstanceOfSatisfying(
                        UnknownSkuException.class, e -> assertThat(e.sku()).isEqualTo(BREAD));
        assertThat(tables.adjustments()).isEmpty();
        assertThat(outbox.committedEvents()).isEmpty();
    }

    @Test
    void readsTheLevelsOfAKnownStoreOnly() {
        stores.add(MARINA);
        tables.stock(MARINA, MILK, 10, 3);

        assertThat(stock.levels(MARINA, List.of(MILK, BREAD)).level(MILK))
                .isEqualTo(new StockLevel(MARINA, MILK, 10, 3));
        assertThatThrownBy(() -> stock.levels(JLT, List.of(MILK))).isInstanceOf(StoreNotFoundException.class);
    }

    @Test
    void simultaneousFirstDeliveriesOfASkuAllCount() throws Exception {
        stores.add(MARINA);
        catalog.add(MILK);

        Concurrently.run(20, i -> stock.adjust(new AdjustStock(MARINA, MILK, 1, AdjustmentReason.RECEIVED)));

        assertThat(tables.stockOf(MARINA, MILK)).extracting(StockRow::onHand).isEqualTo(20);
        assertThat(tables.adjustments())
                .extracting(AdjustmentRow::onHandAfter)
                .containsExactlyInAnyOrderElementsOf(
                        IntStream.rangeClosed(1, 20).boxed().toList());
    }
}
