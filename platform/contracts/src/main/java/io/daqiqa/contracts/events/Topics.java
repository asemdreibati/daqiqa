package io.daqiqa.contracts.events;

/** Kafka topics. The version in the name changes only if the partitioning key changes. */
public final class Topics {

    public static final String ORDERS = "daqiqa.orders.v1";
    public static final String INVENTORY = "daqiqa.inventory.v1";

    private Topics() {}
}
