package io.daqiqa.inventory.application;

import io.daqiqa.inventory.application.InventoryMetrics.ExpiryFailure;
import io.daqiqa.kernel.ReservationId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;

/**
 * Returns holds nobody confirmed in time to sale. Runs on a fixed delay wherever scheduling is enabled (see
 * {@code InventoryConfiguration}); tests call {@link #sweep()} directly.
 */
public class ReservationExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpirySweeper.class);

    private final ReservationService reservations;
    private final InventoryMetrics metrics;
    private final Clock clock;
    private final int batchSize;
    private final Duration lockTimeout;

    public ReservationExpirySweeper(
            ReservationService reservations,
            InventoryMetrics metrics,
            Clock clock,
            int batchSize,
            Duration lockTimeout) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("The batch size must be positive, was " + batchSize);
        }
        if (lockTimeout.isNegative() || lockTimeout.isZero()) {
            throw new IllegalArgumentException("The lock timeout must be positive, was " + lockTimeout);
        }
        this.reservations = reservations;
        this.metrics = metrics;
        this.clock = clock;
        this.batchSize = batchSize;
        this.lockTimeout = lockTimeout;
    }

    /**
     * Expires every hold that ran out by now, {@code batchSize} at a time, each batch in its own short transaction, so
     * a backlog (after an outage, say) never turns into one long transaction holding thousands of locks. Holds that run
     * out during the sweep are left for the next one, and a hold that cannot expire stops the loop only when its batch
     * expires nothing else, so a sweep always ends. A batch that ran into contention ends the sweep too: those holds
     * are still due, and the next round takes them.
     *
     * @return how many holds expired
     */
    public int sweep() {
        Instant now = Timestamps.now(clock);
        int total = 0;
        Batch batch;
        do {
            batch = expireBatch(now);
            total += batch.expired();
        } while (batch.taken() == batchSize && batch.expired() > 0);
        if (total > 0) {
            log.info("Expired {} reservation holds that ran out by {}", total, now);
        }
        return total;
    }

    /**
     * One batch in one transaction, and what to make of a failure.
     *
     * <ul>
     *   <li><b>Contention</b> (a checkout holds a stock row of the batch for longer than the sweeper's lock timeout):
     *       normal under load. The batch is left for the next round, which is seconds away, and counted apart from
     *       failures so it never raises the alarm meant for holds that cannot expire at all.
     *   <li><b>Anything else</b> (one hold's stock rows are inconsistent, say): the same holds are retried one per
     *       transaction, so one poison hold cannot keep every other hold in every store from expiring.
     * </ul>
     */
    private Batch expireBatch(Instant now) {
        try {
            int expired = reservations.expireElapsedHolds(now, batchSize, lockTimeout);
            return new Batch(expired, expired);
        } catch (RuntimeException batchFailure) {
            if (LockContention.isContention(batchFailure)) {
                return contended("a batch of elapsed holds", batchFailure);
            }
            metrics.expiryFailed(ExpiryFailure.BATCH);
            log.warn("A batch of elapsed holds failed to expire; retrying them one by one", batchFailure);
            List<ReservationId> elapsed = reservations.elapsedHoldIds(now, batchSize);
            int expired = 0;
            for (ReservationId hold : elapsed) {
                if (expireAlone(hold, now)) {
                    expired++;
                }
            }
            return new Batch(elapsed.size(), expired);
        }
    }

    private boolean expireAlone(ReservationId hold, Instant now) {
        try {
            return reservations.expireElapsedHold(hold, now, lockTimeout);
        } catch (RuntimeException holdFailure) {
            if (LockContention.isContention(holdFailure)) {
                contended("hold " + hold, holdFailure);
                return false;
            }
            metrics.expiryFailed(ExpiryFailure.HOLD);
            log.error("Hold {} cannot expire and stays held; its stock rows need repair", hold, holdFailure);
            return false;
        }
    }

    /** Someone else was holding a row this sweep needed: not a failure, and nothing to repair. */
    private Batch contended(String what, RuntimeException contention) {
        metrics.expiryContended();
        log.info(
                "Gave up waiting for a row lock while expiring {} after {}; the next sweep takes them again: {}",
                what,
                lockTimeout,
                NestedExceptionUtils.getMostSpecificCause(contention).getMessage());
        return new Batch(0, 0);
    }

    /** How many holds a batch took on, and how many of them expired. */
    private record Batch(int taken, int expired) {}
}
