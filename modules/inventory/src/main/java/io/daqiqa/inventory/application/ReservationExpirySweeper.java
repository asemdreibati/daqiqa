package io.daqiqa.inventory.application;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Returns holds nobody confirmed in time to sale. Runs on a fixed delay wherever scheduling is enabled (see
 * {@code InventoryConfiguration}); tests call {@link #sweep()} directly.
 */
public class ReservationExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpirySweeper.class);

    private final ReservationService reservations;
    private final Clock clock;
    private final int batchSize;

    public ReservationExpirySweeper(ReservationService reservations, Clock clock, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("The batch size must be positive, was " + batchSize);
        }
        this.reservations = reservations;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /**
     * Expires every hold that ran out by now, {@code batchSize} at a time, each batch in its own short transaction, so
     * a backlog (after an outage, say) never turns into one long transaction holding thousands of locks. Holds that run
     * out during the sweep are left for the next one, so a sweep always ends.
     *
     * @return how many holds expired
     */
    public int sweep() {
        Instant now = Timestamps.now(clock);
        int total = 0;
        int expired;
        do {
            expired = reservations.expireElapsedHolds(now, batchSize);
            total += expired;
        } while (expired == batchSize);
        if (total > 0) {
            log.info("Expired {} reservation holds that ran out by {}", total, now);
        }
        return total;
    }
}
