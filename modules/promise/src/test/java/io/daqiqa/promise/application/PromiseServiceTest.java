package io.daqiqa.promise.application;

import static io.daqiqa.promise.application.Tokens.key;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.daqiqa.inventory.api.AvailabilityQuery;
import io.daqiqa.inventory.api.StockSnapshot;
import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.kernel.StoreId;
import io.daqiqa.network.api.StoreCoverage;
import io.daqiqa.network.api.StoreStatus;
import io.daqiqa.promise.MutableClock;
import io.daqiqa.promise.api.DeliveryTier;
import io.daqiqa.promise.api.VerifiedPromise;
import io.daqiqa.promise.domain.EtaBreakdown;
import io.daqiqa.promise.domain.InvalidBasketException;
import io.daqiqa.promise.domain.PickTimeModel;
import io.daqiqa.promise.domain.PromiseCalculator;
import io.daqiqa.promise.domain.PromiseCalibration;
import io.daqiqa.promise.domain.StoreLoad;
import io.daqiqa.promise.domain.Suggestion;
import io.daqiqa.promise.domain.UnavailableReason;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The promise use case with fakes for the other contexts: coverage, stock and the store directory. */
class PromiseServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");
    private static final GeoPoint CUSTOMER = GeoPoint.of(25.0772, 55.1390);
    private static final StoreId MARINA = StoreId.of("DXB-MARINA");
    private static final StoreId JLT = StoreId.of("DXB-JLT");
    private static final Sku MILK = Sku.of("MILK-FULL-1L");
    private static final Sku BREAD = Sku.of("BREAD-ARABIC-6PK");
    private static final List<SkuQuantity> BREAKFAST = List.of(new SkuQuantity(MILK, 2), new SkuQuantity(BREAD, 1));

    private List<StoreCoverage> covering = List.of();
    private final Map<StoreId, Map<Sku, Integer>> shelves = new HashMap<>();
    private final List<AvailabilityCall> availabilityCalls = new ArrayList<>();
    private final FakeStoreDirectory directory = new FakeStoreDirectory();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock(NOW);

    private final PromiseTokenCodec tokens =
            new PromiseTokenCodec(new SigningKeys(Map.of("k1", key(1)), "k1"), Duration.ofSeconds(90), clock);
    private final CheckoutPromiseVerifier checkout = new CheckoutPromiseVerifier(tokens, directory, clock, 100);

    private final AvailabilityQuery availability = (stores, skus) -> {
        availabilityCalls.add(new AvailabilityCall(List.copyOf(stores), List.copyOf(skus)));
        Map<StoreId, Map<Sku, Integer>> read = new HashMap<>();
        stores.forEach(store -> read.put(store, shelves.getOrDefault(store, Map.of())));
        return new StockSnapshot(read);
    };

    private final PromiseService service = new PromiseService(
            point -> covering,
            availability,
            new PromiseCalculator(
                    new PickTimeModel(ofSeconds(45), ofSeconds(8), ofSeconds(600)),
                    new StatusBasedStoreLoad(
                            directory,
                            new StoreLoad(ofSeconds(60), ofSeconds(60)),
                            new StoreLoad(ofSeconds(300), ofSeconds(180))),
                    PromiseCalibration.constant(ofSeconds(90))),
            tokens,
            new PromiseMetrics(meters));

    record AvailabilityCall(List<StoreId> stores, List<Sku> skus) {}

    @Test
    void promisesTheFastestStoreWithATokenCheckoutCanVerify() {
        coveredBy(new StoreCoverage(MARINA, ofSeconds(330), 2_000));
        directory.with(MARINA, StoreStatus.OPEN);
        shelves.put(MARINA, Map.of(MILK, 5, BREAD, 5));

        IssuedPromise offer = available(service.promise(CUSTOMER, BREAKFAST));

        assertThat(offer.quote().storeId()).isEqualTo(MARINA);
        assertThat(offer.quote().breakdown()).isEqualTo(new EtaBreakdown(60, 69, 60, 330, 90));
        assertThat(offer.quote().etaMinutes()).isEqualTo(11);
        assertThat(offer.expiresAt()).isEqualTo(NOW.plusSeconds(90));
        VerifiedPromise verified = checkout.verify(offer.token(), BREAKFAST, CUSTOMER);
        assertThat(verified.promiseId()).isEqualTo(offer.promiseId());
        assertThat(verified.storeId()).isEqualTo(MARINA);
        assertThat(verified.etaMinutes()).isEqualTo(11);
        assertThat(verified.tier()).isEqualTo(DeliveryTier.EXPRESS_15);
    }

    @Test
    void readsStockInOneRoundTripForEveryCandidateStoreAndTheWholeBasket() {
        coveredBy(new StoreCoverage(JLT, ofSeconds(100), 800), new StoreCoverage(MARINA, ofSeconds(330), 2_000));

        service.promise(CUSTOMER, BREAKFAST);

        assertThat(availabilityCalls).containsExactly(new AvailabilityCall(List.of(JLT, MARINA), List.of(BREAD, MILK)));
    }

    @Test
    void doesNotReadStockWhenNoStoreCoversTheLocation() {
        PromiseResult result = service.promise(CUSTOMER, BREAKFAST);

        assertThat(result).isEqualTo(new PromiseResult.Unavailable(UnavailableReason.OUT_OF_COVERAGE, List.of()));
        assertThat(availabilityCalls).isEmpty();
    }

    @Test
    void aBusyStorePromisesLater() {
        coveredBy(new StoreCoverage(MARINA, ofSeconds(330), 2_000));
        directory.with(MARINA, StoreStatus.BUSY);
        shelves.put(MARINA, Map.of(MILK, 5, BREAD, 5));

        IssuedPromise offer = available(service.promise(CUSTOMER, BREAKFAST));

        assertThat(offer.quote().breakdown()).isEqualTo(new EtaBreakdown(300, 69, 180, 330, 90));
        assertThat(offer.quote().tier()).isEqualTo(DeliveryTier.STANDARD_30);
    }

    @Test
    void mergesDuplicateLinesBeforePromising() {
        coveredBy(new StoreCoverage(MARINA, ofSeconds(330), 2_000));
        directory.with(MARINA, StoreStatus.OPEN);
        shelves.put(MARINA, Map.of(MILK, 5, BREAD, 5));
        List<SkuQuantity> splitLines =
                List.of(new SkuQuantity(MILK, 1), new SkuQuantity(BREAD, 1), new SkuQuantity(MILK, 1));

        IssuedPromise offer = available(service.promise(CUSTOMER, splitLines));

        assertThat(offer.quote().breakdown().pickSeconds()).isEqualTo(69);
        assertThat(availabilityCalls.getFirst().skus()).containsExactly(BREAD, MILK);
        assertThat(checkout.verify(offer.token(), BREAKFAST, CUSTOMER).promiseId())
                .isEqualTo(offer.promiseId());
    }

    @Test
    void comesWithFasterOptionsWhenDroppingItemsHelps() {
        coveredBy(new StoreCoverage(JLT, ofSeconds(100), 800), new StoreCoverage(MARINA, ofSeconds(330), 2_000));
        directory.with(JLT, StoreStatus.OPEN).with(MARINA, StoreStatus.OPEN);
        shelves.put(MARINA, Map.of(MILK, 5, BREAD, 5));
        shelves.put(JLT, Map.of(MILK, 5));

        PromiseResult result = service.promise(CUSTOMER, BREAKFAST);

        assertThat(available(result).quote().storeId()).isEqualTo(MARINA);
        assertThat(result.suggestions())
                .containsExactly(new Suggestion(JLT, List.of(BREAD), 7, DeliveryTier.EXPRESS_15));
    }

    @Test
    void refusesABasketNoPromiseCanBeMadeFor() {
        coveredBy(new StoreCoverage(MARINA, ofSeconds(330), 2_000));

        assertThatExceptionOfType(InvalidBasketException.class)
                .isThrownBy(
                        () -> service.promise(CUSTOMER, List.of(new SkuQuantity(MILK, 60), new SkuQuantity(MILK, 60))));
        assertThat(availabilityCalls).isEmpty();
    }

    @Test
    void timesEveryPromiseByOutcomeAndRecordsTheEta() {
        coveredBy(new StoreCoverage(MARINA, ofSeconds(330), 2_000));
        directory.with(MARINA, StoreStatus.OPEN);
        shelves.put(MARINA, Map.of(MILK, 5, BREAD, 5));
        service.promise(CUSTOMER, BREAKFAST);
        shelves.clear();
        service.promise(CUSTOMER, BREAKFAST);

        assertThat(requests("AVAILABLE", "EXPRESS_15", "none").count()).isEqualTo(1);
        assertThat(requests("UNAVAILABLE", "none", "OUT_OF_STOCK").count()).isEqualTo(1);
        assertThat(meters.get("daqiqa.promise.eta").summary().count()).isEqualTo(1);
        assertThat(meters.get("daqiqa.promise.eta").summary().totalAmount()).isEqualTo(11);
    }

    @Test
    void registersEveryOutcomeUpFrontWithALatencyHistogramAndA25MillisecondBucket() {
        assertThat(meters.get("daqiqa.promise.requests").timers()).hasSize(6);

        Timer timer = requests("UNAVAILABLE", "none", "OUT_OF_COVERAGE");
        timer.record(Duration.ofMillis(10));
        assertThat(Arrays.stream(timer.takeSnapshot().histogramCounts()).map(CountAtBucket::bucket))
                .contains((double) Duration.ofMillis(25).toNanos());
    }

    private Timer requests(String status, String tier, String reason) {
        return meters.get("daqiqa.promise.requests")
                .tags("status", status, "tier", tier, "reason", reason)
                .timer();
    }

    private void coveredBy(StoreCoverage... stores) {
        covering = List.of(stores);
    }

    private static IssuedPromise available(PromiseResult result) {
        assertThat(result).isInstanceOf(PromiseResult.Available.class);
        return ((PromiseResult.Available) result).offer();
    }
}
