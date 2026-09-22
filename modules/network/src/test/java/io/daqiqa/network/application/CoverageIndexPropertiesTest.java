package io.daqiqa.network.application;

import static io.daqiqa.network.StoreFixtures.offset;
import static io.daqiqa.network.StoreFixtures.store;
import static io.daqiqa.network.application.CoverageIndexTest.GRID;
import static io.daqiqa.network.application.CoverageIndexTest.TRAVEL;
import static io.daqiqa.network.application.CoverageIndexTest.ids;
import static org.assertj.core.api.Assertions.assertThat;

import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.network.api.StoreCoverage;
import io.daqiqa.network.api.StoreStatus;
import io.daqiqa.network.domain.Store;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.DoubleRange;

/**
 * The coverage guarantees, over many generated points and networks: a point is served by exactly the stores that
 * accept orders and whose service radius contains it (none beyond its radius, none missing), fastest first.
 */
class CoverageIndexPropertiesTest {

    /** A varied network around Dubai: every status, radii from the minimum to the maximum, overlapping areas. */
    private static final List<Store> DUBAI = List.of(
            store("DXB-MARINA", GeoPoint.of(25.0805, 55.1403), 3_000, StoreStatus.OPEN),
            store("DXB-JLT", GeoPoint.of(25.0693, 55.1413), 2_500, StoreStatus.BUSY),
            store("DXB-JBR", GeoPoint.of(25.0780, 55.1330), Store.MIN_SERVICE_RADIUS_METERS, StoreStatus.OPEN),
            store("DXB-BARSHA", GeoPoint.of(25.1127, 55.1960), 4_000, StoreStatus.OPEN),
            store("DXB-TECOM", GeoPoint.of(25.0960, 55.1740), 1_500, StoreStatus.PAUSED),
            store("DXB-DOWNTOWN", GeoPoint.of(25.1972, 55.2744), Store.MAX_SERVICE_RADIUS_METERS, StoreStatus.OPEN),
            store("DXB-BUSINESS-BAY", GeoPoint.of(25.1850, 55.2650), 2_000, StoreStatus.CLOSED),
            store("DXB-DEIRA", GeoPoint.of(25.2710, 55.3070), 6_000, StoreStatus.BUSY),
            store("DXB-SILICON-OASIS", GeoPoint.of(25.1210, 55.3830), 8_000, StoreStatus.OPEN),
            store("DXB-JVC", GeoPoint.of(25.0600, 55.2100), 3_500, StoreStatus.OPEN));

    private static final NetworkView DUBAI_VIEW = viewOf(DUBAI);

    @Property
    void anywhereInTheCityTheAnswerIsExactAndFastestFirst(
            @ForAll @DoubleRange(min = 24.95, max = 25.35) double latitude,
            @ForAll @DoubleRange(min = 55.05, max = 55.50) double longitude) {
        assertServesExactly(DUBAI_VIEW, DUBAI, GeoPoint.of(latitude, longitude));
    }

    @Property
    void atTheEdgeOfEveryServiceAreaTheAnswerIsExact(@ForAll("probes") Probe probe) {
        assertServesExactly(DUBAI_VIEW, DUBAI, probe.pointNear(DUBAI));
    }

    @Property(tries = 100)
    void theAnswerIsExactForAnyNetwork(
            @ForAll("networks") List<Store> network, @ForAll("probeLists") List<Probe> probes) {
        NetworkView view = viewOf(network);

        for (Probe probe : probes) {
            assertServesExactly(view, network, probe.pointNear(network));
        }
    }

    private static void assertServesExactly(NetworkView view, List<Store> network, GeoPoint point) {
        List<StoreCoverage> serving = view.storesServing(point);

        Set<String> shouldServe = network.stream()
                .filter(Store::acceptsOrders)
                .filter(store -> store.location().distanceMetersTo(point) <= store.serviceRadiusMeters())
                .map(store -> store.id().value())
                .collect(Collectors.toSet());
        assertThat(ids(serving)).as("stores serving %s", point).containsExactlyInAnyOrderElementsOf(shouldServe);
        assertThat(serving).isSortedAccordingTo(Comparator.comparing(StoreCoverage::estimatedTravelTime));
    }

    private static NetworkView viewOf(List<Store> network) {
        InMemoryStoreRepository repository = new InMemoryStoreRepository();
        repository.replaceAll(network);
        NetworkView view = new NetworkView(repository, GRID, TRAVEL, new SimpleMeterRegistry());
        view.refresh();
        return view;
    }

    /** A point at {@code radiusFraction} of some store's radius from it: half of them right at the edge of its area. */
    record Probe(int storeIndex, double bearing, double radiusFraction) {

        GeoPoint pointNear(List<Store> network) {
            Store store = network.get(storeIndex % network.size());
            return offset(store.location(), bearing, store.serviceRadiusMeters() * radiusFraction);
        }
    }

    @Provide
    Arbitrary<Probe> probes() {
        return Combinators.combine(
                        Arbitraries.integers().between(0, 99),
                        Arbitraries.doubles().between(0, 360),
                        Arbitraries.oneOf(
                                Arbitraries.doubles().between(0.98, 1.02),
                                Arbitraries.doubles().between(0, 1.5)))
                .as(Probe::new);
    }

    @Provide
    Arbitrary<List<Probe>> probeLists() {
        return probes().list().ofSize(25);
    }

    @Provide
    Arbitrary<List<Store>> networks() {
        Arbitrary<Store> site = Combinators.combine(
                        Arbitraries.doubles().between(25.00, 25.20),
                        Arbitraries.doubles().between(55.10, 55.30),
                        Arbitraries.integers().between(Store.MIN_SERVICE_RADIUS_METERS, 5_000),
                        Arbitraries.of(StoreStatus.class))
                .as((lat, lng, radius, status) -> store("TST-SITE", GeoPoint.of(lat, lng), radius, status));
        return site.list()
                .ofMinSize(1)
                .ofMaxSize(4)
                .map(sites -> IntStream.range(0, sites.size())
                        .mapToObj(i -> store(
                                "TST-S" + i,
                                sites.get(i).location(),
                                sites.get(i).serviceRadiusMeters(),
                                sites.get(i).status()))
                        .toList());
    }
}
