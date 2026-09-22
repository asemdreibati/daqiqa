package io.daqiqa.promise.application;

import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.kernel.PromiseId;
import io.daqiqa.kernel.StoreId;
import io.daqiqa.promise.api.DeliveryTier;
import io.daqiqa.promise.api.VerifiedPromise;
import java.time.Instant;
import java.util.Optional;

/**
 * What a promise token asserts, in the payload format of ADR-0007:
 * {@code promiseId|storeId|tier|etaMinutes|issuedAtEpochSecond|expiresAtEpochSecond|basketHash|latE6|lngE6}. None of
 * the fields can contain {@code |}, so the format is unambiguous.
 *
 * <p>The location the promise was computed for is signed as integer micro-degrees ({@code Math.round(degrees × 10⁶)},
 * about 11 cm): integers have one text form, so the payload is the same on every JVM and in every locale, and the
 * precision is far finer than any delivery tolerance.
 */
record TokenClaims(
        PromiseId promiseId,
        StoreId storeId,
        DeliveryTier tier,
        int etaMinutes,
        long issuedAtEpochSecond,
        long expiresAtEpochSecond,
        String basketHash,
        int latitudeE6,
        int longitudeE6) {

    private static final String SEPARATOR = "|";
    private static final int FIELDS = 9;
    private static final double MICRO_DEGREES = 1e6;

    static int toMicroDegrees(double degrees) {
        return Math.toIntExact(Math.round(degrees * MICRO_DEGREES));
    }

    String encode() {
        return String.join(
                SEPARATOR,
                promiseId.toString(),
                storeId.value(),
                tier.name(),
                Integer.toString(etaMinutes),
                Long.toString(issuedAtEpochSecond),
                Long.toString(expiresAtEpochSecond),
                basketHash,
                Integer.toString(latitudeE6),
                Integer.toString(longitudeE6));
    }

    /** Parses an encoded payload; empty if it is not one. */
    static Optional<TokenClaims> decode(String payload) {
        String[] fields = payload.split("\\|", -1);
        if (fields.length != FIELDS) {
            return Optional.empty();
        }
        try {
            TokenClaims claims = new TokenClaims(
                    PromiseId.of(fields[0]),
                    StoreId.of(fields[1]),
                    DeliveryTier.valueOf(fields[2]),
                    Integer.parseInt(fields[3]),
                    Long.parseLong(fields[4]),
                    Long.parseLong(fields[5]),
                    fields[6],
                    Integer.parseInt(fields[7]),
                    Integer.parseInt(fields[8]));
            claims.location(); // rejects coordinates outside the globe
            return Optional.of(claims);
        } catch (IllegalArgumentException notAClaim) {
            // UUID, store id, tier, number and coordinate parsers all report bad input this way.
            return Optional.empty();
        }
    }

    /** Where the promise was computed for, to the micro-degree. */
    GeoPoint location() {
        return GeoPoint.of(latitudeE6 / MICRO_DEGREES, longitudeE6 / MICRO_DEGREES);
    }

    Instant expiresAt() {
        return Instant.ofEpochSecond(expiresAtEpochSecond);
    }

    VerifiedPromise toVerifiedPromise() {
        return new VerifiedPromise(
                promiseId, storeId, tier, etaMinutes, Instant.ofEpochSecond(issuedAtEpochSecond), expiresAt());
    }
}
