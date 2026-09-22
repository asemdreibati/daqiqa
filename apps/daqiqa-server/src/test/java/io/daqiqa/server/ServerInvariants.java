package io.daqiqa.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.daqiqa.contracts.events.OrderAbandonedV1;
import io.daqiqa.contracts.events.OrderPlacedV1;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What Daqiqa promises whatever the concurrency, checked over the whole database of the assembled server after a
 * scenario (docs/architecture/quality-attributes.md, "Server-level invariants"):
 *
 * <ol>
 *   <li><b>The stock ledger balances.</b> For every (store, SKU), {@code reserved} equals the units of its
 *       {@code HELD} and {@code CONFIRMED} reservation lines, and {@code 0 ≤ reserved ≤ on_hand}.
 *   <li><b>Orders and holds pair up.</b> Every live order ({@code AWAITING_PAYMENT}, {@code CONFIRMED}) has exactly
 *       one active reservation, its own, at its store, confirmed exactly when the order is; and every active
 *       reservation belongs to a live order.
 *   <li><b>Every outcome is announced once.</b> Exactly one {@code OrderPlacedV1} per {@code CONFIRMED} order, exactly
 *       one {@code OrderAbandonedV1} per {@code ABANDONED} order (only card orders can be), no order has both, no such
 *       event names an order that does not exist, and every abandoned card order tells payments that an authorization
 *       may exist.
 *   <li><b>The server comes to rest.</b> Once every checkout has been answered no order still awaits its payment, and
 *       the outbox relay publishes every event.
 * </ol>
 *
 * <p>The queries join the modules' schemas, which no module may do. Here it is the point: these invariants span the
 * modules, and only the composition root's tests see all of them at once. All queries read one snapshot (a read-only,
 * repeatable-read transaction), so a job committing meanwhile cannot make balanced books look unbalanced.
 */
final class ServerInvariants {

    /** Generous: the relay publishes every 250 ms, and every checkout has already been answered. */
    private static final Duration REST_TIMEOUT = Duration.ofSeconds(15);

    private static final Duration REST_POLL_INTERVAL = Duration.ofMillis(50);

    private final JdbcClient db;
    private final TransactionTemplate snapshot;

    ServerInvariants(DataSource dataSource) {
        this.db = JdbcClient.create(dataSource);
        this.snapshot = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
    }

    /**
     * Waits for the server to come to rest, then asserts that every invariant holds. A server that does not come to
     * rest in time fails here too, with everything else that is wrong.
     */
    void assertHold() {
        long deadline = System.nanoTime() + REST_TIMEOUT.toNanos();
        while (!atRest() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(REST_POLL_INTERVAL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the server to come to rest", e);
            }
        }
        assertThat(violations()).as("server-level invariants").isEmpty();
    }

    /** Everything that is wrong, one line each; empty when every invariant holds. */
    List<String> violations() {
        return Objects.requireNonNull(snapshot.execute(status -> {
            List<String> violations = new ArrayList<>();
            violations.addAll(unbalancedStock());
            violations.addAll(liveOrdersWithoutTheirHold());
            violations.addAll(holdsWithoutALiveOrder());
            violations.addAll(ordersAnnouncedWrongly());
            violations.addAll(abandonedCardOrdersRulingOutAnAuthorization());
            violations.addAll(ordersStillAwaitingPayment());
            violations.addAll(unpublishedEvents());
            return violations;
        }));
    }

    private boolean atRest() {
        return db.sql("""
                        SELECT NOT EXISTS (SELECT 1 FROM ordering.customer_order WHERE status = 'AWAITING_PAYMENT')
                           AND NOT EXISTS (SELECT 1 FROM messaging.outbox_event WHERE published_at IS NULL)""").query(Boolean.class).single();
    }

    private List<String> unbalancedStock() {
        return db.sql("""
                        WITH held AS (
                            SELECT r.store_id, l.sku, sum(l.quantity) AS units
                            FROM inventory.reservation r
                            JOIN inventory.reservation_line l ON l.reservation_id = r.id
                            WHERE r.status IN ('HELD', 'CONFIRMED')
                            GROUP BY r.store_id, l.sku)
                        SELECT coalesce(s.store_id, h.store_id) AS store_id, coalesce(s.sku, h.sku) AS sku,
                               s.on_hand, s.reserved, coalesce(h.units, 0) AS held
                        FROM inventory.stock_level s
                        FULL JOIN held h ON h.store_id = s.store_id AND h.sku = s.sku
                        WHERE s.store_id IS NULL
                           OR s.reserved <> coalesce(h.units, 0)
                           OR s.reserved < 0
                           OR s.reserved > s.on_hand
                        ORDER BY 1, 2""")
                .query((rs, row) -> "stock %s/%s: on hand %s, reserved %s, but active reservation lines hold %d"
                        .formatted(
                                rs.getString("store_id"),
                                rs.getString("sku"),
                                rs.getObject("on_hand"),
                                rs.getObject("reserved"),
                                rs.getLong("held")))
                .list();
    }

