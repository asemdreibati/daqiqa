package io.daqiqa.inventory.support;

import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.kernel.LocalizedText;
import io.daqiqa.kernel.StoreId;
import io.daqiqa.network.api.DarkStore;
import io.daqiqa.network.api.StoreDirectory;
import io.daqiqa.network.api.StoreStatus;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Stands in for the network context: knows exactly the stores a test adds. Thread-safe. */
public final class FakeStoreDirectory implements StoreDirectory {

    private final Set<StoreId> stores = ConcurrentHashMap.newKeySet();

    public void add(StoreId... storeIds) {
        stores.addAll(List.of(storeIds));
    }

    public void clear() {
        stores.clear();
    }

    @Override
    public Optional<DarkStore> find(StoreId storeId) {
        return stores.contains(storeId) ? Optional.of(store(storeId)) : Optional.empty();
    }

    @Override
    public List<DarkStore> findAll() {
        return stores.stream()
                .sorted(Comparator.comparing(StoreId::value))
                .map(FakeStoreDirectory::store)
                .toList();
    }

    private static DarkStore store(StoreId id) {
        return new DarkStore(
                id,
                LocalizedText.of("Store " + id, "متجر " + id),
                GeoPoint.of(25.0772, 55.1390),
                StoreStatus.OPEN,
                6,
                3_000);
    }
}
