package io.daqiqa.ordering.application;

import java.time.Instant;
import java.util.Optional;

/**
 * Outbound port: where idempotency keys and their stored responses live. Methods join the caller's transaction; the
 * locking reads hold their row lock until it ends.
 */
public interface IdempotencyKeyRepository {

    /**
     * Stores a new in-progress claim.
     *
     * @return {@code false}, writing nothing, if the key already exists (whatever its state)
     */
    boolean insertInProgress(Claim claim, RequestFingerprint fingerprint, Instant lockedUntil, Instant expiresAt);

    /** The stored key, locked until the current transaction ends. */
    Optional<IdempotencyRecord> lock(IdempotencyKey key);

    /**
     * Locks the key if it is still in progress for {@code claim}'s order, until the current transaction ends.
     *
     * @return {@code false} if the key has completed (or was restarted for another order) in the meantime
     */
    boolean lockInProgress(Claim claim);

    /** Gives the in-progress claim a new lease (a retry took it over). */
    void renewLease(Claim claim, Instant lockedUntil);

    /** Replaces an expired key with a new in-progress claim for another request. */
    void restartInProgress(Claim claim, RequestFingerprint fingerprint, Instant lockedUntil, Instant expiresAt);

    /**
     * Completes the in-progress claim with its final response.
     *
     * @throws IllegalStateException if the key is not in progress for {@code claim}'s order
     */
    void complete(Claim claim, StoredResponse response);

    /** The response of the claim, if it has completed. */
    Optional<StoredResponse> findResponse(Claim claim);

    /** Ends the lease of the in-progress claim at {@code now}, so that the next retry takes it over at once. */
    void expireLease(Claim claim, Instant now);

    /**
     * Deletes up to {@code limit} keys that expired at or before {@code now}.
     *
     * @return how many were deleted
     */
    int deleteExpired(Instant now, int limit);
}
