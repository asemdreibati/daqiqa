package io.daqiqa.ordering.testsupport;

import static io.daqiqa.ordering.testsupport.FakeProductCatalog.BREAD;
import static io.daqiqa.ordering.testsupport.FakeProductCatalog.MILK;
import static io.daqiqa.ordering.testsupport.FakeProductCatalog.POWERBANK;

import io.daqiqa.kernel.CustomerId;
import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.kernel.OrderId;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.kernel.StoreId;
import io.daqiqa.ordering.OrderingTestApplication;
import io.daqiqa.ordering.application.PlaceOrderCommand;
import io.daqiqa.ordering.domain.PaymentMethod;
import io.daqiqa.promise.api.DeliveryTier;
import io.daqiqa.testing.postgres.PostgresTestDatabase;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The ordering module wired by Spring against real PostgreSQL (its schema and the outbox's), with the other modules'
 * APIs faked. Every test starts from the same world: a store with milk, bread and power banks, and the clock at
 * {@link MutableClock#START}.
 */
@Tag("integration")
@SpringBootTest(classes = OrderingTestApplication.class)
@Import(CheckoutTestPorts.class)
public abstract class OrderingIntegrationTest {

    protected static final StoreId STORE = StoreId.of("DXB-MARINA");
    protected static final CustomerId CUSTOMER = CustomerId.of("0192f7c4-7c1e-7cc2-9d3a-2f1a6c0e9b11");
    protected static final GeoPoint HOME = GeoPoint.of(25.0772, 55.1390);
    protected static final String KEY = "0192f7c4-aaaa-7cc2-9d3a-000000000001";

    private static final PostgresTestDatabase DB = PostgresTestDatabase.shared()
            .migrate("classpath:db/migration/messaging", "classpath:db/migration/ordering");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.registerWith(registry);
    }

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected FakeInventory inventory;

    @Autowired
    protected FakePromiseTokenVerifier promises;

    @Autowired
    protected FakeProductCatalog catalog;

    @Autowired
    protected ScriptedPaymentGateway payments;

    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    void freshWorld() {
        DB.truncateSchemas("ordering", "messaging");
        clock.set(MutableClock.START);
        promises.reset();
        payments.reset();
        catalog.reset();
        List.of(MILK, BREAD, POWERBANK).forEach(catalog::add);
        inventory.reset();
        inventory.stock(STORE, MILK.sku(), 10);
        inventory.stock(STORE, BREAD.sku(), 10);
        inventory.stock(STORE, POWERBANK.sku(), 20);
    }

    /** A checkout of {@code basket}, under a promise just issued for exactly that basket. */
    protected PlaceOrderCommand checkoutOf(PaymentMethod paymentMethod, SkuQuantity... basket) {
        List<SkuQuantity> lines = List.of(basket);
        String token = promises.issue(STORE, DeliveryTier.EXPRESS_15, 12, lines, HOME);
        return new PlaceOrderCommand(CUSTOMER, token, lines, HOME, paymentMethod);
    }

    protected static SkuQuantity line(io.daqiqa.catalog.api.Product product, int quantity) {
        return new SkuQuantity(product.sku(), quantity);
    }

    protected long rows(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    protected Optional<String> orderStatus(OrderId id) {
        return jdbc.sql("SELECT status FROM ordering.customer_order WHERE id = :id")
                .param("id", id.value())
                .query(String.class)
                .optional();
    }

    /** Types of the events in the outbox, in append order. */
    protected List<String> outboxEventTypes() {
        return jdbc.sql("SELECT type FROM messaging.outbox_event ORDER BY seq")
                .query(String.class)
                .list();
    }
}
