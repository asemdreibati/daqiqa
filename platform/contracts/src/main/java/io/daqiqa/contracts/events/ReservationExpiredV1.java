package io.daqiqa.contracts.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Stock held for {@code reference} (usually an order id) was released because the hold timed out before the order
 * was confirmed. Ordering does not need this event to stay consistent (its own sweeper abandons orders whose hold
 * has run out); it is published for analytics and for services outside the monolith.
 */
public record ReservationExpiredV1(
        UUID eventId, Instant occurredAt, UUID reservationId, String storeId, String reference, List<LineItemV1> lines)
        implements IntegrationEvent {

    public static final String TYPE = "daqiqa.inventory.reservation-expired.v1";

    public ReservationExpiredV1 {
        lines = List.copyOf(lines);
    }

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
