package io.daqiqa.network;

import io.daqiqa.network.domain.Store;
import io.daqiqa.testing.postgres.PostgresTestDatabase;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The network schema on the test PostgreSQL. Tests insert their own stores; there is no API to create one. */
public final class NetworkDatabase {

    private NetworkDatabase() {}

    public static PostgresTestDatabase migrated() {
        return PostgresTestDatabase.shared().migrate("classpath:db/migration/network");
    }

    public static void insert(JdbcClient jdbc, Store store) {
        jdbc.sql("""
                INSERT INTO network.dark_store
                    (id, name_en, name_ar, latitude, longitude, status, active_pickers, service_radius_m)
                VALUES (:id, :nameEn, :nameAr, :latitude, :longitude, :status, :activePickers, :radius)
                """)
                .param("id", store.id().value())
                .param("nameEn", store.name().en())
                .param("nameAr", store.name().ar())
                .param("latitude", store.location().latitude())
                .param("longitude", store.location().longitude())
                .param("status", store.status().name())
                .param("activePickers", store.activePickers())
                .param("radius", store.serviceRadiusMeters())
                .update();
    }
}