    private List<String> liveOrdersWithoutTheirHold() {
        // Collations differ (inventory's ids are "C"), so the store ids are compared under an explicit one.
        return db.sql("""
                        SELECT o.id, o.status, count(r.id) AS active,
                               count(r.id) FILTER (
                                   WHERE r.id = o.reservation_id
                                     AND r.store_id = o.store_id COLLATE "C"
                                     AND r.status = CASE o.status WHEN 'CONFIRMED' THEN 'CONFIRMED' ELSE 'HELD' END
                               ) AS own
                        FROM ordering.customer_order o
                        LEFT JOIN inventory.reservation r
                               ON r.reference = o.id::text AND r.status IN ('HELD', 'CONFIRMED')
                        WHERE o.status IN ('AWAITING_PAYMENT', 'CONFIRMED')
                        GROUP BY o.id, o.status
                        HAVING count(r.id) <> 1
                            OR count(r.id) FILTER (
                                   WHERE r.id = o.reservation_id
                                     AND r.store_id = o.store_id COLLATE "C"
                                     AND r.status = CASE o.status WHEN 'CONFIRMED' THEN 'CONFIRMED' ELSE 'HELD' END
                               ) <> 1
                        ORDER BY o.id""")
                .query((rs, row) -> ("order %s (%s) has %d active reservations, %d of them its own with the matching "
                                + "status; expected exactly one")
                        .formatted(rs.getString("id"), rs.getString("status"), rs.getLong("active"), rs.getLong("own")))
                .list();
    }

    private List<String> holdsWithoutALiveOrder() {
        return db.sql("""
                        SELECT r.id, r.status, r.reference
                        FROM inventory.reservation r
                        WHERE r.status IN ('HELD', 'CONFIRMED')
                          AND NOT EXISTS (SELECT 1 FROM ordering.customer_order o
                                          WHERE o.id::text = r.reference
                                            AND o.status IN ('AWAITING_PAYMENT', 'CONFIRMED'))
                        ORDER BY r.id""")
                .query((rs, row) -> "reservation %s (%s, reference %s) holds stock, but no live order has that id"
                        .formatted(rs.getString("id"), rs.getString("status"), rs.getString("reference")))
                .list();
    }

    private List<String> ordersAnnouncedWrongly() {
        return db.sql("""
                        WITH announced AS (
                            SELECT payload ->> 'orderId' AS order_id,
                                   count(*) FILTER (WHERE type = :placed) AS placed,
                                   count(*) FILTER (WHERE type = :abandoned) AS abandoned
                            FROM messaging.outbox_event
                            WHERE type IN (:placed, :abandoned)
                            GROUP BY payload ->> 'orderId')
                        SELECT coalesce(o.id::text, a.order_id) AS order_id, o.status,
                               coalesce(a.placed, 0) AS placed, coalesce(a.abandoned, 0) AS abandoned
                        FROM ordering.customer_order o
                        FULL JOIN announced a ON a.order_id = o.id::text
                        WHERE o.id IS NULL
                           OR coalesce(a.placed, 0) <> CASE WHEN o.status = 'CONFIRMED' THEN 1 ELSE 0 END
                           OR coalesce(a.abandoned, 0) <> CASE WHEN o.status = 'ABANDONED' THEN 1 ELSE 0 END
                        ORDER BY 1""")
                .param("placed", OrderPlacedV1.TYPE)
                .param("abandoned", OrderAbandonedV1.TYPE)
                .query((rs, row) -> "order %s (%s): %d OrderPlacedV1 and %d OrderAbandonedV1"
                        .formatted(
                                rs.getString("order_id"),
                                Objects.requireNonNullElse(rs.getString("status"), "no such order"),
                                rs.getLong("placed"),
                                rs.getLong("abandoned")))
                .list();
    }

    private List<String> abandonedCardOrdersRulingOutAnAuthorization() {
        return db.sql("""
                        SELECT payload ->> 'orderId' AS order_id
                        FROM messaging.outbox_event
                        WHERE type = :abandoned
                          AND payload ->> 'paymentMethod' = 'CARD'
                          AND (payload ->> 'paymentAuthorizationMayExist')::boolean IS DISTINCT FROM true
                        ORDER BY seq""")
                .param("abandoned", OrderAbandonedV1.TYPE)
                .query((rs, row) -> "OrderAbandonedV1 for card order %s says no payment authorization can exist"
                        .formatted(rs.getString("order_id")))
                .list();
    }

    private List<String> ordersStillAwaitingPayment() {
        return db.sql("SELECT id FROM ordering.customer_order WHERE status = 'AWAITING_PAYMENT' ORDER BY id")
                .query((rs, row) -> "order %s still awaits its payment although every checkout has been answered"
                        .formatted(rs.getString("id")))
                .list();
    }

    private List<String> unpublishedEvents() {
        long unpublished = db.sql("SELECT count(*) FROM messaging.outbox_event WHERE published_at IS NULL")
                .query(Long.class)
                .single();
        return unpublished == 0
                ? List.of()
                : List.of(unpublished + " outbox events are still unpublished after " + REST_TIMEOUT);
    }
}
