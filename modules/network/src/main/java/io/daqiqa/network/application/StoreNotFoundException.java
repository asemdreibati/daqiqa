package io.daqiqa.network.application;

import io.daqiqa.kernel.StoreId;

/** Raised when a store is requested by id and does not exist. */
public final class StoreNotFoundException extends RuntimeException {

    private final StoreId storeId;

    public StoreNotFoundException(StoreId storeId) {
        super("No store with id " + storeId);
        this.storeId = storeId;
    }

    public StoreId storeId() {
        return storeId;
    }
}
