package io.daqiqa.ordering.application;

import io.daqiqa.contracts.events.LineItemV1;
import io.daqiqa.contracts.events.OrderAbandonedV1;
import io.daqiqa.contracts.events.OrderPlacedV1;
import io.daqiqa.kernel.Ids;
import io.daqiqa.ordering.domain.Order;
import io.daqiqa.ordering.domain.PaymentMethod;
import java.time.Instant;

/** Translates orders into the published language (ADR-0009). */
final class OrderEvents {

    private OrderEvents() {}

    static OrderPlacedV1 placed(Order order, Instant confirmedAt) {
        return new OrderPlacedV1(
                Ids.newTimeOrderedId(),
                confirmedAt,
                order.id().value(),
                order.promise().storeId().value(),
                order.customerId().value(),
                order.promise().promiseId().value(),
                order.promise().tier(),
                order.promise().etaMinutes(),
                order.deliverBy(),
                order.deliveryLocation().latitude(),
                order.deliveryLocation().longitude(),
                order.lines().stream()
                        .map(line -> new LineItemV1(line.sku().value(), line.quantity()))
                        .toList(),
                order.total().amountMinor(),
                order.total().currency().getCurrencyCode());
    }

    /**
     * Every abandoned card order may have left an authorization behind: an earlier attempt may have been approved just
     * before it died, and nothing records a payment that never started.
     */
    static OrderAbandonedV1 abandoned(Order order, AbandonReason reason, Instant abandonedAt) {
        return new OrderAbandonedV1(
                Ids.newTimeOrderedId(),
                abandonedAt,
                order.id().value(),
                order.promise().storeId().value(),
                order.customerId().value(),
                order.paymentMethod().name(),
                reason.name(),
                order.paymentMethod() == PaymentMethod.CARD,
                order.total().amountMinor(),
                order.total().currency().getCurrencyCode());
    }
}
