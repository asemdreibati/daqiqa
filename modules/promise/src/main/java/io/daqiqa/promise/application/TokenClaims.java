package io.daqiqa.promise.application;

import io.daqiqa.kernel.PromiseId;
import io.daqiqa.kernel.StoreId;
import io.daqiqa.promise.api.DeliveryTier;
import io.daqiqa.promise.api.VerifiedPromise;
import java.time.Instant;
import java.util.Optional;

/**
 * What a promise token asserts, in the payload format of ADR-0007:
 * {@code promiseId|storeId|tier|etaMinutes|issuedAtEpochSecond|expiresAtEpochSecond|basketHash}. None of the fields
 * can contain {@code |}, so the format is unambiguous.
 */
record TokenClaims(
        PromiseId promiseId,
        StoreId storeId,
        DeliveryTier tier,
        int etaMinutes,
        long issuedAtEpochSecond,
        long expiresAtEpochSecond,
        String basketHash) {

    private static final String SEPARATOR = "|";
    private static final int FIELDS = 7;

    String encode() {
        return String.join(
                SEPARATOR,
                promiseId.toString(),
                storeId.value(),
                tier.name(),
                Integer.toString(etaMinutes),
                Long.toString(issuedAtEpochSecond),
                Long.toString(expiresAtEpochSecond),
                basketHash);
    }

    /** Parses an encoded payload; empty if it is not one. */
    static Optional<TokenClaims> decode(String payload) {
        String[] fields = payload.split("\\|", -1);
        if (fields.length != FIELDS) {
            return Optional.empty();
        }
        try {
            return Optional.of(new TokenClaims(
                    PromiseId.of(fields[0]),
                    StoreId.of(fields[1]),
                    DeliveryTier.valueOf(fields[2]),
                    Integer.parseInt(fields[3]),
                    Long.parseLong(fields[4]),
                    Long.parseLong(fields[5]),
                    fields[6]));
        } catch (IllegalArgumentException notAClaim) {
            // UUID, store id, tier and number parsers all report bad input this way.
            return Optional.empty();
        }
    }

    Instant expiresAt() {
        return Instant.ofEpochSecond(expiresAtEpochSecond);
    }

    VerifiedPromise toVerifiedPromise() {
        return new VerifiedPromise(
                promiseId, storeId, tier, etaMinutes, Instant.ofEpochSecond(issuedAtEpochSecond), expiresAt());
    }
}
