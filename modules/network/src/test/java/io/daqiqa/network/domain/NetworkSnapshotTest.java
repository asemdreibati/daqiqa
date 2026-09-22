package io.daqiqa.network.domain;

import static io.daqiqa.network.StoreFixtures.DUBAI_MARINA;
import static io.daqiqa.network.StoreFixtures.JLT;
import static io.daqiqa.network.StoreFixtures.offset;
import static io.daqiqa.network.StoreFixtures.openStore;
import static io.daqiqa.network.StoreFixtures.store;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.within;

import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.kernel.StoreId;
import io.daqiqa.network.api.StoreStatus;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class NetworkSnapshotTest {

    private static final TravelTimeModel TRAVEL = new TravelTimeModel(1.3, 7.0, Duration.ofSeconds(60));

    /** Customer location used by most tests. */
    private static final GeoPoint CUSTOMER = DUBAI_MARINA;

    private final CountingOneCellGrid grid = new CountingOneCellGrid();

    @Test
    void anEmptyNetworkServesNobody() {
        assertThat(NetworkSnapshot.empty(grid).storesServing(CUSTOMER, TRAVEL)).isEmpty();
    }

    @Test
    void servesAPointWithinTheServiceRadius() {
        NetworkSnapshot network = network(openStore("DXB-NEAR", offset(CUSTOMER, 0, 999), 1_000));

        assertThat(ids(network.storesServing(CUSTOMER, TRAVEL))).containsExactly("DXB-NEAR");
    }

    @Test
    void checksTheExactDistanceEvenWhenTheCellMatches() {
        // Same (and only) cell, but one metre outside the radius.
        NetworkSnapshot network = network(openStore("DXB-FAR", offset(CUSTOMER, 0, 1_001), 1_000));

        assertThat(network.storesServing(CUSTOMER, TRAVEL)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(StoreStatus.class)
    void servesOnlyFromStoresThatAcceptOrders(StoreStatus status) {
        NetworkSnapshot network = network(store("DXB-NEAR", offset(CUSTOMER, 90, 500), 1_000, status));

        assertThat(network.storesServing(CUSTOMER, TRAVEL)).hasSize(status.acceptsOrders() ? 1 : 0);
    }

    @Test
    void listsTheFastestStoreFirst() {
        NetworkSnapshot network = network(
                openStore("DXB-MID", offset(CUSTOMER, 10, 1_200), 3_000),
                openStore("DXB-FAR", offset(CUSTOMER, 200, 2_500), 3_000),
                openStore("DXB-NEAR", offset(CUSTOMER, 300, 300), 3_000));

        List<StoreReach> serving = network.storesServing(CUSTOMER, TRAVEL);

        assertThat(ids(serving)).containsExactly("DXB-NEAR", "DXB-MID", "DXB-FAR");
        assertThat(serving).isSortedAccordingTo((a, b) -> a.travelTime().compareTo(b.travelTime()));
    }

    @Test
    void breaksTravelTimeTiesByDistanceThenById() {
        // 1000.0 m and 1000.3 m both ride in 186 s: the nearer store wins although its id sorts last.
        // Two stores in the same building are equally near: the smaller id wins.
        NetworkSnapshot network = network(
                openStore("DXB-A", offset(CUSTOMER, 90, 1_000.3), 3_000),
                openStore("DXB-Z", offset(CUSTOMER, 270, 1_000.0), 3_000),
                openStore("DXB-TWIN-B", offset(CUSTOMER, 0, 2_000), 3_000),
                openStore("DXB-TWIN-A", offset(CUSTOMER, 0, 2_000), 3_000));

        List<StoreReach> serving = network.storesServing(CUSTOMER, TRAVEL);

        assertThat(serving.get(0).travelTime()).isEqualTo(serving.get(1).travelTime());
        assertThat(ids(serving)).containsExactly("DXB-Z", "DXB-A", "DXB-TWIN-A", "DXB-TWIN-B");
    }

    @Test
    void reportsTheDistanceAndTheRide() {
        NetworkSnapshot network = network(openStore("DXB-NEAR", offset(CUSTOMER, 45, 2_000), 3_000));

        StoreReach reach = network.storesServing(CUSTOMER, TRAVEL).getFirst();

        assertThat(reach.distanceMeters()).isCloseTo(2_000, within(0.01));
        assertThat(reach.travelTime()).isEqualTo(TRAVEL.travelTime(reach.distanceMeters()));
    }

    @Test
    void listsEveryStoreByIdAndFindsThemWhateverTheirStatus() {
        NetworkSnapshot network = network(
                store("DXB-B", CUSTOMER, 1_000, StoreStatus.CLOSED),
                store("DXB-A", CUSTOMER, 1_000, StoreStatus.PAUSED));

        assertThat(network.stores()).extracting(s -> s.id().value()).containsExactly("DXB-A", "DXB-B");
        assertThat(network.store(StoreId.of("DXB-B")))
                .hasValueSatisfying(s -> assertThat(s.status()).isEqualTo(StoreStatus.CLOSED));
        assertThat(network.store(StoreId.of("DXB-C"))).isEmpty();
    }

    @Test
    void reusesTheGridIndexWhenOnlyStatusesChange() {
        NetworkSnapshot open = network(openStore("DXB-A", CUSTOMER, 1_000), openStore("DXB-B", CUSTOMER, 2_000));
        int coveringsAfterFirstBuild = grid.coverings;

        NetworkSnapshot paused = open.withStores(
                List.of(store("DXB-A", CUSTOMER, 1_000, StoreStatus.PAUSED), openStore("DXB-B", CUSTOMER, 2_000)));

        assertThat(grid.coverings).isEqualTo(coveringsAfterFirstBuild);
        assertThat(ids(paused.storesServing(CUSTOMER, TRAVEL))).containsExactly("DXB-B");
        assertThat(ids(open.storesServing(CUSTOMER, TRAVEL)))
                .as("a snapshot never changes once built")
                .containsExactly("DXB-A", "DXB-B");
    }

    @Test
    void rebuildsTheGridIndexWhenAStoreMovesChangesItsRadiusOrJoins() {
        NetworkSnapshot network = network(openStore("DXB-A", CUSTOMER, 1_000));

        int before = grid.coverings;
        network = network.withStores(List.of(openStore("DXB-A", offset(CUSTOMER, 0, 10), 1_000)));
        assertThat(grid.coverings).as("moved").isEqualTo(before + 1);

        before = grid.coverings;
        network = network.withStores(List.of(openStore("DXB-A", offset(CUSTOMER, 0, 10), 1_500)));
        assertThat(grid.coverings).as("new radius").isEqualTo(before + 1);

        before = grid.coverings;
        network.withStores(
                List.of(openStore("DXB-A", offset(CUSTOMER, 0, 10), 1_500), openStore("DXB-B", CUSTOMER, 1_000)));
        assertThat(grid.coverings).as("new store").isEqualTo(before + 2);
    }

    @Test
    void rejectsTwoStoresWithTheSameId() {
        assertThatIllegalStateException()
                .isThrownBy(() -> network(openStore("DXB-A", CUSTOMER, 1_000), openStore("DXB-A", JLT, 1_000)));
    }

    private NetworkSnapshot network(Store... stores) {
        return NetworkSnapshot.empty(grid).withStores(List.of(stores));
    }

    private static List<String> ids(List<StoreReach> serving) {
        return serving.stream().map(reach -> reach.store().id().value()).toList();
    }

    /**
     * A degenerate grid with one cell for the whole planet. It honours the {@link CellGrid} contract (a superset never
     * misses a cell), so only the snapshot's exact rules decide what is served.
     */
    private static final class CountingOneCellGrid implements CellGrid {

        private int coverings;

        @Override
        public long cellOf(GeoPoint point) {
            return 0;
        }

        @Override
        public long[] cellsCovering(GeoPoint centre, double radiusMeters) {
            coverings++;
            return new long[] {0};
        }
    }
}
