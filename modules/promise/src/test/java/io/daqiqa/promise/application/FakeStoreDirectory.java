package io.daqiqa.promise.application;

import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.kernel.LocalizedText;
import io.daqiqa.kernel.StoreId;
import io.daqiqa.network.api.DarkStore;
import io.daqiqa.network.api.StoreDirectory;
import io.daqiqa.network.api.StoreStatus;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** The network's store directory, as far as the promise engine needs it. */
final class FakeStoreDirectory implements StoreDirectory {

    private final Map<StoreId, DarkStore> stores = new ConcurrentHashMap<>();

    FakeStoreDirectory with(StoreId id, StoreStatus status) {
        stores.put(
                id, new DarkStore(id, LocalizedText.of("Store", "متجر"), GeoPoint.of(25.08, 55.14), status, 5, 3_000));
        return this;
    }

    @Override
    public Optional<DarkStore> find(StoreId storeId) {
        return Optional.ofNullable(stores.get(storeId));
    }

    @Override
    public List<DarkStore> findAll() {
        return stores.values().stream()
                .sorted(Comparator.comparing(store -> store.id().value()))
                .toList();
    }
}
