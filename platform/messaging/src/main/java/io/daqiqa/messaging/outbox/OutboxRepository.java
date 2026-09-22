package io.daqiqa.messaging.outbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL over {@code messaging.outbox_event}. Joins whatever transaction is active on the calling thread. */
@Repository
class OutboxRepository {

    /**
     * Key of the transaction-scoped advisory lock that makes one relay the leader. Arbitrary but fixed: the ASCII bytes
     * of "daqiqa" followed by 0001, so it cannot collide by accident with another advisory lock of this application.
     */
    static final long RELAY_LOCK_KEY = 0x6461_7169_7161_0001L;

    private static final int MAX_ERROR_LENGTH = 2_000;

    /*
     * The relay's statements, each written so that the partial index on unpublished rows applies: every one repeats
     * the index predicate (published_at IS NULL). OutboxQueryPlanTest keeps it that way.
     */

    static final String LOCK_NEXT_BATCH = """
            SELECT id, seq, type, topic, partition_key, payload, occurred_at, attempts, next_attempt_at
            FROM messaging.outbox_event
            WHERE published_at IS NULL
            ORDER BY seq
            LIMIT :limit
            FOR UPDATE SKIP LOCKED""";

    static final String MARK_PUBLISHED = """
            UPDATE messaging.outbox_event SET published_at = :publishedAt
            WHERE seq = ANY(:seqs) AND published_at IS NULL""";

    static final String COUNT_UNPUBLISHED = "SELECT count(*) FROM messaging.outbox_event WHERE published_at IS NULL";

    private final JdbcClient jdbc;

    OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(
            UUID id,
            String type,
            String topic,
            String partitionKey,
            String payload,
            Instant occurredAt,
            Instant createdAt) {
        jdbc.sql("""
                        INSERT INTO messaging.outbox_event
                            (id, type, topic, partition_key, payload, occurred_at, created_at)
                        VALUES (:id, :type, :topic, :partitionKey, CAST(:payload AS jsonb), :occurredAt, :createdAt)""")
                .param("id", id)
                .param("type", type)
                .param("topic", topic)
                .param("partitionKey", partitionKey)
                .param("payload", payload)
                .param("occurredAt", utc(occurredAt))
                .param("createdAt", utc(createdAt))
                .update();
    }

    /**
     * Tries to become the relay leader for the current transaction. Returns immediately (never waits): {@code false}
     * means another instance is relaying right now. The lock is released automatically at commit or rollback, so a
     * crashed leader never blocks the others.
     */
    boolean tryAcquireRelayLock() {
        return Boolean.TRUE.equals(jdbc.sql("SELECT pg_try_advisory_xact_lock(:key)")
                .param("key", RELAY_LOCK_KEY)
                .query(Boolean.class)
                .single());
    }

    /**
     * Locks the next unpublished events in publication order. {@code SKIP LOCKED} is defence in depth: the advisory
     * lock already keeps a second relay out, but should one ever run anyway it takes other rows instead of waiting,
     * and still never publishes a row that is being published.
     */
    List<PendingEvent> lockNextBatch(int limit) {
        return jdbc.sql(LOCK_NEXT_BATCH)
                .param("limit", limit)
                .query(OutboxRepository::pendingEvent)
                .list();
    }

    void markPublished(List<PendingEvent> events, Instant publishedAt) {
        if (events.isEmpty()) {
            return;
        }
        long[] seqs = events.stream().mapToLong(PendingEvent::seq).toArray();
        jdbc.sql(MARK_PUBLISHED)
                .param("publishedAt", utc(publishedAt))
                .param("seqs", seqs)
                .update();
    }

    void recordFailure(PendingEvent event, String error, Instant nextAttemptAt) {
        jdbc.sql("""
                        UPDATE messaging.outbox_event
                        SET attempts = attempts + 1, last_error = :error, next_attempt_at = :nextAttemptAt
                        WHERE id = :id""")
                .param("id", event.id())
                .param("nextAttemptAt", utc(nextAttemptAt))
                .param("error", error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH))
                .update();
    }

    /** Served by the partial index on unpublished rows, so it stays cheap even with a large table. */
    long countUnpublished() {
        return jdbc.sql(COUNT_UNPUBLISHED).query(Long.class).single();
    }

    /**
     * Deletes up to {@code limit} events published before {@code cutoff}; returns how many were deleted. Bounded so a
     * large purge is a series of short statements, not one long transaction holding locks.
     */
    int deletePublishedBefore(Instant cutoff, int limit) {
        return jdbc.sql("""
                        DELETE FROM messaging.outbox_event
                        WHERE published_at < :cutoff
                          AND id IN (SELECT id FROM messaging.outbox_event
                                     WHERE published_at < :cutoff
                                     ORDER BY published_at
                                     LIMIT :limit)""").param("cutoff", utc(cutoff)).param("limit", limit).update();
    }

    private static PendingEvent pendingEvent(ResultSet rs, int row) throws SQLException {
        return new PendingEvent(
                rs.getObject("id", UUID.class),
                rs.getLong("seq"),
                rs.getString("type"),
                rs.getString("topic"),
                rs.getString("partition_key"),
                rs.getString("payload"),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                rs.getInt("attempts"),
                instantOrNull(rs.getObject("next_attempt_at", OffsetDateTime.class)));
    }

    private static @Nullable Instant instantOrNull(@Nullable OffsetDateTime timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
