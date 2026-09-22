/**
 * Public API of the inventory context.
 *
 * <p>Two different questions, two different interfaces: {@link io.daqiqa.inventory.api.AvailabilityQuery} is a fast,
 * possibly slightly stale read used to <em>promise</em>; {@link io.daqiqa.inventory.api.InventoryReservations} is the
 * strongly consistent write used to <em>guarantee</em>.
 */
@NullMarked
package io.daqiqa.inventory.api;

import org.jspecify.annotations.NullMarked;
