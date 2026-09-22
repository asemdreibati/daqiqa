package io.daqiqa.network.application;

import static io.daqiqa.network.StoreFixtures.DUBAI_MARINA;
import static io.daqiqa.network.StoreFixtures.JLT;
import static io.daqiqa.network.StoreFixtures.openStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.kernel.StoreId;
import io.daqiqa.network.NetworkDatabase;
import io.daqiqa.network.NetworkTestApplication;
import io.daqiqa.network.api.CoverageIndex;
import io.daqiqa.network.api.DarkStore;
import io.daqiqa.network.api.StoreCoverage;
import io.daqiqa.network.api.StoreDirectory;
import io.daqiqa.network.api.StoreStatus;
import io.daqiqa.testing.postgres.PostgresTestDatabase;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The whole network module on real PostgreSQL: the index is built from the database at startup, and a committed
 * status change is visible to the coverage index and the store directory as soon as the use case returns.
 */
@Tag("integration")
@SpringBootTest(classes = NetworkTestApplication.class)
class StoreStatusChangeIntegrationTest {

    private static final int WRITERS = 6;
    private static final StoreId SEEDED = StoreId.of("DXB-SEEDED");
    private static final StoreId JLT_STORE = StoreId.of("DXB-JLT");

    /** Runs before the application context starts, so the seeded store is in the database when the index is built. */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresTestDatabase db = NetworkDatabase.migrated();
        db.truncateSchemas("network");
        NetworkDatabase.insert(JdbcClient.create(db.dataSource()), openStore(SEEDED.value(), DUBAI_MARINA, 3_000));
        db.registerWith(registry);
    }

    @Autowired
    StoreService stores;

    @Autowired
    CoverageIndex coverage;

    @Autowired
    StoreDirectory directory;

    @Autowired
    NetworkView view;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate transactions;

    @BeforeEach
    void openJltStore() {
        jdbc.sql("DELETE FROM network.dark_store WHERE id = :id")
                .param("id", JLT_STORE.value())
                .update();
        NetworkDatabase.insert(jdbc, openStore(JLT_STORE.value(), JLT, 3_000));
        view.refresh();
    }

    @Test
    void buildsTheCoverageIndexFromTheDatabaseAtStartup() {
        assertThat(directory.find(SEEDED)).isPresent();
        assertThat(servedAt(DUBAI_MARINA)).contains(SEEDED);
    }

    @Test
    void aPausedStoreStopsGettingPromisesAsSoonAsTheChangeReturns() {
        assertThat(servedAt(JLT)).contains(JLT_STORE);

        stores.changeStatus(JLT_STORE, StoreStatus.PAUSED);

        assertThat(servedAt(JLT)).doesNotContain(JLT_STORE);
        assertThat(directory.find(JLT_STORE)).map(DarkStore::status).contains(StoreStatus.PAUSED);

        stores.changeStatus(JLT_STORE, StoreStatus.BUSY);

        assertThat(servedAt(JLT)).contains(JLT_STORE);
        assertThat(stores.get(JLT_STORE).status()).isEqualTo(StoreStatus.BUSY);
    }

    @Test
    void aStatusChangeThatIsRolledBackNeverReachesTheIndex() {
        transactions.executeWithoutResult(transaction -> {
            stores.changeStatus(JLT_STORE, StoreStatus.CLOSED);
            assertThat(servedAt(JLT)).as("not committed yet").contains(JLT_STORE);
            transaction.setRollbackOnly();
        });

        assertThat(servedAt(JLT)).contains(JLT_STORE);
        assertThat(stores.get(JLT_STORE).status()).isEqualTo(StoreStatus.OPEN);
    }

    /**
     * Real transactions, row locks and after-commit callbacks: many requests change the same store while the periodic
     * reload runs, and the in-memory view ends up exactly as the database is.
     */
    @Test
    void concurrentStatusChangesAndReloadsLeaveTheViewEqualToTheDatabase() throws Exception {
        StoreId hot = StoreId.of("DXB-HOT");
        jdbc.sql("DELETE FROM network.dark_store WHERE id = :id")
                .param("id", hot.value())
                .update();
        NetworkDatabase.insert(jdbc, openStore(hot.value(), JLT, 3_000));
        view.refresh();
        ExecutorService threads = Executors.newFixedThreadPool(WRITERS + 1);
        try {
            for (int round = 0; round < 3; round++) {
                CountDownLatch start = new CountDownLatch(1);
                AtomicBoolean changing = new AtomicBoolean(true);
                List<Future<?>> writers = new ArrayList<>();
                for (int writer = 0; writer < WRITERS; writer++) {
                    int offset = writer + round;
                    writers.add(threads.submit(() -> {
                        start.await();
                        for (int i = 0; i < 20; i++) {
                            stores.changeStatus(hot, StoreStatus.values()[(i + offset) % StoreStatus.values().length]);
                        }
                        return null;
                    }));
                }
                Future<?> reloader = threads.submit(() -> {
                    start.await();
                    do {
                        view.refresh();
                    } while (changing.get());
                    return null;
                });
                start.countDown();
                for (Future<?> writer : writers) {
                    writer.get(1, TimeUnit.MINUTES);
                }
                changing.set(false);
                reloader.get(1, TimeUnit.MINUTES);

                assertThat(directory.find(hot))
                        .map(DarkStore::status)
                        .as("round %d", round)
                        .contains(stores.get(hot).status());
            }
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    void changingTheStatusOfAnUnknownStoreFails() {
        assertThatExceptionOfType(StoreNotFoundException.class)
                .isThrownBy(() -> stores.changeStatus(StoreId.of("DXB-NOWHERE"), StoreStatus.OPEN));
    }

    private List<StoreId> servedAt(GeoPoint point) {
        return coverage.storesServing(point).stream()
                .map(StoreCoverage::storeId)
                .toList();
    }
}
