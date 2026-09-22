package io.daqiqa.inventory.support;

import io.daqiqa.kernel.ReservationId;
import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.StoreId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Direct access to the inventory tables: arranges a test's starting stock and checks what was really written, without
 * going through the code under test.
 */
public final class InventoryTables {

    private final DataSource dataSource;
    private final JdbcClient jdbc;

    public InventoryTables(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = JdbcClient.create(dataSource);
    }

    public void stock(StoreId store, Sku sku, int onHand) {
        stock(store, sku, onHand, 0);
    }

    public void stock(StoreId store, Sku sku, int onHand, int reserved) {
        jdbc.sql("""
                        INSERT INTO inventory.stock_level (store_id, sku, on_hand, reserved, version, updated_at)
                        VALUES (:store, :sku, :onHand, :reserved, 0, :at)""")
                .param("store", store.value())
                .param("sku", sku.value())
                .param("onHand", onHand)
                .param("reserved", reserved)
                .param("at", OffsetDateTime.parse("2026-09-22T09:00:00Z"))
                .update();
    }

    /** The stock row, or null when the store has no row for the SKU. */
    public @Nullable StockRow stockOf(StoreId store, Sku sku) {
        return jdbc.sql("""
                        SELECT on_hand, reserved, version
                          FROM inventory.stock_level
                         WHERE store_id = :store AND sku = :sku""")
                .param("store", store.value())
                .param("sku", sku.value())
                .query((rs, row) -> new StockRow(rs.getInt("on_hand"), rs.getInt("reserved"), rs.getLong("version")))
                .optional()
                .orElse(null);
    }

    /**
     * Whether another transaction could lock this stock row right now, asked on a connection of its own (never the
     * caller's transactional one) and without waiting.
     */
    public boolean isStockRowFree(StoreId store, Sku sku) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement lock = connection.prepareStatement(
                        "SELECT 1 FROM inventory.stock_level WHERE store_id = ? AND sku = ? FOR UPDATE NOWAIT")) {
            connection.setAutoCommit(true);
            lock.setString(1, store.value());
            lock.setString(2, sku.value());
            lock.executeQuery().close();
            return true;
        } catch (SQLException e) {
            if ("55P03".equals(e.getSQLState())) { // lock_not_available
                return false;
            }
            throw new IllegalStateException(e);
        }
    }

    public List<ReservationRow> reservations() {
        return jdbc.sql("""
                        SELECT id, store_id, reference, status, release_reason, expires_at
                          FROM inventory.reservation
                         ORDER BY created_at, id""")
                .query((rs, row) -> new ReservationRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("store_id"),
                        rs.getString("reference"),
                        rs.getString("status"),
                        rs.getString("release_reason"),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    public ReservationRow reservation(ReservationId id) {
        return reservations().stream()
                .filter(reservation -> reservation.id().equals(id.value()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No reservation " + id));
    }

    /** Quantity per SKU on the reservation's lines. */
    public Map<String, Integer> linesOf(ReservationId id) {
        return jdbc
                .sql("SELECT sku, quantity FROM inventory.reservation_line WHERE reservation_id = :id")
                .param("id", id.value())
                .query((rs, row) -> Map.entry(rs.getString("sku"), rs.getInt("quantity")))
                .list()
                .stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public List<AdjustmentRow> adjustments() {
        return jdbc.sql("""
                        SELECT id, store_id, sku, delta, reason, on_hand_after, created_at
                          FROM inventory.stock_adjustment
                         ORDER BY created_at, id""")
                .query((rs, row) -> new AdjustmentRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("store_id"),
                        rs.getString("sku"),
                        rs.getInt("delta"),
                        rs.getString("reason"),
                        rs.getInt("on_hand_after"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /**
     * Every (store, SKU) whose books do not balance: {@code reserved} differs from the units on its held and confirmed
     * reservations' lines, or exceeds what is on hand. Empty when inventory is consistent.
     */
    public List<String> unbalancedBooks() {
        return jdbc.sql("""
                        SELECT coalesce(s.store_id, h.store_id) AS store_id, coalesce(s.sku, h.sku) AS sku,
                               s.on_hand, s.reserved, coalesce(h.held, 0) AS held
                          FROM inventory.stock_level AS s
                          FULL JOIN (SELECT r.store_id, l.sku, sum(l.quantity) AS held
                                       FROM inventory.reservation AS r
                                       JOIN inventory.reservation_line AS l ON l.reservation_id = r.id
                                      WHERE r.status IN ('HELD', 'CONFIRMED')
                                      GROUP BY r.store_id, l.sku) AS h
                            ON h.store_id = s.store_id AND h.sku = s.sku
                         WHERE s.reserved IS DISTINCT FROM coalesce(h.held, 0)
                            OR s.reserved > s.on_hand""")
                .query((rs, row) -> rs.getString("store_id") + "/" + rs.getString("sku") + ": on hand "
                        + rs.getObject("on_hand") + ", reserved " + rs.getObject("reserved") + ", held by reservations "
                        + rs.getLong("held"))
                .list();
    }

    public record StockRow(int onHand, int reserved, long version) {}

    public record ReservationRow(
            UUID id,
            String storeId,
            String reference,
            String status,
            @Nullable String releaseReason,
            Instant expiresAt) {}

    public record AdjustmentRow(
            UUID id, String storeId, String sku, int delta, String reason, int onHandAfter, Instant createdAt) {}
}
