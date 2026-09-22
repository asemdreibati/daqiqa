package io.daqiqa.network.application;

import static io.daqiqa.network.StoreFixtures.BUSINESS_BAY;
import static io.daqiqa.network.StoreFixtures.DUBAI_MARINA;
import static io.daqiqa.network.StoreFixtures.JLT;
import static io.daqiqa.network.StoreFixtures.offset;
import static io.daqiqa.network.StoreFixtures.openStore;
import static io.daqiqa.network.StoreFixtures.store;
import static org.assertj.core.api.Assertions.assertThat;

import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.kernel.StoreId;
import io.daqiqa.network.adapter.out.h3.H3CellGrid;
import io.daqiqa.network.api.DarkStore;
import io.daqiqa.network.api.StoreCoverage;
import io.daqiqa.network.api.StoreStatus;
import io.daqiqa.network.domain.CellGrid;
import io.daqiqa.network.domain.Store;
import io.daqiqa.network.domain.TravelTimeModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The coverage index as other modules use it: the real H3 grid over an in-memory store network. */
class CoverageIndexTest {

    static final CellGrid GRID = H3CellGrid.create();
    static final TravelTimeModel TRAVEL = new TravelTimeModel(1.3, 7.0, Duration.ofSeconds(60));
    static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");

    private final InMemoryStoreRepository repository = new InMemoryStoreRepository();
    private final NetworkView view = new NetworkView(repository, GRID, TRAVEL, new SimpleMeterRegistry());

    @Test
    void aPointNextToAStoreIsServedByIt() {
        network(openStore("DXB-MARINA", DUBAI_MARINA, 3_000));

        assertThat(ids(view.storesServing(offset(DUBAI_MARINA, 30, 150)))).containsExactly("DXB-MARINA");
    }

    @Test
    void aPointOutsideEveryServiceAreaIsServedByNobody() {
        network(openStore("DXB-MARINA", DUBAI_MARINA, 3_000));

        assertThat(view.storesServing(BUSINESS_BAY)).isEmpty();
    }

    @Test
    void aPointJustInsideTheRadiusIsServedAndJustOutsideIsNot() {
        network(openStore("DXB-MARINA", DUBAI_MARINA, 3_000));

        for (int bearing = 0; bearing < 360; bearing += 15) {
            assertThat(view.storesServing(offset(DUBAI_MARINA, bearing, 2_999))).hasSize(1);
            assertThat(view.storesServing(offset(DUBAI_MARINA, bearing, 3_001))).isEmpty();
        }
    }

    @Test
    void theFastestStoreComesFirst() {
        network(openStore("DXB-MARINA", DUBAI_MARINA, 5_000), openStore("DXB-JLT", JLT, 5_000));
        GeoPoint nearJlt = offset(JLT, 180, 200);

        List<StoreCoverage> serving = view.storesServing(nearJlt);

        assertThat(ids(serving)).containsExactly("DXB-JLT", "DXB-MARINA");
        assertThat(serving.get(0).estimatedTravelTime())
                .isLessThan(serving.get(1).estimatedTravelTime());
    }

    @Test
    void pausedAndClosedStoresAreNotOfferedButBusyStoresAre() {
        network(
                store("DXB-OPEN", DUBAI_MARINA, 3_000, StoreStatus.OPEN),
                store("DXB-BUSY", DUBAI_MARINA, 3_000, StoreStatus.BUSY),
                store("DXB-PAUSED", DUBAI_MARINA, 3_000, StoreStatus.PAUSED),
                store("DXB-CLOSED", DUBAI_MARINA, 3_000, StoreStatus.CLOSED));

        assertThat(ids(view.storesServing(DUBAI_MARINA))).containsExactly("DXB-BUSY", "DXB-OPEN");
    }

    @Test
    void aStatusChangeIsVisibleToTheVeryNextLookup() {
        network(openStore("DXB-MARINA", DUBAI_MARINA, 3_000));
        StoreId marina = StoreId.of("DXB-MARINA");

        repository.updateStatus(marina, StoreStatus.PAUSED, NOW);
        view.onStoreStatusChanged(new StoreStatusChanged(marina, StoreStatus.PAUSED));

        assertThat(view.storesServing(DUBAI_MARINA)).isEmpty();
        assertThat(view.find(marina)).map(DarkStore::status).contains(StoreStatus.PAUSED);
    }

    @Test
    void reportsTheRideAsTheTravelModelEstimatesIt() {
        network(openStore("DXB-MARINA", DUBAI_MARINA, 5_000));
        GeoPoint customer = offset(DUBAI_MARINA, 60, 2_000);

        StoreCoverage coverage = view.storesServing(customer).getFirst();

        assertThat(coverage.distanceMeters()).isEqualTo(2_000);
        assertThat(coverage.estimatedTravelTime()).isEqualTo(Duration.ofSeconds(432));
    }

    @Test
    void theDirectoryListsEveryStoreWhateverItsStatusOrderedById() {
        network(
                store("DXB-MARINA", DUBAI_MARINA, 3_000, StoreStatus.CLOSED),
                openStore("DXB-JLT", JLT, 3_000),
                openStore("DXB-BUSINESS-BAY", BUSINESS_BAY, 3_000));

        assertThat(view.findAll())
                .extracting(store -> store.id().value())
                .containsExactly("DXB-BUSINESS-BAY", "DXB-JLT", "DXB-MARINA");
        assertThat(view.find(StoreId.of("DXB-MARINA")))
                .hasValueSatisfying(
                        store -> assertThat(store.serviceRadiusMeters()).isEqualTo(3_000));
        assertThat(view.find(StoreId.of("DXB-NOWHERE"))).isEmpty();
    }

    @Test
    void loadsTheNetworkFromTheRepositoryWhenTheBeanStarts() {
        repository.replaceAll(List.of(openStore("DXB-MARINA", DUBAI_MARINA, 3_000)));

        view.afterPropertiesSet();

        assertThat(ids(view.storesServing(DUBAI_MARINA))).containsExactly("DXB-MARINA");
    }

    private void network(Store... stores) {
        repository.replaceAll(List.of(stores));
        view.refresh();
    }

    static List<String> ids(List<StoreCoverage> serving) {
        return serving.stream().map(coverage -> coverage.storeId().value()).toList();
    }
}
