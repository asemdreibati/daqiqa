package io.daqiqa.ordering.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.daqiqa.kernel.Money;
import io.daqiqa.ordering.domain.Order;
import io.daqiqa.ordering.domain.OrderLine;
import io.daqiqa.ordering.domain.OrderStatus;
import io.daqiqa.ordering.domain.PaymentMethod;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The {@code Order} schema of the API contract. Kept apart from the domain type so the wire format is explicit; the
 * property order follows the contract, so responses read the way the documentation does.
 */
@JsonPropertyOrder({
    "orderId",
    "status",
    "storeId",
    "customerId",
    "promiseId",
    "tier",
    "etaMinutes",
    "placedAt",
    "deliverBy",
    "paymentMethod",
    "items",
    "total"
})
record OrderJson(
        UUID orderId,
        OrderStatus status,
        String storeId,
        UUID customerId,
        UUID promiseId,
        String tier,
        int etaMinutes,
        Instant placedAt,
        Instant deliverBy,
        PaymentMethod paymentMethod,
        List<LineJson> items,
        MoneyJson total) {

    static OrderJson from(Order order) {
        return new OrderJson(
                order.id().value(),
                order.status(),
                order.promise().storeId().value(),
                order.customerId().value(),
                order.promise().promiseId().value(),
                order.promise().tier(),
                order.promise().etaMinutes(),
                order.placedAt(),
                order.deliverBy(),
                order.paymentMethod(),
                order.lines().stream().map(LineJson::from).toList(),
                MoneyJson.from(order.total()));
    }

    @JsonPropertyOrder({"sku", "name", "quantity", "unitPrice", "lineTotal"})
    record LineJson(String sku, NameJson name, int quantity, MoneyJson unitPrice, MoneyJson lineTotal) {

        static LineJson from(OrderLine line) {
            return new LineJson(
                    line.sku().value(),
                    new NameJson(line.name().en(), line.name().ar()),
                    line.quantity(),
                    MoneyJson.from(line.unitPrice()),
                    MoneyJson.from(line.lineTotal()));
        }
    }

    @JsonPropertyOrder({"en", "ar"})
    record NameJson(String en, String ar) {}

    @JsonPropertyOrder({"amountMinor", "currency"})
    record MoneyJson(long amountMinor, String currency) {

        static MoneyJson from(Money money) {
            return new MoneyJson(money.amountMinor(), money.currency().getCurrencyCode());
        }
    }
}
