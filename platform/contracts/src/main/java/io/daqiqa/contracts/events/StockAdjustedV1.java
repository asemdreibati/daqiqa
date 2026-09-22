package io.daqiqa.contracts.events;

import java.time.Instant;
import java.util.UUID;

/** The on-hand quantity of a SKU at a store changed outside of the order flow (delivery received, damage, count). */
public record StockAdjustedV1(
        UUID eventId,
        Instant occurredAt,
        String storeId,
        String sku,
        int delta,
        String reason,
        int onHandAfter,
        int reservedAfter)
        implements IntegrationEvent {

    public static final String TYPE = "daqiqa.inventory.stock-adjusted.v1";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String topic() {
        return Topics.INVENTORY;
    }

    @Override
    public String partitionKey() {
        return storeId;
    }
}
