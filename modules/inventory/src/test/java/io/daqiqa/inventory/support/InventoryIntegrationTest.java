package io.daqiqa.inventory.support;

import io.daqiqa.inventory.InventoryTestApplication;
import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.StoreId;
import io.daqiqa.testing.postgres.PostgresTestDatabase;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Base class of tests that run the module's Spring wiring (transactions, proxies, configuration) against real
 * PostgreSQL. Every test starts from empty inventory tables, a reset clock and fakes that know nothing.
 */
@Tag("integration")
@SpringBootTest(classes = InventoryTestApplication.class)
@Import(IntegrationTestBeans.class)
public abstract class InventoryIntegrationTest {

    protected static final StoreId MARINA = StoreId.of("DXB-MARINA");
    protected static final StoreId JLT = StoreId.of("DXB-JLT");
    protected static final Sku MILK = Sku.of("MILK-FULL-1L");
    protected static final Sku BREAD = Sku.of("BREAD-ARABIC-6PK");
    protected static final Sku WATER = Sku.of("WATER-500ML-12PK");

    @Autowired
    protected InventoryTables tables;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected FakeStoreDirectory stores;

    @Autowired
    protected FakeProductCatalog catalog;

    @Autowired
    protected TransactionalRecordingOutbox outbox;

    @Autowired
    protected MeterRegistry meters;

    @BeforeEach
    void startFromAnEmptyInventory() {
        PostgresTestDatabase.shared().truncateSchemas("inventory");
        clock.reset();
        stores.clear();
        catalog.clear();
        outbox.clear();
    }

    /** Current value of a counter; the registry lives as long as the cached context, so compare before and after. */
    protected double counter(String name, String tag, String value) {
        Counter counter = meters.find(name).tag(tag, value).counter();
        return counter == null ? 0 : counter.count();
    }
}
