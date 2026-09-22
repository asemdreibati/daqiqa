package io.daqiqa.network.api;

/** Operational status of a dark store, set by store operations (or automatically by load shedding later). */
public enum StoreStatus {
    /** Taking orders normally. */
    OPEN,
    /** Taking orders, but under pressure: promises from this store get longer. */
    BUSY,
    /** Temporarily not taking orders (stock-take, incident, rider shortage). */
    PAUSED,
    /** Outside opening hours. */
    CLOSED;

    public boolean acceptsOrders() {
        return this == OPEN || this == BUSY;
    }
}
