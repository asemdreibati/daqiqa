/**
 * Ordering use cases: idempotent checkout ({@link io.daqiqa.ordering.application.CheckoutService}), order queries,
 * the abandoned-order sweeper and the idempotency key purge. Owns transaction boundaries and the outbound ports that
 * are not domain concepts (payments, idempotency keys, response rendering).
 */
@NullMarked
package io.daqiqa.ordering.application;

import org.jspecify.annotations.NullMarked;
