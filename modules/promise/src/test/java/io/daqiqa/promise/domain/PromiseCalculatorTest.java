package io.daqiqa.promise.domain;

import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;

import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.kernel.StoreId;
import io.daqiqa.promise.api.DeliveryTier;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Every branch of the calculator, with the v1 default models: picking 45 s + 8 s per unit (capped at 600 s), an open
 * store queues 60 s and waits 60 s for a rider, a busy one 300 s and 180 s, and every ETA carries a 90 s buffer.
 */
class PromiseCalculatorTest {

    private static final StoreId MARINA = StoreId.of("DXB-MARINA");
    private static final StoreId JLT = StoreId.of("DXB-JLT");
    private static final StoreId BARSHA = StoreId.of("DXB-BARSHA");

    private static final Sku MILK = Sku.of("MILK-FULL-1L");
    private static final Sku BREAD = Sku.of("BREAD-ARABIC-6PK");
    private static final Sku EGGS = Sku.of("EGGS-12");

    /** Two milks and a bread: three units, picked in 45 + 3 × 8 = 69 s. */
    private static final Basket BREAKFAST = basket(line(MILK, 2), line(BREAD, 1));

    private static final StoreLoad OPEN = new StoreLoad(ofSeconds(60), ofSeconds(60));
    private static final StoreLoad BUSY = new StoreLoad(ofSeconds(300), ofSeconds(180));

    private final Map<StoreId, StoreLoad> loads = new HashMap<>();
    private final Map<StoreId, Duration> buffers = new HashMap<>();
    private final Map<StoreId, Map<Sku, Integer>> shelves = new HashMap<>();

    private final PromiseCalculator calculator = new PromiseCalculator(
            new PickTimeModel(ofSeconds(45), ofSeconds(8), ofSeconds(600)),
            store -> loads.getOrDefault(store, OPEN),
            store -> buffers.getOrDefault(store, ofSeconds(90)));

    @Test
    void withNoStoreNearbyTheLocationIsOutOfCoverage() {
        assertThat(decide(BREAKFAST))
                .isEqualTo(new PromiseDecision.Unavailable(UnavailableReason.OUT_OF_COVERAGE, List.of()));
    }

    @Test
    void offersTheStoreThatHasTheWholeBasketAndShowsWhereTheMinutesGo() {
        stock(MARINA, Map.of(MILK, 5, BREAD, 5));

        Quote quote = offer(decide(BREAKFAST, at(MARINA, 330, 2_000)));

        assertThat(quote.storeId()).isEqualTo(MARINA);
        assertThat(quote.breakdown()).isEqualTo(new EtaBreakdown(60, 69, 60, 330, 90));
        assertThat(quote.etaMinutes()).isEqualTo(11); // 609 s
        assertThat(quote.tier()).isEqualTo(DeliveryTier.EXPRESS_15);
    }

    @Test
    void aBusyStoreQueuesAndWaitsForRidersLonger() {
        stock(MARINA, Map.of(MILK, 5, BREAD, 5));
        loads.put(MARINA, BUSY);

        Quote quote = offer(decide(BREAKFAST, at(MARINA, 330, 2_000)));

        assertThat(quote.breakdown()).isEqualTo(new EtaBreakdown(300, 69, 180, 330, 90));
        assertThat(quote.etaMinutes()).isEqualTo(17); // 969 s
        assertThat(quote.tier()).isEqualTo(DeliveryTier.STANDARD_30);
    }

    @Test
    void eachStoreGetsItsOwnCalibratedBuffer() {
        stock(MARINA, Map.of(MILK, 5, BREAD, 5));
        buffers.put(MARINA, ofSeconds(150));

        assertThat(offer(decide(BREAKFAST, at(MARINA, 330, 2_000))).breakdown().bufferSeconds())
                .isEqualTo(150);
    }

    @Test
    void pickingABigBasketIsCapped() {
        stock(MARINA, Map.of(MILK, 99, BREAD, 99));

        Quote quote = offer(decide(basket(line(MILK, 35), line(BREAD, 35)), at(MARINA, 330, 2_000)));

        assertThat(quote.breakdown().pickSeconds()).isEqualTo(600);
    }

    @Test
    void theEtaIsTheBreakdownRoundedUpToTheMinute() {
        stock(MARINA, Map.of(MILK, 5, BREAD, 5));

        // 60 + 69 + 60 + 90 = 279 s before the ride.
        assertThat(offer(decide(BREAKFAST, at(MARINA, 321, 2_000))).etaMinutes())
                .isEqualTo(10); // 600 s
        assertThat(offer(decide(BREAKFAST, at(MARINA, 322, 2_000))).etaMinutes())
                .isEqualTo(11); // 601 s
    }

    @Test
    void aFractionOfASecondOfTravelCountsAsAWholeSecond() {
        stock(MARINA, Map.of(MILK, 5, BREAD, 5));
        Candidate marina = new Candidate(MARINA, ofMillis(321_001), 2_000);

        Quote quote = offer(calculator.decide(BREAKFAST, List.of(marina), this::available));

        assertThat(quote.breakdown().travelSeconds()).isEqualTo(322);
        assertThat(quote.etaMinutes()).isEqualTo(11);
    }

