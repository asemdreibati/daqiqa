package io.daqiqa.promise.api;

import io.daqiqa.kernel.SkuQuantity;
import java.util.List;

/** Verifies promise tokens at checkout. Stateless: no database lookup, just cryptography and a clock. */
public interface PromiseTokenVerifier {

    /**
     * @param token the token returned with the promise
     * @param lines the basket being ordered; must match the promised basket exactly (after merging duplicates)
     * @throws InvalidPromiseException if the token is malformed, tampered with, expired, or for another basket
     */
    VerifiedPromise verify(String token, List<SkuQuantity> lines);
}
