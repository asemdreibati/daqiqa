package io.daqiqa.inventory.domain;

/** Why store operations changed the quantity on hand (the values of the HTTP contract). */
public enum AdjustmentReason {
    /** A delivery arrived. */
    RECEIVED,
    /** Units were damaged and removed from sale. */
    DAMAGED,
    /** Units passed their best-before date and were removed from sale. */
    EXPIRED,
    /** A shelf count found a different quantity. */
    CYCLE_COUNT,
    /** Fixing an earlier mistake. */
    CORRECTION
}
