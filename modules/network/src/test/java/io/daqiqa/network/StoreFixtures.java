package io.daqiqa.network;

import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.kernel.LocalizedText;
import io.daqiqa.kernel.StoreId;
import io.daqiqa.network.api.StoreStatus;
import io.daqiqa.network.domain.Store;

/** Stores and places around Dubai for tests. */
public final class StoreFixtures {

    public static final GeoPoint DUBAI_MARINA = GeoPoint.of(25.0805, 55.1403);
    public static final GeoPoint JLT = GeoPoint.of(25.0693, 55.1413);
    public static final GeoPoint BUSINESS_BAY = GeoPoint.of(25.1850, 55.2650);

    /** Mean Earth radius, as used by {@link GeoPoint#distanceMetersTo}. */
    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private StoreFixtures() {}

    public static Store store(String id, GeoPoint location, int serviceRadiusMeters, StoreStatus status) {
        return new Store(
                StoreId.of(id),
                LocalizedText.of("Store " + id, "متجر " + id),
                location,
                status,
                6,
                serviceRadiusMeters);
    }

    public static Store openStore(String id, GeoPoint location, int serviceRadiusMeters) {
        return store(id, location, serviceRadiusMeters, StoreStatus.OPEN);
    }

    /**
     * The point {@code meters} away from {@code origin} in direction {@code bearingDegrees} (0 = north), on the same
     * sphere {@link GeoPoint#distanceMetersTo} measures on, so the distance back to {@code origin} is {@code meters}.
     */
    public static GeoPoint offset(GeoPoint origin, double bearingDegrees, double meters) {
        double angular = meters / EARTH_RADIUS_METERS;
        double bearing = Math.toRadians(bearingDegrees);
        double lat1 = Math.toRadians(origin.latitude());
        double lng1 = Math.toRadians(origin.longitude());
        double lat2 =
                Math.asin(Math.sin(lat1) * Math.cos(angular) + Math.cos(lat1) * Math.sin(angular) * Math.cos(bearing));
        double lng2 = lng1
                + Math.atan2(
                        Math.sin(bearing) * Math.sin(angular) * Math.cos(lat1),
                        Math.cos(angular) - Math.sin(lat1) * Math.sin(lat2));
        // Normalise to [-180, 180): a circle may cross the antimeridian.
        double longitude = (Math.toDegrees(lng2) + 540) % 360 - 180;
        return GeoPoint.of(Math.toDegrees(lat2), longitude);
    }
}
