package io.daqiqa.ordering.adapter.out.persistence;

import io.daqiqa.kernel.OrderId;
import io.daqiqa.ordering.application.Claim;
import io.daqiqa.ordering.application.IdempotencyKey;
import io.daqiqa.ordering.application.IdempotencyKeyRepository;
import io.daqiqa.ordering.application.IdempotencyRecord;
import io.daqiqa.ordering.application.RequestFingerprint;
import io.daqiqa.ordering.application.StoredResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Idempotency keys in {@code ordering.idempotency_key}. Statements that act on a claim match the key, its order and
 * the in-progress state together, so a claim that was completed or restarted meanwhile is never touched.
 */
@Repository
class JdbcIdempotencyKeyRepository implements IdempotencyKeyRepository {

    private final JdbcClient jdbc;

    JdbcIdempotencyKeyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertInProgress(
            Claim claim, RequestFingerprint fingerprint, Instant lockedUntil, Instant expiresAt) {
        // ON CONFLICT DO NOTHING: concurrent first requests with the same key queue on its primary key, and all but
        // one find it taken, with no error to handle.
        int inserted = forClaim("""
                        INSERT INTO ordering.idempotency_key
                            (customer_id, key, request_hash, order_id, status, locked_until, expires_at)
                        VALUES (:customerId, :key, :requestHash, :orderId, 'IN_PROGRESS', :lockedUntil, :expiresAt)
                        ON CONFLICT (customer_id, key) DO NOTHING""", claim)
                .param("requestHash", fingerprint.value())
                .param("lockedUntil", utc(lockedUntil))
                .param("expiresAt", utc(expiresAt))
                .update();
        return inserted == 1;
    }

    @Override
    public Optional<IdempotencyRecord> lock(IdempotencyKey key) {
        return jdbc.sql("""
                        SELECT request_hash, order_id, status, locked_until, response_status, response_body,
                               expires_at
                        FROM ordering.idempotency_key
                        WHERE customer_id = :customerId AND key = :key
                        FOR UPDATE""")
                .param("customerId", key.customerId().value())
                .param("key", key.value())
                .query(JdbcIdempotencyKeyRepository::record)
                .optional();
    }

    @Override
    public boolean lockInProgress(Claim claim) {
        return forClaim("""
                        SELECT 1 FROM ordering.idempotency_key
                        WHERE customer_id = :customerId AND key = :key AND order_id = :orderId
                          AND status = 'IN_PROGRESS'
                        FOR UPDATE""", claim).query(Integer.class).optional().isPresent();
    }

    @Override
    public void renewLease(Claim claim, Instant lockedUntil) {
        int updated =
                forClaim("""
                        UPDATE ordering.idempotency_key SET locked_until = :lockedUntil
                        WHERE customer_id = :customerId AND key = :key AND order_id = :orderId
                          AND status = 'IN_PROGRESS'""", claim).param("lockedUntil", utc(lockedUntil)).update();
        requireOneRow(updated, claim);
    }

    @Override
    public void restartInProgress(Claim claim, RequestFingerprint fingerprint, Instant lockedUntil, Instant expiresAt) {
        int updated = forClaim("""
                        UPDATE ordering.idempotency_key
                        SET request_hash = :requestHash, order_id = :orderId, status = 'IN_PROGRESS',
                            locked_until = :lockedUntil, response_status = NULL, response_body = NULL,
                            expires_at = :expiresAt
                        WHERE customer_id = :customerId AND key = :key""", claim)
                .param("requestHash", fingerprint.value())
                .param("lockedUntil", utc(lockedUntil))
                .param("expiresAt", utc(expiresAt))
                .update();
        requireOneRow(updated, claim);
    }

    @Override
    public void complete(Claim claim, StoredResponse response) {
        int updated = forClaim("""
                        UPDATE ordering.idempotency_key
                        SET status = 'COMPLETED', locked_until = NULL,
                            response_status = :responseStatus, response_body = :responseBody
                        WHERE customer_id = :customerId AND key = :key AND order_id = :orderId
                          AND status = 'IN_PROGRESS'""", claim)
                .param("responseStatus", response.status())
                .param("responseBody", response.body())
                .update();
        requireOneRow(updated, claim);
    }

    @Override
    public Optional<StoredResponse> findResponse(Claim claim) {
        return forClaim("""
                        SELECT response_status, response_body FROM ordering.idempotency_key
                        WHERE customer_id = :customerId AND key = :key AND order_id = :orderId
                          AND status = 'COMPLETED'""", claim)
                .query((rs, row) -> new StoredResponse(rs.getInt("response_status"), rs.getString("response_body")))
                .optional();
    }

    @Override
    public void expireLease(Claim claim, Instant now) {
        forClaim("""
                        UPDATE ordering.idempotency_key SET locked_until = :now
                        WHERE customer_id = :customerId AND key = :key AND order_id = :orderId
                          AND status = 'IN_PROGRESS'""", claim).param("now", utc(now)).update();
    }

    @Override
    public int deleteExpired(Instant now, int limit) {
        // The outer condition is evaluated again on the row being deleted: a key that a request reclaimed after the
        // sub-select picked it (its expiry moved forward) survives.
        return jdbc.sql("""
                        DELETE FROM ordering.idempotency_key
                        WHERE expires_at <= :now
                          AND (customer_id, key) IN (SELECT customer_id, key FROM ordering.idempotency_key
                                                     WHERE expires_at <= :now
                                                     ORDER BY expires_at
                                                     LIMIT :limit)""").param("now", utc(now)).param("limit", limit).update();
    }

    /** A statement with the claim's customer, key and order bound. */
    private JdbcClient.StatementSpec forClaim(String sql, Claim claim) {
        return jdbc.sql(sql)
                .param("customerId", claim.key().customerId().value())
                .param("key", claim.key().value())
                .param("orderId", claim.orderId().value());
    }

    private static void requireOneRow(int updated, Claim claim) {
        if (updated != 1) {
            throw new IllegalStateException(
                    "The idempotency key of order " + claim.orderId() + " is not in the state this step expects");
        }
    }

    private static IdempotencyRecord record(ResultSet rs, int row) throws SQLException {
        IdempotencyRecord.State state = "COMPLETED".equals(rs.getString("status"))
                ? new IdempotencyRecord.State.Completed(
                        new StoredResponse(rs.getInt("response_status"), rs.getString("response_body")))
                : new IdempotencyRecord.State.InProgress(
                        rs.getObject("locked_until", OffsetDateTime.class).toInstant());
        return new IdempotencyRecord(
                new RequestFingerprint(rs.getString("request_hash")),
                OrderId.of(rs.getObject("order_id", UUID.class)),
                state,
                rs.getObject("expires_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
