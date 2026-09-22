package io.daqiqa.network.application;

import io.daqiqa.kernel.StoreId;
import io.daqiqa.network.api.StoreStatus;
import io.daqiqa.network.domain.Store;
import io.daqiqa.network.domain.StoreRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Test double for the database. Thread-safe. */
final class InMemoryStoreRepository implements StoreRepository {

    private final Map<StoreId, Store> stores = new ConcurrentHashMap<>();

    void replaceAll(List<Store> network) {
        stores.clear();
        network.forEach(store -> stores.put(store.id(), store));
    }

    @Override
    public List<Store> findAll() {
        return stores.values().stream()
                .sorted(Comparator.comparing(store -> store.id().value()))
                .toList();
    }

    @Override
    public Optional<Store> findById(StoreId id) {
        return Optional.ofNullable(stores.get(id));
    }

    @Override
    public Optional<Store> updateStatus(StoreId id, StoreStatus status, Instant changedAt) {
        return Optional.ofNullable(stores.computeIfPresent(
                id,
                (key, store) -> new Store(
                        store.id(),
                        store.name(),
                        store.location(),
                        status,
                        store.activePickers(),
                        store.serviceRadiusMeters())));
    }
}
