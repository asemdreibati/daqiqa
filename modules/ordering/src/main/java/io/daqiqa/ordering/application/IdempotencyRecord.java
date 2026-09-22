package io.daqiqa.ordering.application;

import io.daqiqa.kernel.OrderId;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * An idempotency key as stored, and the rule that decides what a new request carrying it gets (ADR-0008).
 *
 * @param fingerprint of the request that first claimed the key
 * @param orderId the order that request creates
 * @param expiresAt when the key is forgotten
 */
public record IdempotencyRecord(RequestFingerprint fingerprint, OrderId orderId, State state, Instant expiresAt) {

    public IdempotencyRecord {
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /** Where the request that claimed the key is. */
    public sealed interface State {

        /** Being processed; the claim is exclusive until {@code lockedUntil} (its lease). */
        record InProgress(Instant lockedUntil) implements State {}

        /** Finished with this response. */
        record Completed(StoredResponse response) implements State {}
    }

    /**
     * What a request with fingerprint {@code request} gets at {@code now}:
     *
     * <ul>
     *   <li>an expired key is forgotten and may be claimed afresh;
     *   <li>a different request is refused, whatever the key's state: it can never succeed with this key;
     *   <li>a completed key replays its response;
     *   <li>a key in progress makes the request wait, until its lease runs out: then the instance processing it is
     *       presumed dead and the request takes the claim over.
     * </ul>
     */
    ClaimDecision decide(RequestFingerprint request, Instant now) {
        if (!now.isBefore(expiresAt)) {
            return new ClaimDecision.Expired();
        }
        if (!fingerprint.equals(request)) {
            return new ClaimDecision.KeyReused();
        }
        return switch (state) {
            case State.Completed(StoredResponse response) -> new ClaimDecision.Replay(response);
            case State.InProgress(Instant lockedUntil)
            when now.isBefore(lockedUntil) -> new ClaimDecision.InProgress(Duration.between(now, lockedUntil));
            case State.InProgress expiredLease -> new ClaimDecision.TakeOver();
        };
    }

    /** The outcomes of {@link #decide}. */
    sealed interface ClaimDecision {

        record Replay(StoredResponse response) implements ClaimDecision {}

        record KeyReused() implements ClaimDecision {}

        record InProgress(Duration retryAfter) implements ClaimDecision {}

        record TakeOver() implements ClaimDecision {}

        record Expired() implements ClaimDecision {}
    }
}
