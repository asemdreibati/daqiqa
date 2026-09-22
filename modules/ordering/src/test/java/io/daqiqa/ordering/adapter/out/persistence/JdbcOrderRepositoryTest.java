package io.daqiqa.ordering.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.daqiqa.kernel.CustomerId;
import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.kernel.LocalizedText;
import io.daqiqa.kernel.Money;
import io.daqiqa.kernel.OrderId;
import io.daqiqa.kernel.PromiseId;
import io.daqiqa.kernel.ReservationId;
import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.kernel.StoreId;
import io.daqiqa.ordering.domain.BasketPricing;
import io.daqiqa.ordering.domain.Order;
import io.daqiqa.ordering.domain.OrderStatus;
import io.daqiqa.ordering.domain.PaymentMethod;
import io.daqiqa.ordering.domain.PricingResult;
import io.daqiqa.ordering.domain.ProductPrice;
import io.daqiqa.ordering.domain.PromiseTerms;
import io.daqiqa.testing.postgres.PostgresTestDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
class JdbcOrderRepositoryTest {

    private static final PostgresTestDatabase DB =
            PostgresTestDatabase.shared().migrate("classpath:db/migration/ordering");
    private static final Instant NOW = Instant.parse("2026-09-22T12:00:00.123Z");
    private static final ProductPrice MILK = new ProductPrice(
            Sku.of("MILK-FULL-1L"),
            LocalizedText.of("Full Cream Milk 1L", "حليب كامل الدسم 1 لتر"),
            Money.of(695, "AED"));
    private static final ProductPrice DATES = new ProductPrice(
            Sku.of("DATES-KHALAS-1KG"), LocalizedText.of("Khalas Dates 1kg", "تمر خلاص 1 كغ"), Money.of(3_500, "AED"));

    private final JdbcClient jdbc = JdbcClient.create(DB.dataSource());
    private final TransactionTemplate transaction =
            new TransactionTemplate(new DataSourceTransactionManager(DB.dataSource()));
    private final JdbcOrderRepository orders = new JdbcOrderRepository(jdbc);

    @BeforeEach
    void emptySchema() {
        DB.truncateSchemas("ordering");
    }

    @Test
    void readsAnOrderBackExactlyAsItWasStoredWithItsSnapshottedLines() {
        Order order = anOrder(PaymentMethod.CARD, NOW);

        orders.insert(order);

        assertThat(orders.find(order.id())).contains(order);
        assertThat(jdbc.sql("SELECT deliver_by FROM ordering.customer_order")
                        .query((rs, row) ->
                                rs.getObject(1, OffsetDateTime.class).toInstant())
                        .single())
                .isEqualTo(NOW.plus(Duration.ofMinutes(12)));
    }

    @Test
    void findsNothingForAnUnknownOrder() {
        assertThat(orders.find(OrderId.newId())).isEmpty();
        assertThat(inTransaction(() -> orders.lock(OrderId.newId()))).isEmpty();
    }

    @Test
    void changesTheStatusOnlyFromTheStatusTheCallerSaw() {
        Order order = anOrder(PaymentMethod.CARD, NOW);
        orders.insert(order);

        orders.updateStatus(order.confirmPayment(), OrderStatus.AWAITING_PAYMENT, NOW.plusSeconds(3));

        assertThat(orders.find(order.id())).map(Order::status).contains(OrderStatus.CONFIRMED);
        assertThatIllegalStateException()
                .isThrownBy(() -> orders.updateStatus(order.abandon(), OrderStatus.AWAITING_PAYMENT, NOW));
    }

    @Test
    void findsOrdersStillAwaitingPaymentPlacedByTheCutoffOldestFirst() {
        Order oldest = anOrder(PaymentMethod.CARD, NOW.minus(Duration.ofMinutes(10)));
        Order older = anOrder(PaymentMethod.CARD, NOW.minus(Duration.ofMinutes(6)));
        Order recent = anOrder(PaymentMethod.CARD, NOW.minus(Duration.ofMinutes(1)));
        Order confirmed = anOrder(PaymentMethod.CASH_ON_DELIVERY, NOW.minus(Duration.ofMinutes(20)));
        List.of(recent, older, confirmed, oldest).forEach(orders::insert);
        Instant cutoff = NOW.minus(Duration.ofMinutes(5));

        assertThat(orders.findAwaitingPaymentPlacedBy(cutoff, 10)).containsExactly(oldest.id(), older.id());
        assertThat(orders.findAwaitingPaymentPlacedBy(cutoff, 1)).containsExactly(oldest.id());
    }

    @Test
    void theSweeperSkipsAnOrderThatACheckoutIsSettlingInsteadOfWaitingForIt() throws Exception {
        Order order = anOrder(PaymentMethod.CARD, NOW);
        orders.insert(order);

        try (Connection checkout = DB.dataSource().getConnection()) {
            checkout.setAutoCommit(false);
            try (PreparedStatement lock =
                    checkout.prepareStatement("SELECT id FROM ordering.customer_order WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, order.id().value());
                lock.executeQuery().close();
            }

            assertThat(inTransaction(() -> orders.lockIfAwaitingPayment(order.id())))
                    .isEmpty();

            checkout.rollback();
        }
        assertThat(inTransaction(() -> orders.lockIfAwaitingPayment(order.id())))
                .contains(order);
    }

    @Test
    void theSweeperNeverTakesAnOrderThatNoLongerAwaitsPayment() {
        Order order = anOrder(PaymentMethod.CARD, NOW);
        orders.insert(order);
        orders.updateStatus(order.failPayment(), OrderStatus.AWAITING_PAYMENT, NOW);

        assertThat(inTransaction(() -> orders.lockIfAwaitingPayment(order.id())))
                .isEmpty();
    }

    @Test
    void theDatabaseRefusesACashOnDeliveryOrderWaitingForAPayment() {
        Order order = anOrder(PaymentMethod.CASH_ON_DELIVERY, NOW);
        orders.insert(order);

        assertThatThrownBy(() -> jdbc.sql("UPDATE ordering.customer_order SET status = 'AWAITING_PAYMENT'")
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("customer_order_cash_needs_no_payment");
    }

    private Optional<Order> inTransaction(Supplier<Optional<Order>> read) {
        Optional<Order> order = transaction.execute(status -> read.get());
        return Objects.requireNonNull(order);
    }

    private static Order anOrder(PaymentMethod paymentMethod, Instant placedAt) {
        PricingResult.Priced basket = (PricingResult.Priced) BasketPricing.price(
                List.of(new SkuQuantity(DATES.sku(), 1), new SkuQuantity(MILK.sku(), 3)),
                Map.of(MILK.sku(), MILK, DATES.sku(), DATES));
        return Order.place(
                OrderId.newId(),
                CustomerId.newId(),
                new PromiseTerms(PromiseId.newId(), StoreId.of("DXB-MARINA"), "EXPRESS_15", 12),
                placedAt,
                GeoPoint.of(25.0772, 55.139),
                paymentMethod,
                ReservationId.newId(),
                basket);
    }
}
