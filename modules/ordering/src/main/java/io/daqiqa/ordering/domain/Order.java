package io.daqiqa.ordering.domain;

import io.daqiqa.kernel.CustomerId;
import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.kernel.Money;
import io.daqiqa.kernel.OrderId;
import io.daqiqa.kernel.ReservationId;
import io.daqiqa.kernel.Sku;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A customer order. Everything but the status is fixed when the order is placed: the promise it was sold under, where
 * it goes, how it is paid, the reservation holding its stock, and its lines with their snapshotted names and prices.
 * The status moves only as {@link OrderStatus#canBecome} allows; each move returns a new instance.
 *
 * @param placedAt when the stock was held and the order created; the promised minutes count from here
 * @param reservationId the inventory reservation holding this order's stock
 * @param total the sum of the line totals
 */
public record Order(
        OrderId id,
        CustomerId customerId,
        PromiseTerms promise,
        Instant placedAt,
        GeoPoint deliveryLocation,
        PaymentMethod paymentMethod,
        ReservationId reservationId,
        List<OrderLine> lines,
        Money total,
        OrderStatus status) {

    public Order {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(promise, "promise");
        Objects.requireNonNull(placedAt, "placedAt");
        Objects.requireNonNull(deliveryLocation, "deliveryLocation");
        Objects.requireNonNull(paymentMethod, "paymentMethod");
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(total, "total");
        Objects.requireNonNull(status, "status");
        lines = List.copyOf(lines);
        requireWellFormed(id, lines);
        if (!BasketPricing.totalOf(lines).equals(total)) {
            throw new IllegalArgumentException("Total of order " + id + " must be the sum of its lines, was " + total);
        }
        if (paymentMethod == PaymentMethod.CASH_ON_DELIVERY
                && (status == OrderStatus.AWAITING_PAYMENT || status == OrderStatus.PAYMENT_FAILED)) {
            throw new IllegalArgumentException("A cash-on-delivery order never waits for, or fails, a card payment");
        }
    }

    /**
     * Places an order for a priced basket whose stock is held. Card orders wait for their payment; cash-on-delivery
     * orders are confirmed straight away.
     */
    public static Order place(
            OrderId id,
            CustomerId customerId,
            PromiseTerms promise,
            Instant placedAt,
            GeoPoint deliveryLocation,
            PaymentMethod paymentMethod,
            ReservationId reservationId,
            PricingResult.Priced basket) {
        return new Order(
                id,
                customerId,
                promise,
                placedAt,
                deliveryLocation,
                paymentMethod,
                reservationId,
                basket.lines(),
                basket.total(),
                paymentMethod.initialStatus());
    }

    /** The promise to keep: placement time plus the promised minutes. */
    public Instant deliverBy() {
        return placedAt.plus(Duration.ofMinutes(promise.etaMinutes()));
    }

    /** The card payment was approved. */
    public Order confirmPayment() {
        return moveTo(OrderStatus.CONFIRMED);
    }

    /** The card payment was declined. */
    public Order failPayment() {
        return moveTo(OrderStatus.PAYMENT_FAILED);
    }

    /** The payment did not complete while the stock was held. */
    public Order abandon() {
        return moveTo(OrderStatus.ABANDONED);
    }

    private Order moveTo(OrderStatus next) {
        if (!status.canBecome(next)) {
            throw new IllegalOrderTransitionException(id, status, next);
        }
        return new Order(
                id, customerId, promise, placedAt, deliveryLocation, paymentMethod, reservationId, lines, total, next);
    }

    private static void requireWellFormed(OrderId id, List<OrderLine> lines) {
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("Order " + id + " has no lines");
        }
        Set<Sku> skus = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            OrderLine line = lines.get(i);
            if (line.lineNo() != i + 1) {
                throw new IllegalArgumentException("Lines of order " + id + " must be numbered 1.." + lines.size());
            }
            if (!skus.add(line.sku())) {
                throw new IllegalArgumentException("Order " + id + " lists " + line.sku() + " twice");
            }
        }
    }
}