    @Test
    void aPromiseIsNeverForLessThanAMinute() {
        stock(MARINA, Map.of(MILK, 5, BREAD, 5));
        PromiseCalculator instant = new PromiseCalculator(
                new PickTimeModel(Duration.ZERO, Duration.ZERO, Duration.ZERO),
                store -> new StoreLoad(Duration.ZERO, Duration.ZERO),
                PromiseCalibration.constant(Duration.ZERO));

        Quote quote = offer(instant.decide(BREAKFAST, List.of(at(MARINA, 0, 0)), this::available));

        assertThat(quote.etaMinutes()).isEqualTo(1);
        assertThat(quote.tier()).isEqualTo(DeliveryTier.EXPRESS_15);
    }

    @Test
    void theFastestStoreWithTheWholeBasketWinsOverAFasterStoreWithoutIt() {
        stock(MARINA, Map.of(MILK, 5, BREAD, 5));
        stock(JLT, Map.of(MILK, 5));

        PromiseDecision decision = decide(BREAKFAST, at(JLT, 100, 800), at(MARINA, 330, 2_000));

        assertThat(offer(decision).storeId()).isEqualTo(MARINA);
    }

    @Test
    void equallyFastStoresAreDecidedByDistanceThenById() {
        stock(MARINA, Map.of(MILK, 5, BREAD, 5));
        stock(JLT, Map.of(MILK, 5, BREAD, 5));
        stock(BARSHA, Map.of(MILK, 5, BREAD, 5));

        // 609 s and 629 s are both 11 minutes: the nearer store wins, although it is 20 s slower.
        assertThat(offer(decide(BREAKFAST, at(MARINA, 330, 2_000), at(JLT, 350, 1_900)))
                        .storeId())
                .isEqualTo(JLT);
        // Same minutes, same distance: the smaller id, so the answer never depends on the order of candidates.
        assertThat(offer(decide(BREAKFAST, at(MARINA, 330, 2_000), at(BARSHA, 330, 2_000)))
                        .storeId())
                .isEqualTo(BARSHA);
        assertThat(offer(decide(BREAKFAST, at(BARSHA, 330, 2_000), at(MARINA, 330, 2_000)))
                        .storeId())
                .isEqualTo(BARSHA);
    }

    @Test
    void withoutAStoreThatHasTheWholeBasketTheBasketIsOutOfStock() {
        stock(MARINA, Map.of(MILK, 5));
        stock(JLT, Map.of(BREAD, 5));

        PromiseDecision decision = decide(BREAKFAST, at(MARINA, 330, 2_000), at(JLT, 200, 1_500));

        assertThat(decision).isInstanceOf(PromiseDecision.Unavailable.class);
        assertThat(((PromiseDecision.Unavailable) decision).reason()).isEqualTo(UnavailableReason.OUT_OF_STOCK);
        // Without an offer, any store that can deliver part of the basket within a tier is worth suggesting.
        assertThat(decision.suggestions())
                .containsExactly(
                        new Suggestion(JLT, List.of(MILK), 8, DeliveryTier.EXPRESS_15), // 463 s
                        new Suggestion(MARINA, List.of(BREAD), 11, DeliveryTier.EXPRESS_15)); // 601 s
    }

    @Test
    void aWholeBasketThatCannotArriveWithinTheSlowestTierIsNoCapacity() {
        stock(MARINA, Map.of(MILK, 5, BREAD, 5));
        stock(JLT, Map.of(MILK, 5));

        PromiseDecision decision = decide(BREAKFAST, at(MARINA, 3_600, 9_000), at(JLT, 100, 800));

        assertThat(decision).isInstanceOf(PromiseDecision.Unavailable.class);
        assertThat(((PromiseDecision.Unavailable) decision).reason()).isEqualTo(UnavailableReason.NO_CAPACITY);
        assertThat(decision.suggestions())
                .containsExactly(new Suggestion(JLT, List.of(BREAD), 7, DeliveryTier.EXPRESS_15));
    }

    @Test
    void suggestsDroppingWhatAFasterStoreLacksTimedForTheSmallerBasket() {
        stock(MARINA, Map.of(MILK, 5, BREAD, 5));
        stock(JLT, Map.of(MILK, 5));

        PromiseDecision decision = decide(BREAKFAST, at(MARINA, 330, 2_000), at(JLT, 100, 800));

        assertThat(offer(decision).etaMinutes()).isEqualTo(11);
        // Two milks only: picked in 61 s, so 60 + 61 + 60 + 100 + 90 = 371 s.
        assertThat(decision.suggestions())
                .containsExactly(new Suggestion(JLT, List.of(BREAD), 7, DeliveryTier.EXPRESS_15));
    }

