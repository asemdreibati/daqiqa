package io.daqiqa.network.application;

import io.daqiqa.network.api.DarkStore;
import io.daqiqa.network.domain.Store;

/** Maps the domain model to the module's public API, which never exposes domain types. */
final class DarkStores {

    private DarkStores() {}

    static DarkStore from(Store store) {
        return new DarkStore(
                store.id(),
                store.name(),
                store.location(),
                store.status(),
                store.activePickers(),
                store.serviceRadiusMeters());
    }
}
