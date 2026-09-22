package io.daqiqa.ordering.application;

import io.daqiqa.ordering.domain.Order;

/**
 * Renders the outcome of a checkout as the HTTP response the client receives. Implemented by the web adapter, which
 * owns the wire format.
 *
 * <p>Why the use case needs it: the final response is stored on the idempotency key in the same transaction that
 * settles the order, so a replay can never disagree with the order's fate, and it is stored as bytes so that every
 * retry gets exactly what the first caller got.
 */
public interface CheckoutResponseRenderer {

    /** 201 with the order. */
    StoredResponse orderPlaced(Order order);

    /** The problem response for a rejection. */
    StoredResponse rejected(CheckoutRejection rejection);
}
