package io.daqiqa.promise.api;

import io.daqiqa.kernel.PromiseId;
import io.daqiqa.kernel.StoreId;
import java.time.Instant;

/**
 * A promise whose token passed verification: untampered, unexpired, and issued for exactly the cart being ordered.
 *
 * @param etaMinutes minutes from order placement to delivery that the customer was shown
 */
public record VerifiedPromise(
        PromiseId promiseId, StoreId storeId, DeliveryTier tier, int etaMinutes, Instant issuedAt, Instant expiresAt) {

    /** The delivery deadline for an order placed at {@code placedAt} under this promise. */
    public Instant deliverBy(Instant placedAt) {
        return placedAt.plusSeconds(etaMinutes * 60L);
    }
}
