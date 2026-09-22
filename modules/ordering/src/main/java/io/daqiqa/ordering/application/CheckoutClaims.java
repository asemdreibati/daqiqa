package io.daqiqa.ordering.application;

import io.daqiqa.kernel.OrderId;
import io.daqiqa.ordering.application.IdempotencyRecord.ClaimDecision;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Claims, holds, completes and releases idempotency keys for checkouts (ADR-0008). */
@Component
class CheckoutClaims {

    private final IdempotencyKeyRepository keys;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Duration lease;
    private final Duration retention;

    CheckoutClaims(
            IdempotencyKeyRepository keys,
            PlatformTransactionManager transactionManager,
            Clock clock,
            OrderingProperties properties) {
        this.keys = keys;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.lease = properties.idempotency().lease();
        this.retention = properties.idempotency().retention();
    }

    /** The result of claiming a key: work to do, or an answer to replay. */
    sealed interface ClaimResult {

        record Claimed(Claim claim) implements ClaimResult {}

        record Replay(OrderId orderId, StoredResponse response) implements ClaimResult {}
    }

    /**
     * Claims {@code key} for a request, in its own short transaction, so the claim is visible to concurrent retries
     * before any slow work starts.
     *
     * <p>A new key is claimed together with the id of the order it will create. The id is fixed here, once, because
     * every later step is idempotent by it: it is the reservation's reference, the payment provider's idempotency key
     * and the order's primary key. A retry that takes over a claim whose instance died therefore continues the same
     * order instead of creating a second one.
     *
     * @throws IdempotencyKeyReusedException if the key was used for a different request
     * @throws CheckoutInProgressException if a request with this key is being processed and its lease is still live
     */
    ClaimResult claim(IdempotencyKey key, RequestFingerprint fingerprint) {
        return Objects.requireNonNull(transaction.execute(status -> claimInTransaction(key, fingerprint)));
    }

    private ClaimResult claimInTransaction(IdempotencyKey key, RequestFingerprint fingerprint) {
        Instant now = clock.instant();
        // At most two rounds: between a conflicting insert and the lock, the row can only disappear by being purged as
        // expired, and then the second insert succeeds.
        for (int round = 0; round < 2; round++) {
            Claim fresh = new Claim(key, OrderId.newId());
            if (keys.insertInProgress(fresh, fingerprint, now.plus(lease), now.plus(retention))) {
                return new ClaimResult.Claimed(fresh);
            }
            Optional<IdempotencyRecord> existing = keys.lock(key);
            if (existing.isPresent()) {
                return decide(key, existing.get(), fingerprint, now);
            }
        }
        throw new IllegalStateException("Idempotency key could be neither claimed nor read");
    }

    private ClaimResult decide(
            IdempotencyKey key, IdempotencyRecord existing, RequestFingerprint fingerprint, Instant now) {
        return switch (existing.decide(fingerprint, now)) {
            case ClaimDecision.Replay(StoredResponse response) -> new ClaimResult.Replay(existing.orderId(), response);
            case ClaimDecision.KeyReused reused -> throw new IdempotencyKeyReusedException();
            case ClaimDecision.InProgress(Duration retryAfter) -> throw new CheckoutInProgressException(retryAfter);
            case ClaimDecision.TakeOver takeOver -> {
                Claim claim = new Claim(key, existing.orderId());
                keys.renewLease(claim, now.plus(lease));
                yield new ClaimResult.Claimed(claim);
            }
            case ClaimDecision.Expired expired -> {
                Claim claim = new Claim(key, OrderId.newId());
                keys.restartInProgress(claim, fingerprint, now.plus(lease), now.plus(retention));
                yield new ClaimResult.Claimed(claim);
            }
        };
    }

    /**
     * Locks the claim's key for the rest of the caller's transaction, proving the claim is still in progress. Every
     * transaction that acts on a claim starts with this, which serialises all attempts with the same key: an attempt
     * that stalled past its lease cannot settle an order that a newer attempt already settled.
     *
     * @throws ClaimSettledException if another attempt has completed the claim
     */
    void hold(Claim claim) {
        if (!keys.lockInProgress(claim)) {
            throw new ClaimSettledException(claim);
        }
    }

    /** Stores the claim's final response. Runs in the caller's transaction, after {@link #hold}. */
    void complete(Claim claim, StoredResponse response) {
        keys.complete(claim, response);
    }

    /** The response a completed claim holds. */
    StoredResponse storedResponse(Claim claim) {
        return keys.findResponse(claim)
                .orElseThrow(() -> new IllegalStateException("Checkout for order " + claim.orderId()
                        + " has no stored response although its claim was settled"));
    }

    /**
     * Gives the claim up after an unexpected failure by ending its lease now: the client may retry at once, and the
     * retry takes the claim over and continues the same order. A single statement, so it needs no transaction.
     */
    void release(Claim claim) {
        keys.expireLease(claim, clock.instant());
    }
}
