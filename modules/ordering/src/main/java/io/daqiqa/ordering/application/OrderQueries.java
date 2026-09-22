package io.daqiqa.ordering.application;

import io.daqiqa.kernel.OrderId;
import io.daqiqa.ordering.domain.Order;
import io.daqiqa.ordering.domain.OrderRepository;
import org.springframework.stereotype.Service;

/** Read side of ordering ({@code GET /v1/orders/{orderId}}). */
@Service
public class OrderQueries {

    private final OrderRepository orders;

    OrderQueries(OrderRepository orders) {
        this.orders = orders;
    }

    /** @throws OrderNotFoundException if there is no such order */
    public Order get(OrderId id) {
        return orders.find(id).orElseThrow(() -> new OrderNotFoundException(id));
    }
}
