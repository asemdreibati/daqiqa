package io.daqiqa.contracts.events;

import java.time.Instant;
import java.util.UUID;

/**
 * An order was abandoned: its card payment did not complete while its stock was held, so the stock went back on sale
 * and nothing will be picked or delivered.
 *
 * <p>For payments this is a to-do, not just a fact: when {@code paymentAuthorizationMayExist}, the provider may hold an
 * authorization under the order id (the provider's idempotency key) that nothing will capture, and the payments void
 * job, a future consumer of this event, must release it. Orders cannot tell a declined or never-started payment from
 * an authorization whose confirmation was lost, so every abandoned card order says so.
 *
 * @param paymentMethod {@code CARD} or {@code CASH_ON_DELIVERY}
 * @param reason {@code HOLD_EXPIRED}: the payment never completed within the hold (checkout stopped, or the client
 *     gave up); {@code PAID_AFTER_HOLD_EXPIRED}: the payment was approved only after the hold had expired, so an
 *     authorization certainly exists
 */
public record OrderAbandonedV1(
        UUID eventId,
        Instant occurredAt,
        UUID orderId,
        String storeId,
        UUID customerId,
        String paymentMethod,
        String reason,
        boolean paymentAuthorizationMayExist,
        long totalMinor,
        String currency)
        implements IntegrationEvent {

    public static final String TYPE = "daqiqa.ordering.order-abandoned.v1";

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
