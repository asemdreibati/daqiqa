package io.daqiqa.network.api;

import io.daqiqa.kernel.StoreId;
import java.util.List;
import java.util.Optional;

/** Lookup of dark stores. */
public interface StoreDirectory {

    Optional<DarkStore> find(StoreId storeId);

    /** All stores, whatever their status, ordered by id. */
    List<DarkStore> findAll();
}
