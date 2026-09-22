package io.daqiqa.ordering.application;

/**
 * The claim is no longer in progress: another attempt with the same key (one that took the claim over while this one
 * stalled) finished the checkout first. Rolls back this attempt's transaction; the attempt then answers with the
 * response the other one stored.
 */
final class ClaimSettledException extends RuntimeException {

    ClaimSettledException(Claim claim) {
        super("Checkout for order " + claim.orderId() + " was settled by another attempt with the same key");
    }
}
