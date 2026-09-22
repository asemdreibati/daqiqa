package io.daqiqa.network.adapter.out.persistence;

import static io.daqiqa.network.StoreFixtures.DUBAI_MARINA;
import static io.daqiqa.network.StoreFixtures.JLT;
import static io.daqiqa.network.StoreFixtures.openStore;
import static io.daqiqa.network.StoreFixtures.store;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.kernel.LocalizedText;
import io.daqiqa.kernel.StoreId;
import io.daqiqa.network.NetworkDatabase;
import io.daqiqa.network.api.StoreStatus;
import io.daqiqa.network.domain.Store;
import io.daqiqa.testing.postgres.PostgresTestDatabase;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@Tag("integration")
class JdbcStoreRepositoryTest {

    private static final PostgresTestDatabase DB = NetworkDatabase.migrated();

    private final JdbcClient jdbc = JdbcClient.create(DB.dataSource());
    private final JdbcStoreRepository repository = new JdbcStoreRepository(jdbc);

    @BeforeEach
    void emptyNetwork() {
        DB.truncateSchemas("network");
    }

    @Test
    void readsBackEveryAttributeOfAStore() {
        Store marina = new Store(
                StoreId.of("DXB-MARINA"),
                LocalizedText.of("Dubai Marina", "دبي مارينا"),
                GeoPoint.of(25.0805, 55.1403),
                StoreStatus.BUSY,
                7,
                3_000,
                4);
        NetworkDatabase.insert(jdbc, marina);

        assertThat(repository.findById(StoreId.of("DXB-MARINA"))).contains(marina);
        assertThat(repository.findById(StoreId.of("DXB-NOWHERE"))).isEmpty();
    }

    @Test
    void listsStoresInByteOrderOfTheirIdsWhateverTheDatabaseLocale() {
        // A locale collation that ignores punctuation would put DXB-AA before DXB-A-B; byte order does not.
        for (String id : new String[] {"DXB-AA", "RUH-OLAYA", "DXB-A-B", "AUH-CORNICHE"}) {
            NetworkDatabase.insert(jdbc, openStore(id, DUBAI_MARINA, 3_000));
        }

        assertThat(repository.findAll())
                .extracting(store -> store.id().value())
                .containsExactly("AUH-CORNICHE", "DXB-A-B", "DXB-AA", "RUH-OLAYA");
    }

    @Test
    void changesTheStatusAndStampsWhenItChanged() {
        NetworkDatabase.insert(jdbc, openStore("DXB-MARINA", DUBAI_MARINA, 3_000));
        Instant changedAt = Instant.parse("2026-09-22T12:34:56Z");

        assertThat(repository.updateStatus(StoreId.of("DXB-MARINA"), StoreStatus.PAUSED, changedAt))
                .hasValueSatisfying(store -> {
                    assertThat(store.status()).isEqualTo(StoreStatus.PAUSED);
                    assertThat(store.version())
                            .as("each change is a new version")
                            .isEqualTo(2);
                });

        assertThat(repository.findById(StoreId.of("DXB-MARINA")))
                .hasValueSatisfying(store -> assertThat(store.status()).isEqualTo(StoreStatus.PAUSED));
        assertThat(jdbc.sql("SELECT updated_at FROM network.dark_store WHERE id = 'DXB-MARINA'")
                        .query(OffsetDateTime.class)
                        .single()
                        .toInstant())
                .isEqualTo(changedAt);
    }

    @Test
    void changingTheStatusOfAnUnknownStoreChangesNothing() {
        NetworkDatabase.insert(jdbc, openStore("DXB-MARINA", DUBAI_MARINA, 3_000));

        assertThat(repository.updateStatus(StoreId.of("DXB-JLT"), StoreStatus.PAUSED, Instant.EPOCH))
                .isEmpty();
        assertThat(repository.findAll()).extracting(Store::status).containsExactly(StoreStatus.OPEN);
    }

    @Test
    void aNewStoreIsClosedUntilOperationsOpensIt() {
        jdbc.sql("""
                INSERT INTO network.dark_store (id, name_en, name_ar, latitude, longitude, service_radius_m)
                VALUES ('DXB-NEW', 'New', 'جديد', 25.1, 55.2, 2000)
                """).update();

        assertThat(repository.findById(StoreId.of("DXB-NEW"))).hasValueSatisfying(store -> {
            assertThat(store.status()).isEqualTo(StoreStatus.CLOSED);
            assertThat(store.version()).isZero();
        });
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "id = 'dxb-lower'",
                "status = 'OPENED'",
                "service_radius_m = 499",
                "service_radius_m = 10001",
                "active_pickers = -1",
                "latitude = 91",
                "name_ar = ' '",
                "version = -1"
            })
    void theDatabaseRejectsAStoreThatBreaksAnInvariant(String violation) {
        NetworkDatabase.insert(jdbc, store("DXB-JLT", JLT, 3_000, StoreStatus.OPEN));

        assertThatThrownBy(() -> jdbc.sql("UPDATE network.dark_store SET " + violation + " WHERE id = 'DXB-JLT'")
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
