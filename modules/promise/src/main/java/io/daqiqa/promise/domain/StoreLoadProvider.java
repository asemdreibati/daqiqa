package io.daqiqa.promise.domain;

import io.daqiqa.kernel.StoreId;

/**
 * Outbound port: how loaded a store is right now. Version 1 derives it from the store's operational status; a later
 * version will use live picker and rider queues. Called on the promise hot path, so implementations answer from
 * memory.
 */
public interface StoreLoadProvider {

    StoreLoad loadAt(StoreId store);
}
