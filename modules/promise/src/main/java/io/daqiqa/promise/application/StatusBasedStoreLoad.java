package io.daqiqa.promise.application;

import io.daqiqa.kernel.StoreId;
import io.daqiqa.network.api.DarkStore;
import io.daqiqa.network.api.StoreDirectory;
import io.daqiqa.network.api.StoreStatus;
import io.daqiqa.promise.domain.StoreLoad;
import io.daqiqa.promise.domain.StoreLoadProvider;
import java.util.Objects;

/**
 * Store load, version 1: configured waits per operational status, read from the network's in-memory store directory.
 *
 * <p>Candidates come from the coverage index, which only returns stores accepting orders. A store that stopped
 * accepting orders in the microseconds since (or that the directory does not know) gets the busy waits: when in doubt,
 * promise later, not sooner.
 */
public final class StatusBasedStoreLoad implements StoreLoadProvider {

    private final StoreDirectory stores;
    private final StoreLoad open;
    private final StoreLoad busy;

    public StatusBasedStoreLoad(StoreDirectory stores, StoreLoad open, StoreLoad busy) {
        this.stores = Objects.requireNonNull(stores, "stores");
        this.open = Objects.requireNonNull(open, "open");
        this.busy = Objects.requireNonNull(busy, "busy");
    }

    @Override
    public StoreLoad loadAt(StoreId store) {
        StoreStatus status = stores.find(store).map(DarkStore::status).orElse(StoreStatus.BUSY);
        return switch (status) {
            case OPEN -> open;
            case BUSY, PAUSED, CLOSED -> busy;
        };
    }
}
