package io.daqiqa.inventory.application;

import io.daqiqa.inventory.application.InventoryMetrics.ExpiryFailure;
import io.daqiqa.kernel.ReservationId;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    public ReservationExpirySweeper(
            ReservationService reservations, InventoryMetrics metrics, Clock clock, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("The batch size must be positive, was " + batchSize);
        }
        this.reservations = reservations;
        this.metrics = metrics;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /**
     * Expires every hold that ran out by now, {@code batchSize} at a time, each batch in its own short transaction, so
     * a backlog (after an outage, say) never turns into one long transaction holding thousands of locks. Holds that run
     * out during the sweep are left for the next one, and a hold that cannot expire stops the loop only when its batch
     * expires nothing else, so a sweep always ends.
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
     * One batch in one transaction. If that fails (one hold's stock rows are inconsistent, say), the same holds are
     * retried one per transaction, so one poison hold cannot keep every other hold in every store from expiring.
     */
    private Batch expireBatch(Instant now) {
        try {
            int expired = reservations.expireElapsedHolds(now, batchSize);
            return new Batch(expired, expired);
        } catch (RuntimeException batchFailure) {
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
            return reservations.expireElapsedHold(hold, now);
        } catch (RuntimeException holdFailure) {
            metrics.expiryFailed(ExpiryFailure.HOLD);
            log.error("Hold {} cannot expire and stays held; its stock rows need repair", hold, holdFailure);
            return false;
        }
    }

    /** How many holds a batch took on, and how many of them expired. */
    private record Batch(int taken, int expired) {}
}
