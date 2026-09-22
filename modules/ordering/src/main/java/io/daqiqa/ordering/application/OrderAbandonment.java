package io.daqiqa.ordering.application;

import io.daqiqa.contracts.events.OrderAbandonedV1;
import io.daqiqa.inventory.api.InventoryReservations;
import io.daqiqa.inventory.api.ReleaseReason;
import io.daqiqa.messaging.OutboxWriter;
import io.daqiqa.ordering.domain.Order;
import io.daqiqa.ordering.domain.OrderRepository;
import io.daqiqa.ordering.domain.OrderStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Abandons a card order whose payment did not complete within its stock hold, the same way wherever that is noticed
 * (the sweeper, a retry that finds the hold expired, a payment approved too late): the order becomes
 * {@code ABANDONED}, its reservation is released ({@link ReleaseReason#ORDER_ABANDONED}, a no-op if the inventory
 * already expired it), and {@link OrderAbandonedV1} is appended to the outbox in the same transaction, so that the
 * payments void job, its future consumer, can release any authorization left under the order id.
 *
 * <p>Runs in the caller's transaction, which must hold the order's row lock.
 */
@Component
class OrderAbandonment {

    private static final Logger log = LoggerFactory.getLogger(OrderAbandonment.class);

    private final OrderRepository orders;
    private final InventoryReservations inventory;
    private final OutboxWriter outbox;
    private final Clock clock;
    private final MeterRegistry meters;

    OrderAbandonment(
            OrderRepository orders,
            InventoryReservations inventory,
            OutboxWriter outbox,
            Clock clock,
            MeterRegistry meters) {
        this.orders = orders;
        this.inventory = inventory;
        this.outbox = outbox;
        this.clock = clock;
        this.meters = meters;
    }

    void abandon(Order order, AbandonReason reason) {
        Instant now = clock.instant();
        Order abandoned = order.abandon();
        orders.updateStatus(abandoned, OrderStatus.AWAITING_PAYMENT, now);
        inventory.release(order.reservationId(), ReleaseReason.ORDER_ABANDONED);
        outbox.append(OrderEvents.abandoned(abandoned, reason, now));
        // Logged and counted once committed: an abandonment whose transaction rolls back did not happen.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                Counter.builder("daqiqa.ordering.abandoned")
                        .description("Card orders abandoned because their payment did not complete within the hold")
                        .tag("reason", reason.tag())
                        .register(meters)
                        .increment();
                log.warn(
                        "Abandoned order {} ({}): a card authorization may exist under its id and must be voided",
                        order.id(),
                        reason);
            }
        });
    }
}
