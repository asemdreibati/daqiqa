/**
 * Public API of the ordering context. Empty for now: ordering is driven through HTTP and publishes
 * {@link io.daqiqa.contracts.events.OrderPlacedV1} events; no other module calls it synchronously.
 */
@NullMarked
package io.daqiqa.ordering.api;

import org.jspecify.annotations.NullMarked;
