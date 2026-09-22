package io.daqiqa.network.domain;

import io.daqiqa.kernel.StoreId;
import io.daqiqa.network.api.StoreStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Outbound port: where dark stores are stored. Implemented by a persistence adapter. */
public interface StoreRepository {

    /** Every store, whatever its status, ordered by id. */
    List<Store> findAll();

    Optional<Store> findById(StoreId id);

    /** Sets the status of a store; returns the updated store, or empty if there is no such store. */
    Optional<Store> updateStatus(StoreId id, StoreStatus status, Instant changedAt);
}
