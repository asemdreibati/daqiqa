package io.daqiqa.inventory.application;

import io.daqiqa.kernel.StoreId;

/** The network context knows no store with this id. */
public final class StoreNotFoundException extends RuntimeException {

    private final StoreId storeId;

    public StoreNotFoundException(StoreId storeId) {
        super("No store " + storeId);
        this.storeId = storeId;
    }

    public StoreId storeId() {
        return storeId;
    }
}
