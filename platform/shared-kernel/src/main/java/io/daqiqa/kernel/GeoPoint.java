package io.daqiqa.kernel;

/** A WGS84 coordinate. */
public record GeoPoint(double latitude, double longitude) {

    /** Mean Earth radius (IUGG), in metres. */
    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    public GeoPoint {
        // Written as negated range checks so that NaN is rejected too.
        if (!(latitude >= -90.0 && latitude <= 90.0)) {
            throw new IllegalArgumentException("Latitude out of range: " + latitude);
        }
        if (!(longitude >= -180.0 && longitude <= 180.0)) {
            throw new IllegalArgumentException("Longitude out of range: " + longitude);
        }
    }

    public static GeoPoint of(double latitude, double longitude) {
        return new GeoPoint(latitude, longitude);
    }

    /** Great-circle distance (haversine), in metres. Accurate to ~0.5% — plenty for city-scale delivery. */
    public double distanceMetersTo(GeoPoint other) {
        double lat1 = Math.toRadians(latitude);
        double lat2 = Math.toRadians(other.latitude);
        double dLat = lat2 - lat1;
        double dLng = Math.toRadians(other.longitude - longitude);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
