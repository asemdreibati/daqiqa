package io.daqiqa.inventory.adapter.out.persistence;

import static io.daqiqa.inventory.adapter.out.persistence.SqlValues.timestamp;

import io.daqiqa.inventory.domain.HeldUnits;
import io.daqiqa.inventory.domain.StockKey;
import io.daqiqa.inventory.domain.StockLevel;
import io.daqiqa.inventory.domain.StockLevelRepository;
import io.daqiqa.inventory.domain.StoreStock;
import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.kernel.StoreId;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcStockLevelRepository implements StockLevelRepository {

    private static final RowMapper<StockLevel> STOCK_LEVEL = (rs, row) -> new StockLevel(
            StoreId.of(rs.getString("store_id")),
            Sku.of(rs.getString("sku")),
            rs.getInt("on_hand"),
            rs.getInt("reserved"));

    private final JdbcClient jdbc;

    JdbcStockLevelRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public StoreStock lockForUpdate(StoreId storeId, Collection<Sku> skus) {
        // ORDER BY is applied before FOR UPDATE takes the locks, so rows are locked in SKU order: two baskets sharing
        // SKUs queue behind each other instead of deadlocking (ADR-0005).
        List<StockLevel> levels = jdbc.sql("""
                        SELECT store_id, sku, on_hand, reserved
                          FROM inventory.stock_level
                         WHERE store_id = :storeId AND sku = ANY(:skus)
                         ORDER BY sku
                           FOR UPDATE""")
                .param("storeId", storeId.value())
                .param("skus", skuValues(skus))
                .query(STOCK_LEVEL)
                .list();
        return StoreStock.of(storeId, levels);
    }

    @Override
    public StoreStock find(StoreId storeId, Collection<Sku> skus) {
        List<StockLevel> levels = jdbc.sql("""
                        SELECT store_id, sku, on_hand, reserved
                          FROM inventory.stock_level
                         WHERE store_id = :storeId AND sku = ANY(:skus)""")
                .param("storeId", storeId.value())
                .param("skus", skuValues(skus))
                .query(STOCK_LEVEL)
                .list();
        return StoreStock.of(storeId, levels);
    }

    @Override
    public Map<StoreId, Map<Sku, Integer>> available(Collection<StoreId> storeIds, Collection<Sku> skus) {
        Map<StoreId, Map<Sku, Integer>> available = new HashMap<>();
        // Both arrays constrain the primary key (store_id, sku): one index scan for every store × SKU combination.
        jdbc.sql("""
                        SELECT store_id, sku, on_hand - reserved AS available
                          FROM inventory.stock_level
                         WHERE store_id = ANY(:storeIds) AND sku = ANY(:skus)""")
                .param("storeIds", storeIds.stream().map(StoreId::value).toArray(String[]::new))
                .param("skus", skuValues(skus))
                .query(rs -> {
                    available
                            .computeIfAbsent(StoreId.of(rs.getString("store_id")), store -> new HashMap<>())
                            .put(Sku.of(rs.getString("sku")), rs.getInt("available"));
                });
        return available;
    }

    @Override
    public void reserve(StoreId storeId, List<SkuQuantity> lines, Instant now) {
        // The caller holds the row locks and found no shortfall; the availability condition checks again anyway, so
        // even a caller that skipped the lock could not oversell: a line it refuses leaves the count short.
        int updated = jdbc.sql("""
                        UPDATE inventory.stock_level AS s
                           SET reserved = s.reserved + l.quantity, updated_at = :now
                          FROM unnest(:skus, :quantities) AS l(sku, quantity)
                         WHERE s.store_id = :storeId
                           AND s.sku = l.sku
                           AND s.on_hand - s.reserved >= l.quantity""")
                .param("storeId", storeId.value())
                .param("skus", lines.stream().map(line -> line.sku().value()).toArray(String[]::new))
                .param(
                        "quantities",
                        lines.stream().mapToInt(SkuQuantity::quantity).toArray())
                .param("now", timestamp(now))
                .update();
        if (updated != lines.size()) {
            throw new IllegalStateException("Could hold only " + updated + " of " + lines.size() + " lines at "
                    + storeId + ": stock changed under a held lock");
        }
    }

    @Override
    public void lockForUpdate(Collection<StockKey> keys) {
        lock(keys);
    }

    @Override
    public void release(List<HeldUnits> units, Instant now) {
        if (units.isEmpty()) {
            return;
        }
        // Lock first, in (store, SKU) order: an UPDATE locks rows in whatever order its plan visits them, and could
        // deadlock with a reservation locking the same rows in SKU order.
        int locked = lock(units.stream().map(HeldUnits::key).toList());
        int updated = jdbc.sql("""
                        UPDATE inventory.stock_level AS s
                           SET reserved = s.reserved - u.quantity, updated_at = :now
                          FROM unnest(:storeIds, :skus, :quantities) AS u(store_id, sku, quantity)
                         WHERE s.store_id = u.store_id
                           AND s.sku = u.sku
                           AND s.reserved >= u.quantity""")
                .param(
                        "storeIds",
                        units.stream().map(held -> held.storeId().value()).toArray(String[]::new))
                .param("skus", units.stream().map(held -> held.sku().value()).toArray(String[]::new))
                .param(
                        "quantities",
                        units.stream().mapToInt(HeldUnits::quantity).toArray())
                .param("now", timestamp(now))
                .update();
        if (locked != units.size() || updated != units.size()) {
            throw new IllegalStateException("Could return only " + updated + " of " + units.size()
                    + " held quantities to sale (" + locked + " stock rows found): reserved stock is inconsistent");
        }
    }

    /** Locks the existing rows of {@code keys} in (store, SKU) order, in one statement; returns how many exist. */
    private int lock(Collection<StockKey> keys) {
        List<StockKey> rows = keys.stream().distinct().sorted().toList();
        if (rows.isEmpty()) {
            return 0;
        }
        // ORDER BY is applied before FOR UPDATE takes the locks, so rows are locked in the global order.
        return jdbc.sql("""
                        SELECT s.store_id
                          FROM inventory.stock_level AS s
                          JOIN unnest(:storeIds, :skus) AS k(store_id, sku)
                            ON s.store_id = k.store_id AND s.sku = k.sku
                         ORDER BY s.store_id, s.sku
                           FOR UPDATE OF s""")
                .param(
                        "storeIds",
                        rows.stream().map(key -> key.storeId().value()).toArray(String[]::new))
                .param("skus", rows.stream().map(key -> key.sku().value()).toArray(String[]::new))
                .query(String.class)
                .list()
                .size();
    }

    @Override
    public StockLevel lockOrCreate(StoreId storeId, Sku sku, Instant now) {
        // Create the row first (nothing happens if it exists), so there is always a row to lock: two first deliveries
        // of a SKU then queue on the same row instead of racing to insert it.
        jdbc.sql("""
                        INSERT INTO inventory.stock_level (store_id, sku, on_hand, reserved, updated_at)
                        VALUES (:storeId, :sku, 0, 0, :now)
                        ON CONFLICT (store_id, sku) DO NOTHING""")
                .param("storeId", storeId.value())
                .param("sku", sku.value())
                .param("now", timestamp(now))
                .update();
        return jdbc.sql("""
                        SELECT store_id, sku, on_hand, reserved
                          FROM inventory.stock_level
                         WHERE store_id = :storeId AND sku = :sku
                           FOR UPDATE""")
                .param("storeId", storeId.value())
                .param("sku", sku.value())
                .query(STOCK_LEVEL)
                .single();
    }

    @Override
    public void adjustOnHand(StoreId storeId, Sku sku, int delta, Instant now) {
        int updated = jdbc.sql("""
                        UPDATE inventory.stock_level
                           SET on_hand = on_hand + :delta, updated_at = :now
                         WHERE store_id = :storeId AND sku = :sku AND on_hand + :delta >= reserved""")
                .param("storeId", storeId.value())
                .param("sku", sku.value())
                .param("delta", delta)
                .param("now", timestamp(now))
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Could not adjust " + sku + " at " + storeId + " by " + delta
                    + ": no stock row, or on hand would drop below reserved");
        }
    }

    private static String[] skuValues(Collection<Sku> skus) {
        return skus.stream().map(Sku::value).toArray(String[]::new);
    }
}