    @Test
    void aLineAStoreCannotSupplyInFullIsOneToDrop() {
        stock(MARINA, Map.of(MILK, 5, BREAD, 5));
        stock(JLT, Map.of(MILK, 1, BREAD, 5));

        PromiseDecision decision = decide(BREAKFAST, at(MARINA, 330, 2_000), at(JLT, 100, 800));

        assertThat(decision.suggestions())
                .containsExactly(new Suggestion(JLT, List.of(MILK), 7, DeliveryTier.EXPRESS_15));
    }

    @Test
    void doesNotSuggestDroppingItemsWhenThatIsNotStrictlyFaster() {
        stock(MARINA, Map.of(MILK, 5, BREAD, 5));
        stock(JLT, Map.of(MILK, 5));

        // Without the bread, JLT needs 601 s: 11 minutes, the same as the offer.
        PromiseDecision decision = decide(BREAKFAST, at(MARINA, 330, 2_000), at(JLT, 330, 1_000));

        assertThat(offer(decision).storeId()).isEqualTo(MARINA);
        assertThat(decision.suggestions()).isEmpty();
    }

    @Test
    void neverSuggestsDroppingTheWholeBasket() {
        stock(JLT, Map.of(MILK, 1));

        PromiseDecision decision = decide(BREAKFAST, at(JLT, 100, 800));

        assertThat(decision).isEqualTo(new PromiseDecision.Unavailable(UnavailableReason.OUT_OF_STOCK, List.of()));
    }

    @Test
    void doesNotSuggestWhatWouldStillMissTheSlowestTier() {
        stock(JLT, Map.of(MILK, 5));

        PromiseDecision decision = decide(BREAKFAST, at(JLT, 3_600, 9_000));

        assertThat(decision.suggestions()).isEmpty();
    }

    @Test
    void suggestsAtMostTwoOptionsFastestFirst() {
        Basket basket = basket(line(MILK, 2), line(BREAD, 1), line(EGGS, 1));
        stock(JLT, Map.of(MILK, 5, BREAD, 5)); // without eggs: 379 s
        stock(MARINA, Map.of(MILK, 5, EGGS, 5)); // without bread: 479 s
        stock(BARSHA, Map.of(BREAD, 5, EGGS, 5)); // without milk: 571 s

        PromiseDecision decision = decide(basket, at(BARSHA, 300, 2_500), at(MARINA, 200, 1_500), at(JLT, 100, 800));

        assertThat(decision.suggestions())
                .containsExactly(
                        new Suggestion(JLT, List.of(EGGS), 7, DeliveryTier.EXPRESS_15),
                        new Suggestion(MARINA, List.of(BREAD), 8, DeliveryTier.EXPRESS_15));
    }

    @Test
    void leavesOutASuggestionThatDropsMoreItemsWithoutBeingFaster() {
        Basket basket = basket(line(MILK, 2), line(BREAD, 1), line(EGGS, 1));
        stock(JLT, Map.of(MILK, 5, BREAD, 5)); // without eggs: 579 s, 10 minutes
        stock(MARINA, Map.of(MILK, 5)); // without bread and eggs: 571 s, also 10 minutes

        PromiseDecision decision = decide(basket, at(JLT, 300, 2_000), at(MARINA, 300, 2_000));

        assertThat(decision.suggestions())
                .containsExactly(new Suggestion(JLT, List.of(EGGS), 10, DeliveryTier.EXPRESS_15));
    }

    @Test
    void keepsASuggestionThatDropsMoreItemsButIsFaster() {
        Basket basket = basket(line(MILK, 2), line(BREAD, 1), line(EGGS, 1));
        stock(JLT, Map.of(MILK, 5, BREAD, 5)); // without eggs: 579 s, 10 minutes
        stock(BARSHA, Map.of(EGGS, 5)); // without milk and bread: 363 s, 7 minutes

        PromiseDecision decision = decide(basket, at(JLT, 300, 2_000), at(BARSHA, 100, 800));

        assertThat(decision.suggestions())
                .containsExactly(
                        new Suggestion(BARSHA, List.of(BREAD, MILK), 7, DeliveryTier.EXPRESS_15),
                        new Suggestion(JLT, List.of(EGGS), 10, DeliveryTier.EXPRESS_15));
    }

    private PromiseDecision decide(Basket basket, Candidate... candidates) {
        return calculator.decide(basket, List.of(candidates), this::available);
    }

    private int available(StoreId store, Sku sku) {
        return shelves.getOrDefault(store, Map.of()).getOrDefault(sku, 0);
    }

    private void stock(StoreId store, Map<Sku, Integer> units) {
        shelves.put(store, units);
    }

    private static Quote offer(PromiseDecision decision) {
        assertThat(decision).isInstanceOf(PromiseDecision.Offered.class);
        return ((PromiseDecision.Offered) decision).quote();
    }

    private static Candidate at(StoreId store, int travelSeconds, int distanceMeters) {
        return new Candidate(store, ofSeconds(travelSeconds), distanceMeters);
    }

    private static Basket basket(SkuQuantity... lines) {
        return Basket.of(List.of(lines));
    }

    private static SkuQuantity line(Sku sku, int quantity) {
        return new SkuQuantity(sku, quantity);
    }
}
