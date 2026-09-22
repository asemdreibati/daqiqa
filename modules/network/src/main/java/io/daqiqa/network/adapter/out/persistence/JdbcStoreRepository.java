package io.daqiqa.network.adapter.out.persistence;

import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.kernel.LocalizedText;
import io.daqiqa.kernel.StoreId;
import io.daqiqa.network.api.StoreStatus;
import io.daqiqa.network.domain.Store;
import io.daqiqa.network.domain.StoreRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcStoreRepository implements StoreRepository {

    private static final String COLUMNS =
            "id, name_en, name_ar, latitude, longitude, status, active_pickers, service_radius_m";

    private final JdbcClient jdbc;

    JdbcStoreRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Store> findAll() {
        // The id column uses the "C" collation, so this is byte order: stable across database locales.
        return jdbc.sql("SELECT " + COLUMNS + " FROM network.dark_store ORDER BY id")
                .query(JdbcStoreRepository::mapRow)
                .list();
    }

    @Override
    public Optional<Store> findById(StoreId id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM network.dark_store WHERE id = :id")
                .param("id", id.value())
                .query(JdbcStoreRepository::mapRow)
                .optional();
    }

    @Override
    public Optional<Store> updateStatus(StoreId id, StoreStatus status, Instant changedAt) {
        // One statement: the update and the read of the updated row cannot interleave with another change.
        return jdbc.sql("UPDATE network.dark_store SET status = :status, updated_at = :changedAt WHERE id = :id"
                        + " RETURNING " + COLUMNS)
                .param("status", status.name())
                .param("changedAt", OffsetDateTime.ofInstant(changedAt, ZoneOffset.UTC))
                .param("id", id.value())
                .query(JdbcStoreRepository::mapRow)
                .optional();
    }

    private static Store mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Store(
                StoreId.of(rs.getString("id")),
                LocalizedText.of(rs.getString("name_en"), rs.getString("name_ar")),
                GeoPoint.of(rs.getDouble("latitude"), rs.getDouble("longitude")),
                StoreStatus.valueOf(rs.getString("status")),
                rs.getInt("active_pickers"),
                rs.getInt("service_radius_m"));
    }
}
