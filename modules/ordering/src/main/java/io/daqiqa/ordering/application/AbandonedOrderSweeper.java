package io.daqiqa.ordering.application;

import io.daqiqa.inventory.api.ReleaseReason;
import io.daqiqa.kernel.OrderId;
import io.daqiqa.ordering.domain.Order;
import io.daqiqa.ordering.domain.OrderRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
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
 *
 * <p>Releasing the stock takes locks the sweeper does not control: the inventory locks the store's stock rows, which a
 * burst of checkouts holds all the time. This is background work behind live customers, so each transaction caps its
 * lock wait ({@code daqiqa.ordering.sweeper.lock-timeout}) and gives that order up for this round when the wait runs
 * out. Losing a lock race is normal under load, not a fault: it is counted apart from real failures and logged at INFO,
 * and the next sweep tries the order again, which is what the hold's expiry entitles it to anyway.
 */
@Component
class AbandonedOrderSweeper {

    private static final Logger log = LoggerFactory.getLogger(AbandonedOrderSweeper.class);

    /** PostgreSQL: lock_not_available, deadlock_detected, serialization_failure. */
    private static final Set<String> CONTENTION_STATES = Set.of("55P03", "40P01", "40001");

    private final OrderRepository orders;
    private final OrderAbandonment abandonment;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Duration hold;
    private final int batchSize;
    private final Duration lockTimeout;
    private final Counter failures;
    private final Counter contention;

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
        this.lockTimeout = properties.sweeper().lockTimeout();
        this.failures = Counter.builder("daqiqa.ordering.sweeper.failures")
                .description("Orders the sweeper could not abandon; retried on the next sweep")
                .register(meters);
        this.contention = Counter.builder("daqiqa.ordering.sweeper.contention")
                .description("Orders the sweeper gave up on because their rows were locked; retried on the next sweep")
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
                if (lockContention(e)) {
                    contention.increment();
                    log.info(
                            "Order {} is locked by someone else ({}); the next sweep retries it",
                            id,
                            rootCause(e).toString());
                } else {
                    failures.increment();
                    log.warn("Could not abandon order {}; the next sweep retries it", id, e);
                }
            }
        }
        return abandoned;
    }

    private boolean abandonIfStillWaiting(OrderId id) {
        orders.capLockWait(lockTimeout);
        Optional<Order> waiting = orders.lockIfAwaitingPayment(id);
        if (waiting.isEmpty()) {
            return false; // settled in the meantime, or being settled right now
        }
        abandonment.abandon(waiting.get(), AbandonReason.HOLD_EXPIRED);
        return true;
    }

    /**
     * Whether the failure is a lock we could not get: the wait ran out ({@code 55P03}), the database broke a deadlock
     * ({@code 40P01}) or a concurrent write won ({@code 40001}). The state is looked for down the whole cause chain
     * because Spring translates only some of them into its own exceptions ({@code 55P03} arrives as an
     * {@code UncategorizedSQLException}), and because the failure may come from another module's repository.
     */
    private static boolean lockContention(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && CONTENTION_STATES.contains(sql.getSQLState())) {
                return true;
            }
            if (cause == cause.getCause()) {
                break;
            }
        }
        return false;
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
