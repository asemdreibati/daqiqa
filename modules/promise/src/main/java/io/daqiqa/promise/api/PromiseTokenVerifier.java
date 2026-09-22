package io.daqiqa.promise.api;

import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.kernel.SkuQuantity;
import java.util.List;

/**
 * Verifies promise tokens at checkout. Stateless apart from an in-memory view of store status: no database lookup,
 * just cryptography, a clock and the coverage snapshot.
 */
public interface PromiseTokenVerifier {

    /**
     * Checks that the token is ours, untampered and unexpired, and that the order matches what was promised.
     *
     * @param token the token returned with the promise
     * @param lines the basket being ordered; must match the promised basket exactly (after merging duplicates)
     * @param deliveryLocation where the order goes; must be within the configured tolerance of the location the
     *     promise was computed for, because travel time — and therefore the promise — depends on it
     * @throws InvalidPromiseException if the token is malformed, tampered with, expired, for another basket or another
     *     location, or if the promised store no longer accepts orders
     */
    VerifiedPromise verify(String token, List<SkuQuantity> lines, GeoPoint deliveryLocation);
}
