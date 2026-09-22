package io.daqiqa.ordering.application;

import io.daqiqa.kernel.OrderId;
import io.daqiqa.ordering.application.CheckoutClaims.ClaimResult;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

/**
 * Places orders ({@code POST /v1/orders}), exactly once per idempotency key (ADR-0008, docs/design/ordering.md).
 *
 * <p>The first request with a key claims it and does the work; the business outcome, success or rejection, is
 * stored on the key in the same transaction that settles the order, and every retry receives it byte for byte. An
 * unexpected failure stores nothing: the claim is released, so the client may retry with the same key and continue
 * the same order.
 */
@Service
public class CheckoutService {

    private final CheckoutClaims claims;
    private final CheckoutFlow flow;
    private final CheckoutMetrics metrics;

    CheckoutService(CheckoutClaims claims, CheckoutFlow flow, CheckoutMetrics metrics) {
        this.claims = claims;
        this.flow = flow;
        this.metrics = metrics;
    }

    /**
     * @param idempotencyKey the client's {@code Idempotency-Key}, scoped to the command's customer
     * @return the stored response: the outcome of this request, or of the earlier one with the same key
     * @throws IdempotencyKeyReusedException if the key was already used for a different request
     * @throws CheckoutInProgressException if the first request with this key is still being processed
     */
    public CheckoutResponse placeOrder(String idempotencyKey, PlaceOrderCommand command) {
        Timer.Sample sample = metrics.start();
        CheckoutOutcome outcome = CheckoutOutcome.FAILED;
        try {
            IdempotencyKey key = new IdempotencyKey(command.customerId(), idempotencyKey);
            return switch (claims.claim(key, command.fingerprint())) {
                case ClaimResult.Replay(OrderId orderId, StoredResponse response) -> {
                    outcome = CheckoutOutcome.REPLAYED;
                    yield new CheckoutResponse(orderId, response, true);
                }
                case ClaimResult.Claimed(Claim claim) -> {
                    Settlement settlement = process(claim, command);
                    outcome = settlement.outcome();
                    yield new CheckoutResponse(
                            claim.orderId(), settlement.response(), outcome == CheckoutOutcome.REPLAYED);
                }
            };
        } catch (IdempotencyKeyReusedException e) {
            outcome = CheckoutOutcome.IDEMPOTENCY_KEY_REUSED;
            throw e;
        } catch (CheckoutInProgressException e) {
            outcome = CheckoutOutcome.IDEMPOTENCY_REQUEST_IN_PROGRESS;
            throw e;
        } finally {
            metrics.record(sample, outcome);
        }
    }

    private Settlement process(Claim claim, PlaceOrderCommand command) {
        try {
            return flow.run(claim, command);
        } catch (RuntimeException failure) {
            // Nothing deterministic happened that could be stored: give the claim up so a retry can proceed.
            try {
                claims.release(claim);
            } catch (RuntimeException releaseFailure) {
                // The lease runs out on its own; report the failure that matters.
                failure.addSuppressed(releaseFailure);
            }
            throw failure;
        }
    }
}
