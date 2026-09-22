package io.daqiqa.network.domain;

import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.kernel.LocalizedText;
import io.daqiqa.kernel.StoreId;
import io.daqiqa.network.api.StoreStatus;
import java.util.Objects;

/**
 * A dark store. Invariants: it has at least zero active pickers and a service radius between
 * {@value #MIN_SERVICE_RADIUS_METERS} m and {@value #MAX_SERVICE_RADIUS_METERS} m (the database enforces the same).
 *
 * <p>{@link StoreStatus} is the context's published enum; the domain uses it directly so that "which statuses accept
 * orders" has a single source of truth.
 */
public record Store(
        StoreId id,
        LocalizedText name,
        GeoPoint location,
        StoreStatus status,
        int activePickers,
        int serviceRadiusMeters) {

    /** Below this a store would cover a handful of buildings: not worth a dark store. */
    public static final int MIN_SERVICE_RADIUS_METERS = 500;

    /** Beyond this a rider cannot make a 15 to 30 minute promise in city traffic. */
    public static final int MAX_SERVICE_RADIUS_METERS = 10_000;

    public Store {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(status, "status");
        if (activePickers < 0) {
            throw new IllegalArgumentException("Active pickers of " + id + " cannot be negative, was " + activePickers);
        }
        if (serviceRadiusMeters < MIN_SERVICE_RADIUS_METERS || serviceRadiusMeters > MAX_SERVICE_RADIUS_METERS) {
            throw new IllegalArgumentException(
                    "Service radius of " + id + " must be between " + MIN_SERVICE_RADIUS_METERS + " and "
                            + MAX_SERVICE_RADIUS_METERS + " m, was " + serviceRadiusMeters);
        }
    }

    public boolean acceptsOrders() {
        return status.acceptsOrders();
    }
}
