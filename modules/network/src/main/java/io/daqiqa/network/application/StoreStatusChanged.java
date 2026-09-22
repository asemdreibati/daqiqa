package io.daqiqa.network.application;

import io.daqiqa.kernel.StoreId;
import io.daqiqa.network.api.StoreStatus;

/**
 * Application event: the status of a store changed. Internal to this instance; {@link NetworkView} reacts to it once
 * the change is committed. Other instances will learn about status changes from a {@code StoreStatusChangedV1}
 * integration event (later wave); until then their periodic refresh catches up.
 */
public record StoreStatusChanged(StoreId storeId, StoreStatus status) {}
