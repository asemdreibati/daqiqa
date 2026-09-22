package io.daqiqa.ordering.application;

import io.daqiqa.contracts.events.LineItemV1;
import io.daqiqa.contracts.events.OrderPlacedV1;
import io.daqiqa.kernel.Ids;
import io.daqiqa.ordering.domain.Order;
import java.time.Instant;

/** Translates a confirmed order into the published language (ADR-0009). */
final class OrderPlacedEvents {

    private OrderPlacedEvents() {}

    static OrderPlacedV1 orderPlaced(Order order, Instant confirmedAt) {
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
}
