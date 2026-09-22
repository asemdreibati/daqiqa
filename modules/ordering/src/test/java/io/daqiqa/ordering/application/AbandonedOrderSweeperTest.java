package io.daqiqa.ordering.application;

import static io.daqiqa.ordering.testsupport.FakeProductCatalog.BREAD;
import static io.daqiqa.ordering.testsupport.FakeProductCatalog.MILK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.daqiqa.inventory.api.ReleaseReason;
import io.daqiqa.kernel.OrderId;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.ordering.domain.PaymentMethod;
import io.daqiqa.ordering.testsupport.FakeInventory.Reservation;
import io.daqiqa.ordering.testsupport.FakeInventory.Status;
import io.daqiqa.ordering.testsupport.OrderingIntegrationTest;
import io.daqiqa.ordering.testsupport.ScriptedPaymentGateway.SimulatedCrash;
import io.daqiqa.testing.postgres.PostgresTestDatabase;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AbandonedOrderSweeperTest extends OrderingIntegrationTest {

    @Autowired
    CheckoutService checkout;

    @Autowired
    AbandonedOrderSweeper sweeper;

    @Autowired
    MeterRegistry meters;

    @Test
    void abandonsAnOrderWhosePaymentNeverCompletedWithinTheHoldAndPutsItsStockBackOnSale() {
        OrderId stuck = checkoutThatDies("stuck-order-1", new SkuQuantity(MILK.sku(), 2));
        assertThat(inventory.available(STORE, MILK.sku())).isEqualTo(8);

        clock.advance(Duration.ofMinutes(4));
        assertThat(sweeper.sweep()).as("still within the hold").isZero();

        clock.advance(Duration.ofMinutes(1));
        assertThat(sweeper.sweep()).isEqualTo(1);

        assertThat(orderStatus(stuck)).contains("ABANDONED");
        Reservation reservation = inventory.reservationsFor(stuck.toString()).getFirst();
        assertThat(reservation.status()).isEqualTo(Status.RELEASED);
        assertThat(reservation.releaseReason()).isEqualTo(ReleaseReason.ORDER_ABANDONED);
        assertThat(inventory.available(STORE, MILK.sku())).isEqualTo(10);
        assertThat(sweeper.sweep()).as("abandoned once").isZero();
    }

    @Test
    void leavesSettledOrdersAlone() {
        CheckoutResponse confirmed = checkout.placeOrder(KEY, checkoutOf(PaymentMethod.CARD, line(MILK, 1)));
        clock.advance(Duration.ofMinutes(30));

        assertThat(sweeper.sweep()).isZero();
        assertThat(orderStatus(confirmed.orderId())).contains("CONFIRMED");
    }

    @Test
    void skipsAnOrderThatACheckoutIsSettlingRightNow() throws Exception {
        OrderId stuck = checkoutThatDies("stuck-order-1", new SkuQuantity(MILK.sku(), 1));
        clock.advance(Duration.ofMinutes(6));

        try (Connection checkoutTransaction =
                PostgresTestDatabase.shared().dataSource().getConnection()) {
            checkoutTransaction.setAutoCommit(false);
            try (PreparedStatement lock = checkoutTransaction.prepareStatement(
                    "SELECT id FROM ordering.customer_order WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, stuck.value());
                lock.executeQuery().close();
            }

            assertThat(sweeper.sweep()).as("skipped, not waited for").isZero();

            checkoutTransaction.rollback();
        }
        assertThat(sweeper.sweep()).isEqualTo(1);
    }

    @Test
    void anOrderThatCannotBeAbandonedDoesNotHoldBackTheOthers() {
        OrderId failing = checkoutThatDies("stuck-order-1", new SkuQuantity(MILK.sku(), 1));
        OrderId healthy = checkoutThatDies("stuck-order-2", new SkuQuantity(BREAD.sku(), 1));
        inventory.failReleasesOf(
                inventory.reservationsFor(failing.toString()).getFirst().id(),
                new IllegalStateException("inventory unavailable"));
        clock.advance(Duration.ofMinutes(6));
        double failuresBefore =
                meters.counter("daqiqa.ordering.sweeper.failures").count();

        assertThat(sweeper.sweep()).isEqualTo(1);

        assertThat(orderStatus(healthy)).contains("ABANDONED");
        assertThat(orderStatus(failing)).as("rolled back, retried next sweep").contains("AWAITING_PAYMENT");
        assertThat(meters.counter("daqiqa.ordering.sweeper.failures").count()).isEqualTo(failuresBefore + 1);
    }

    /** A card checkout whose instance dies during the payment: the order waits for it with its stock held. */
    private OrderId checkoutThatDies(String key, SkuQuantity line) {
        payments.crash();
        assertThatThrownBy(() -> checkout.placeOrder(key, checkoutOf(PaymentMethod.CARD, line)))
                .isInstanceOf(SimulatedCrash.class);
        OrderId orderId = payments.authorizations().getLast();
        assertThat(orderStatus(orderId)).contains("AWAITING_PAYMENT");
        return orderId;
    }
}
