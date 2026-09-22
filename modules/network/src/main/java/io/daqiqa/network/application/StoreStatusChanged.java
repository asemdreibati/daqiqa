package io.daqiqa.network.application;

import io.daqiqa.network.domain.Store;

/**
 * Application event: the status of a store changed, and {@code store} is the row exactly as the update returned it,
 * new version included. Internal to this instance: {@link NetworkView} applies it once the transaction commits. Other
 * instances will learn about status changes from a {@code StoreStatusChangedV1} integration event (later wave); until
 * then their periodic reconciliation catches up.
 */
public record StoreStatusChanged(Store store) {}
