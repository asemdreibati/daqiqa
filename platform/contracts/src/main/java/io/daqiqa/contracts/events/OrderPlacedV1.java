package io.daqiqa.contracts.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An order was confirmed and must now be picked at {@code storeId} and delivered by {@code promisedBy}.
 *
 * <p>Carries the promise that was made, so downstream consumers (fulfilment, dispatch, promise accuracy) can measure
 * delivery against it without calling back.
 */
public record OrderPlacedV1(
        UUID eventId,
        Instant occurredAt,
        UUID orderId,
        String storeId,
        UUID customerId,
        UUID promiseId,
        String deliveryTier,
        int promisedEtaMinutes,
        Instant promisedBy,
        double deliveryLatitude,
        double deliveryLongitude,
        List<LineItemV1> lines,
        long totalMinor,
        String currency)
        implements IntegrationEvent {

    public static final String TYPE = "daqiqa.ordering.order-placed.v1";

    public OrderPlacedV1 {
        lines = List.copyOf(lines);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String topic() {
        return Topics.ORDERS;
    }

    @Override
    public String partitionKey() {
        return storeId;
    }
}
