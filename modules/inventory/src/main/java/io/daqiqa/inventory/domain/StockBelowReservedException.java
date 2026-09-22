package io.daqiqa.inventory.domain;

/** A stock adjustment would leave fewer units on hand than are held for orders. Nothing was changed. */
public final class StockBelowReservedException extends RuntimeException {

    private final StockLevel level;
    private final int delta;

    StockBelowReservedException(StockLevel level, int delta) {
        super("Adjusting " + level.sku() + " at " + level.storeId() + " by " + delta + " would leave "
                + (level.onHand() + delta) + " on hand, but " + level.reserved() + " are reserved for orders");
        this.level = level;
        this.delta = delta;
    }

    /** The level the adjustment was refused against. */
    public StockLevel level() {
        return level;
    }

    public int delta() {
        return delta;
    }
}
