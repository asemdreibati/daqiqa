package io.daqiqa.ordering.application;

import io.daqiqa.inventory.api.ReleaseReason;
import io.daqiqa.kernel.OrderId;
import io.daqiqa.ordering.domain.Order;
import io.daqiqa.ordering.domain.OrderRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Abandons orders whose card payment did not complete within the hold and puts their stock back on sale
 * ({@link ReleaseReason#ORDER_ABANDONED}): the checkouts that stopped between T1 and T2 because the client gave up or
 * the instance died. See {@link OrderAbandonment}.
 *
 * <p>Several instances may sweep at once. Candidates are read in a batch, then each order is abandoned in its own
 * short transaction that locks it with {@code FOR UPDATE SKIP LOCKED}: an order that a checkout (or another sweeper)
 * is settling right now is skipped, never waited for or abandoned twice, and one order that cannot be abandoned does
 * not hold back the rest of the batch.
 */
@Component
class AbandonedOrderSweeper {

    private static final Logger log = LoggerFactory.getLogger(AbandonedOrderSweeper.class);

    private final OrderRepository orders;
    private final OrderAbandonment abandonment;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Duration hold;
    private final int batchSize;
    private final Counter failures;

    AbandonedOrderSweeper(
            OrderRepository orders,
            OrderAbandonment abandonment,
            PlatformTransactionManager transactionManager,
            Clock clock,
            MeterRegistry meters,
            OrderingProperties properties) {
        this.orders = orders;
        this.abandonment = abandonment;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.hold = properties.hold();
        this.batchSize = properties.sweeper().batchSize();
        this.failures = Counter.builder("daqiqa.ordering.sweeper.failures")
                .description("Orders the sweeper could not abandon; retried on the next sweep")
                .register(meters);
    }

    /** Abandons up to one batch of orders that have waited longer than the hold; returns how many. */
    @Scheduled(
            fixedDelayString =
                    "${daqiqa.ordering.sweeper.interval:" + OrderingProperties.Sweeper.DEFAULT_INTERVAL + "}")
    public int sweep() {
        Instant cutoff = clock.instant().minus(hold);
        int abandoned = 0;
        for (OrderId id : orders.findAwaitingPaymentPlacedBy(cutoff, batchSize)) {
            try {
                if (Boolean.TRUE.equals(transaction.execute(status -> abandonIfStillWaiting(id)))) {
                    abandoned++;
                }
            } catch (RuntimeException e) {
                failures.increment();
                log.warn("Could not abandon order {}; the next sweep retries it", id, e);
            }
        }
        return abandoned;
    }

    private boolean abandonIfStillWaiting(OrderId id) {
        Optional<Order> waiting = orders.lockIfAwaitingPayment(id);
        if (waiting.isEmpty()) {
            return false; // settled in the meantime, or being settled right now
        }
        abandonment.abandon(waiting.get(), AbandonReason.HOLD_EXPIRED);
        return true;
    }
}
