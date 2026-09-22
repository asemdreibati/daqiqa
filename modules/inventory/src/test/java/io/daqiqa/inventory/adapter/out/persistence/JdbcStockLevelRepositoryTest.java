package io.daqiqa.inventory.adapter.out.persistence;

import static io.daqiqa.inventory.support.InventoryTables.ARRANGED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.daqiqa.inventory.domain.HeldUnits;
import io.daqiqa.inventory.domain.StockLevel;
import io.daqiqa.inventory.domain.StoreStock;
import io.daqiqa.inventory.support.InventoryTables.StockRow;
import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.kernel.StoreId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class JdbcStockLevelRepositoryTest extends PostgresRepositoryTest {

    private static final StoreId MARINA = StoreId.of("DXB-MARINA");
    private static final StoreId JLT = StoreId.of("DXB-JLT");
    private static final Sku MILK = Sku.of("MILK-FULL-1L");
    private static final Sku BREAD = Sku.of("BREAD-ARABIC-6PK");
    private static final Sku DATES = Sku.of("DATES-KHALAS-1KG");
    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");

    private final JdbcStockLevelRepository repository = new JdbcStockLevelRepository(jdbc);

    @Test
    void readsTheLevelsOfTheRequestedSkusAtOneStore() {
        tables.stock(MARINA, MILK, 10, 3);
        tables.stock(MARINA, BREAD, 5);
        tables.stock(JLT, MILK, 99);

        StoreStock stock = repository.find(MARINA, List.of(MILK, DATES));

        assertThat(stock.level(MILK)).isEqualTo(new StockLevel(MARINA, MILK, 10, 3));
        assertThat(stock.level(DATES)).isEqualTo(StockLevel.none(MARINA, DATES));
    }

    @Test
    void locksTheBasketsRowsUntilTheTransactionEnds() {
        tables.stock(MARINA, MILK, 10);
        tables.stock(MARINA, BREAD, 5);

        transaction.executeWithoutResult(status -> {
            StoreStock locked = repository.lockForUpdate(MARINA, List.of(MILK, BREAD, DATES));
            assertThat(locked.level(MILK).onHand()).isEqualTo(10);
            assertThat(locked.level(DATES)).isEqualTo(StockLevel.none(MARINA, DATES));

            assertLockNotAvailable(() -> inAnotherTransaction(() -> {
                waitForLocksAtMost(Duration.ofMillis(200));
                return repository.lockForUpdate(MARINA, List.of(BREAD));
            }));
        });
        assertThat(inAnotherTransaction(() -> repository.lockForUpdate(MARINA, List.of(BREAD)))
                        .level(BREAD)
                        .onHand())
                .as("free again once the first transaction ended")
                .isEqualTo(5);
    }

    @Test
    void readingLevelsNeverWaitsForLocks() {
        tables.stock(MARINA, MILK, 10);

        transaction.executeWithoutResult(status -> {
            repository.lockForUpdate(MARINA, List.of(MILK));

            StoreStock read = inAnotherTransaction(() -> {
                waitForLocksAtMost(Duration.ofMillis(200));
                return repository.find(MARINA, List.of(MILK));
            });

            assertThat(read.level(MILK).onHand()).isEqualTo(10);
        });
    }

    @Test
    void answersAvailabilityForEveryStoreAndSkuInOneQuery() {
        tables.stock(MARINA, MILK, 10, 4);
        tables.stock(MARINA, BREAD, 2, 2);
        tables.stock(JLT, MILK, 7);
        tables.stock(JLT, DATES, 3);

        Map<StoreId, Map<Sku, Integer>> available = repository.available(List.of(MARINA, JLT), List.of(MILK, BREAD));

        assertThat(available).isEqualTo(Map.of(MARINA, Map.of(MILK, 6, BREAD, 0), JLT, Map.of(MILK, 7)));
    }

    @Test
    void reservingAddsEveryLineToWhatIsReserved() {
        tables.stock(MARINA, MILK, 10, 1);
        tables.stock(MARINA, BREAD, 5);

        repository.reserve(MARINA, List.of(new SkuQuantity(BREAD, 5), new SkuQuantity(MILK, 2)), NOW);

        assertThat(tables.stockOf(MARINA, MILK)).isEqualTo(new StockRow(10, 3, NOW));
        assertThat(tables.stockOf(MARINA, BREAD)).isEqualTo(new StockRow(5, 5, NOW));
    }

    @Test
    void reservingChecksAvailabilityAgainAndRollsBackAPartiallyCoveredBasket() {
        tables.stock(MARINA, MILK, 10);
        tables.stock(MARINA, BREAD, 1);

        assertThatIllegalStateException()
                .isThrownBy(() -> transaction.executeWithoutResult(status ->
                        repository.reserve(MARINA, List.of(new SkuQuantity(MILK, 2), new SkuQuantity(BREAD, 2)), NOW)))
                .withMessageContaining("Could hold only 1 of 2 lines");
        assertThat(tables.stockOf(MARINA, MILK)).isEqualTo(new StockRow(10, 0, ARRANGED_AT));
        assertThat(tables.stockOf(MARINA, BREAD)).isEqualTo(new StockRow(1, 0, ARRANGED_AT));
    }

    @Test
    void releasingReturnsHeldUnitsToSaleAcrossStores() {
        tables.stock(MARINA, MILK, 10, 6);
        tables.stock(MARINA, BREAD, 5, 5);
        tables.stock(JLT, MILK, 3, 3);

        repository.release(
                List.of(new HeldUnits(JLT, MILK, 1), new HeldUnits(MARINA, BREAD, 5), new HeldUnits(MARINA, MILK, 2)),
                NOW);

        assertThat(tables.stockOf(MARINA, MILK)).isEqualTo(new StockRow(10, 4, NOW));
        assertThat(tables.stockOf(MARINA, BREAD)).isEqualTo(new StockRow(5, 0, NOW));
        assertThat(tables.stockOf(JLT, MILK)).isEqualTo(new StockRow(3, 2, NOW));
    }

    @Test
    void refusesToReturnMoreUnitsThanAreReservedOrUnitsOfAMissingRow() {
        tables.stock(MARINA, MILK, 10, 1);

        assertThatIllegalStateException()
                .isThrownBy(() -> repository.release(List.of(new HeldUnits(MARINA, MILK, 2)), NOW))
                .withMessageContaining("reserved stock is inconsistent");
        assertThatIllegalStateException()
                .isThrownBy(() -> repository.release(List.of(new HeldUnits(MARINA, BREAD, 1)), NOW));
        assertThat(tables.stockOf(MARINA, MILK)).isEqualTo(new StockRow(10, 1, ARRANGED_AT));
    }

    @Test
    void createsAnEmptyLevelToLockTheFirstTimeAStoreStocksASku() {
        tables.stock(MARINA, MILK, 10, 4);

        StockLevel created = transaction.execute(status -> repository.lockOrCreate(MARINA, BREAD, NOW));
        StockLevel existing = transaction.execute(status -> repository.lockOrCreate(MARINA, MILK, NOW));

        assertThat(created).isEqualTo(StockLevel.none(MARINA, BREAD));
        assertThat(tables.stockOf(MARINA, BREAD)).isEqualTo(new StockRow(0, 0, NOW));
        assertThat(existing).isEqualTo(new StockLevel(MARINA, MILK, 10, 4));
    }

    @Test
    void adjustsOnHandButNeverBelowWhatIsReserved() {
        tables.stock(MARINA, MILK, 10, 4);

        repository.adjustOnHand(MARINA, MILK, -6, NOW);

        assertThat(tables.stockOf(MARINA, MILK)).isEqualTo(new StockRow(4, 4, NOW));
        assertThatIllegalStateException().isThrownBy(() -> repository.adjustOnHand(MARINA, MILK, -1, NOW));
        assertThatIllegalStateException().isThrownBy(() -> repository.adjustOnHand(MARINA, BREAD, 5, NOW));
    }

    @Test
    void theDatabaseItselfRefusesToReserveMoreThanIsOnHand() {
        tables.stock(MARINA, MILK, 10, 10);

        assertThatThrownBy(() -> jdbc.sql("UPDATE inventory.stock_level SET reserved = 11")
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("stock_level_reserved_within_on_hand");
        assertThatThrownBy(() -> jdbc.sql("UPDATE inventory.stock_level SET reserved = -1")
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
