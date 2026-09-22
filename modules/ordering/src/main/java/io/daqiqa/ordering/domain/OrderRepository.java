package io.daqiqa.ordering.domain;

import io.daqiqa.kernel.OrderId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Outbound port: where orders are kept. Methods join the caller's transaction; the locking reads hold their row lock
 * until it ends.
 */
public interface OrderRepository {

    /** Stores a new order with its lines. */
    void insert(Order order);

    Optional<Order> find(OrderId id);

    /** The order, locked against concurrent changes until the current transaction ends; waits for other holders. */
    Optional<Order> lock(OrderId id);

    /**
     * The order if it is still {@link OrderStatus#AWAITING_PAYMENT} and nobody else holds its lock, locked until the
     * current transaction ends. Never waits: a locked order is being settled by a checkout right now.
     */
    Optional<Order> lockIfAwaitingPayment(OrderId id);

    /** Orders still awaiting payment that were placed at or before {@code cutoff}, oldest first. */
    List<OrderId> findAwaitingPaymentPlacedBy(Instant cutoff, int limit);

    /**
     * Records that the order moved from {@code from} to its current status.
     *
     * @throws IllegalStateException if the stored order is not in {@code from} any more (a lost update)
     */
    void updateStatus(Order order, OrderStatus from, Instant at);
}
