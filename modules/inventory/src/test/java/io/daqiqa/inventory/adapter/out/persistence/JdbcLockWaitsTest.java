package io.daqiqa.inventory.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.daqiqa.inventory.support.InventoryTables.HeldLock;
import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.StoreId;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class JdbcLockWaitsTest extends PostgresRepositoryTest {

    private static final StoreId MARINA = StoreId.of("DXB-MARINA");
    private static final Sku MILK = Sku.of("MILK-FULL-1L");

    private final JdbcLockWaits lockWaits = new JdbcLockWaits(jdbc);
    private final JdbcStockLevelRepository stockLevels = new JdbcStockLevelRepository(jdbc);

    @Test
    void makesThisTransactionGiveUpWaitingForARowSomeoneElseHolds() {
        tables.stock(MARINA, MILK, 10);

        try (HeldLock checkout = tables.holdStockRowLocked(MARINA, MILK)) {
            transaction.executeWithoutResult(status -> {
                lockWaits.limitTo(Duration.ofMillis(200));

                assertLockNotAvailable(() -> stockLevels.lockForUpdate(MARINA, java.util.List.of(MILK)));
            });
        }
    }

    @Test
    void appliesToItsOwnTransactionOnlySoAPooledConnectionCarriesNothingOver() {
        transaction.executeWithoutResult(status -> {
            lockWaits.limitTo(Duration.ofMillis(200));

            assertThat(lockTimeout()).isEqualTo("200ms");
        });

        transaction.executeWithoutResult(status ->
                assertThat(lockTimeout()).as("back to the server's own setting").isEqualTo("0"));
    }

    private String lockTimeout() {
        return jdbc.sql("SHOW lock_timeout").query(String.class).single();
    }
}
