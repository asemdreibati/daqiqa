package io.daqiqa.network.application;

import io.daqiqa.kernel.StoreId;
import io.daqiqa.network.api.DarkStore;
import io.daqiqa.network.api.StoreStatus;
import io.daqiqa.network.domain.Store;
import io.daqiqa.network.domain.StoreRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Store operations use cases. Reads come from the database, the system of record: operators see exactly what was
 * committed, even a change made through another instance.
 */
@Service
@Transactional(readOnly = true)
public class StoreService {

    private final StoreRepository stores;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public StoreService(StoreRepository stores, ApplicationEventPublisher events, Clock clock) {
        this.stores = stores;
        this.events = events;
        this.clock = clock;
    }

    /** Every store, whatever its status, ordered by id. */
    public List<DarkStore> list() {
        return stores.findAll().stream().map(DarkStores::from).toList();
    }

    public DarkStore get(StoreId id) {
        return stores.findById(id).map(DarkStores::from).orElseThrow(() -> new StoreNotFoundException(id));
    }

    /**
     * Opens, pauses, closes or marks a store busy. Once committed, the updated row is applied to this instance's
     * coverage index before this method returns (see {@link NetworkView#onStoreStatusChanged}): a paused store stops
     * receiving promises immediately.
     */
    @Transactional
    public DarkStore changeStatus(StoreId id, StoreStatus status) {
        Store store =
                stores.updateStatus(id, status, clock.instant()).orElseThrow(() -> new StoreNotFoundException(id));
        events.publishEvent(new StoreStatusChanged(store));
        return DarkStores.from(store);
    }
}
