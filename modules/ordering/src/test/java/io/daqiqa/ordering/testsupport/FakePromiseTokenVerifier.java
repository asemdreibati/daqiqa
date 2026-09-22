package io.daqiqa.ordering.testsupport;

import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.kernel.PromiseId;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.kernel.StoreId;
import io.daqiqa.promise.api.DeliveryTier;
import io.daqiqa.promise.api.InvalidPromiseException;
import io.daqiqa.promise.api.InvalidPromiseException.Reason;
import io.daqiqa.promise.api.PromiseTokenVerifier;
import io.daqiqa.promise.api.VerifiedPromise;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verifier honouring the {@link PromiseTokenVerifier} contract without cryptography: tokens are issued by the test and
 * fail for the same reasons as real ones (unknown, tampered, expired against the clock, another basket, another
 * address, a store that stopped taking orders). An exact location match stands in for the real verifier's tolerance.
 */
public final class FakePromiseTokenVerifier implements PromiseTokenVerifier {

    /** ADR-0007's default validity. */
    public static final Duration VALIDITY = Duration.ofSeconds(90);

    private final Clock clock;
    private final Map<String, Issued> issued = new ConcurrentHashMap<>();
    private final Set<String> tampered = ConcurrentHashMap.newKeySet();
    private final Set<StoreId> unavailable = ConcurrentHashMap.newKeySet();

    public FakePromiseTokenVerifier(Clock clock) {
        this.clock = clock;
    }

    /** A token promising {@code etaMinutes} from {@code store} for exactly this basket and address, valid from now. */
    public String issue(
            StoreId store, DeliveryTier tier, int etaMinutes, List<SkuQuantity> basket, GeoPoint deliveryLocation) {
        Instant now = clock.instant();
        VerifiedPromise promise =
                new VerifiedPromise(PromiseId.newId(), store, tier, etaMinutes, now, now.plus(VALIDITY));
        String token = "v1.test." + promise.promiseId();
        issued.put(token, new Issued(promise, canonical(basket), deliveryLocation));
        return token;
    }

    public VerifiedPromise promiseOf(String token) {
        return issued.get(token).promise();
    }

    /** From now on the token fails as if someone had altered it. */
    public void tamper(String token) {
        tampered.add(token);
    }

    /** The store stops taking orders (paused or closed), so its outstanding promises can no longer be honoured. */
    public void storeStopsTakingOrders(StoreId store) {
        unavailable.add(store);
    }

    public void reset() {
        issued.clear();
        tampered.clear();
        unavailable.clear();
    }

    @Override
    public VerifiedPromise verify(String token, List<SkuQuantity> lines, GeoPoint deliveryLocation) {
        Issued promise = issued.get(token);
        if (promise == null) {
            throw new InvalidPromiseException(Reason.MALFORMED, "Not a token we issued");
        }
        if (tampered.contains(token)) {
            throw new InvalidPromiseException(Reason.TAMPERED, "Signature mismatch");
        }
        if (!clock.instant().isBefore(promise.promise().expiresAt())) {
            throw new InvalidPromiseException(Reason.EXPIRED, "Promise expired");
        }
        if (!canonical(lines).equals(promise.basket())) {
            throw new InvalidPromiseException(Reason.CART_MISMATCH, "Promise was made for another basket");
        }
        if (!deliveryLocation.equals(promise.deliveryLocation())) {
            throw new InvalidPromiseException(Reason.LOCATION_MISMATCH, "Promise was made for another address");
        }
        if (unavailable.contains(promise.promise().storeId())) {
            throw new InvalidPromiseException(Reason.STORE_UNAVAILABLE, "The store stopped taking orders");
        }
        return promise.promise();
    }

    private static List<SkuQuantity> canonical(List<SkuQuantity> basket) {
        return SkuQuantity.merge(basket).stream()
                .sorted(Comparator.comparing(line -> line.sku().value()))
                .toList();
    }

    private record Issued(VerifiedPromise promise, List<SkuQuantity> basket, GeoPoint deliveryLocation) {}
}
