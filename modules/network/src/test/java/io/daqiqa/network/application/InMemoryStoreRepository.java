package io.daqiqa.network.application;

import io.daqiqa.kernel.StoreId;
import io.daqiqa.network.StoreFixtures;
import io.daqiqa.network.api.StoreStatus;
import io.daqiqa.network.domain.Store;
import io.daqiqa.network.domain.StoreRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Test double for the database: every status change increments the version, as the real one does. Thread-safe. */
final class InMemoryStoreRepository implements StoreRepository {

    private final Map<StoreId, Store> stores = new ConcurrentHashMap<>();
    private final AtomicInteger reloads = new AtomicInteger();

    void replaceAll(List<Store> network) {
        stores.clear();
        network.forEach(store -> stores.put(store.id(), store));
    }

    /** How many times the whole network was read. */
    int reloads() {
        return reloads.get();
    }

    @Override
    public List<Store> findAll() {
        reloads.incrementAndGet();
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
        return Optional.ofNullable(stores.computeIfPresent(id, (key, store) -> StoreFixtures.changed(store, status)));
    }
}
