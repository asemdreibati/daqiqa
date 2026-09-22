package io.daqiqa.promise.application;

import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.network.api.StoreDirectory;
import io.daqiqa.promise.api.InvalidPromiseException;
import io.daqiqa.promise.api.InvalidPromiseException.Reason;
import io.daqiqa.promise.api.PromiseTokenVerifier;
import io.daqiqa.promise.api.VerifiedPromise;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * Checks at checkout that a promise still holds for the order being placed. In this order:
 *
 * <ol>
 *   <li>the token is ours and intact: {@code MALFORMED}, {@code TAMPERED} ({@link PromiseTokenCodec#authenticate});
 *   <li>it has not expired: {@code EXPIRED} when {@code now ≥ expiresAt} on the injected clock;
 *   <li>it was issued for this basket, duplicates merged: {@code CART_MISMATCH};
 *   <li>it was issued for this delivery location, within the tolerance: {@code LOCATION_MISMATCH}. The ride, and
 *       therefore the promise, depends on where the order goes;
 *   <li>the promised store still accepts orders: {@code STORE_UNAVAILABLE}.
 * </ol>
 *
 * <p>No database access: cryptography, a clock and the network's in-memory store directory. A pause made through this
 * instance is seen at once; one made through another instance up to the network refresh interval later (30 s by
 * default), until status changes are propagated as events.
 */
public final class CheckoutPromiseVerifier implements PromiseTokenVerifier {

    private final PromiseTokenCodec tokens;
    private final StoreDirectory stores;
    private final Clock clock;
    private final double locationToleranceMeters;

    /**
     * @param locationToleranceMeters how far the delivery location may be from the location the promise was made for:
     *     enough to absorb geocoding and GPS noise between quote and checkout, not a change of address
     */
    public CheckoutPromiseVerifier(
            PromiseTokenCodec tokens, StoreDirectory stores, Clock clock, double locationToleranceMeters) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.stores = Objects.requireNonNull(stores, "stores");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (!(locationToleranceMeters > 0 && Double.isFinite(locationToleranceMeters))) {
            throw new IllegalArgumentException(
                    "The location tolerance must be a positive number of metres, was " + locationToleranceMeters);
        }
        this.locationToleranceMeters = locationToleranceMeters;
    }

    @Override
    public VerifiedPromise verify(String token, List<SkuQuantity> lines, GeoPoint deliveryLocation) {
        TokenClaims claims = tokens.authenticate(token);
        if (!clock.instant().isBefore(claims.expiresAt())) {
            throw new InvalidPromiseException(Reason.EXPIRED, "The promise expired at " + claims.expiresAt());
        }
        if (!claims.basketHash().equals(BasketHash.of(lines))) {
            throw new InvalidPromiseException(Reason.CART_MISMATCH, "The promise was made for a different basket");
        }
        // The message states distances only: coordinates are personal data and exception messages end up in logs.
        double distance = claims.location().distanceMetersTo(deliveryLocation);
        if (distance > locationToleranceMeters) {
            throw new InvalidPromiseException(
                    Reason.LOCATION_MISMATCH,
                    "The delivery location is " + Math.round(distance) + " m from the one the promise was made for;"
                            + " at most " + Math.round(locationToleranceMeters) + " m are accepted");
        }
        boolean storeAcceptsOrders = stores.find(claims.storeId())
                .map(store -> store.status().acceptsOrders())
                .orElse(false);
        if (!storeAcceptsOrders) {
            throw new InvalidPromiseException(
                    Reason.STORE_UNAVAILABLE, "Store " + claims.storeId() + " no longer accepts orders");
        }
        return claims.toVerifiedPromise();
    }
}
