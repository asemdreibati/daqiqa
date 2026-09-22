package io.daqiqa.promise.domain;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;

import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.kernel.StoreId;
import io.daqiqa.promise.api.DeliveryTier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.statistics.Statistics;

/** The calculator's guarantees over many generated baskets, store networks, stock levels and loads. */
class PromiseCalculatorPropertiesTest {

    private static final List<Sku> SKUS =
            IntStream.range(0, 8).mapToObj(i -> Sku.of("SKU-" + i)).toList();
    private static final List<StoreId> STORES =
            IntStream.range(0, 6).mapToObj(i -> StoreId.of("TST-S" + i)).toList();

    private static final StoreLoad OPEN = new StoreLoad(ofSeconds(60), ofSeconds(60));
    private static final StoreLoad BUSY = new StoreLoad(ofSeconds(300), ofSeconds(180));

    /** Guards the generator: every outcome must come up often enough for the other properties to mean something. */
    @Property
    void theScenariosCoverEveryOutcome(@ForAll("scenarios") Scenario scenario) {
        PromiseDecision decision = scenario.decide();
        String outcome =
                switch (decision) {
                    case PromiseDecision.Offered offered -> "OFFERED";
                    case PromiseDecision.Unavailable unavailable ->
                        unavailable.reason().name();
                };
        Statistics.label("outcome").collect(outcome);
        Statistics.label("suggestions").collect(decision.suggestions().size());
        Statistics.label("offer and a faster suggestion")
                .collect(decision instanceof PromiseDecision.Offered
                        && !decision.suggestions().isEmpty());

        // Observed with this generator: about 31% offered, 53% out of stock, 10% out of coverage, 5% no capacity;
        // 18% with one suggestion and 18% with two. The bounds leave room for randomness, not for a broken generator.
        Statistics.label("outcome").coverage(coverage -> {
            coverage.check("OFFERED").percentage(percent -> percent >= 15);
            coverage.check("OUT_OF_STOCK").percentage(percent -> percent >= 15);
            coverage.check("OUT_OF_COVERAGE").percentage(percent -> percent >= 3);
            coverage.check("NO_CAPACITY").percentage(percent -> percent >= 1);
        });
        Statistics.label("suggestions").coverage(coverage -> {
            coverage.check(1).percentage(percent -> percent >= 5);
            coverage.check(2).percentage(percent -> percent >= 5);
        });
        Statistics.label("offer and a faster suggestion")
                .coverage(coverage -> coverage.check(true).percentage(percent -> percent >= 2));
    }

    @Property
    void theChosenStoreCanAlwaysSupplyTheWholeBasket(@ForAll("scenarios") Scenario scenario) {
        if (scenario.decide() instanceof PromiseDecision.Offered offered) {
            StoreId store = offered.quote().storeId();
            assertThat(scenario.candidates()).extracting(Candidate::storeId).contains(store);
            assertThat(scenario.basket().lines())
                    .allSatisfy(line ->
                            assertThat(scenario.stock().covers(store, line)).isTrue());
        }
    }

    @Property
    void noStoreThatCouldSupplyTheWholeBasketWouldBeFasterToTheSecond(@ForAll("scenarios") Scenario scenario) {
        if (scenario.decide() instanceof PromiseDecision.Offered offered) {
            for (Candidate candidate : scenario.candidates()) {
                if (scenario.canSupplyAll(candidate.storeId())) {
                    assertThat(scenario.secondsFromAlone(candidate))
                            .isGreaterThanOrEqualTo(offered.quote().breakdown().totalSeconds());
                }
            }
        }
    }

    @Property
    void theEtaIsTheBreakdownRoundedUpToTheMinuteAndFitsTheTier(@ForAll("scenarios") Scenario scenario) {
        if (scenario.decide() instanceof PromiseDecision.Offered offered) {
            Quote quote = offered.quote();
            assertThat(quote.etaMinutes())
                    .isEqualTo(Math.max(1, (quote.breakdown().totalSeconds() + 59) / 60));
            assertThat(DeliveryTier.forEta(quote.etaMinutes())).contains(quote.tier());
        }
    }

    /** "Suggestions never contain SKUs that are in stock at that store", and never empty the basket. */
    @Property
    void aSuggestionDropsExactlyWhatThatStoreCannotSupply(@ForAll("scenarios") Scenario scenario) {
        for (Suggestion suggestion : scenario.decide().suggestions()) {
            List<Sku> lacking = scenario.basket().lines().stream()
                    .filter(line -> !scenario.stock().covers(suggestion.storeId(), line))
                    .map(SkuQuantity::sku)
                    .toList();
            assertThat(suggestion.skus()).isEqualTo(lacking);
            assertThat(suggestion.skus())
                    .isNotEmpty()
                    .hasSizeLessThan(scenario.basket().lines().size());
        }
    }

    @Property
    void suggestionsAreFewFastestFirstAndBeatTheOffer(@ForAll("scenarios") Scenario scenario) {
        PromiseDecision decision = scenario.decide();
        List<Suggestion> suggestions = decision.suggestions();

        assertThat(suggestions).hasSizeLessThanOrEqualTo(PromiseCalculator.MAX_SUGGESTIONS);
        assertThat(suggestions).isSortedAccordingTo(Comparator.comparingInt(Suggestion::etaMinutes));
        assertThat(suggestions).allSatisfy(suggestion -> {
            assertThat(DeliveryTier.forEta(suggestion.etaMinutes())).contains(suggestion.tier());
            if (decision instanceof PromiseDecision.Offered offered) {
                assertThat(suggestion.etaMinutes()).isLessThan(offered.quote().etaMinutes());
                assertThat(suggestion.storeId()).isNotEqualTo(offered.quote().storeId());
            }
        });
    }

    @Property
    void theReasonForNoPromiseIsTheRightOne(@ForAll("scenarios") Scenario scenario) {
        if (scenario.decide() instanceof PromiseDecision.Unavailable unavailable) {
            List<Candidate> complete = scenario.candidates().stream()
                    .filter(candidate -> scenario.canSupplyAll(candidate.storeId()))
                    .toList();
            UnavailableReason expected = scenario.candidates().isEmpty()
                    ? UnavailableReason.OUT_OF_COVERAGE
                    : complete.isEmpty() ? UnavailableReason.OUT_OF_STOCK : UnavailableReason.NO_CAPACITY;
            assertThat(unavailable.reason()).isEqualTo(expected);
            assertThat(complete)
                    .allSatisfy(candidate -> assertThat(scenario.etaFromAlone(candidate))
                            .isGreaterThan(DeliveryTier.EXTENDED_60.maxMinutes()));
        }
    }

    @Property
    void aLongerRideNeverMakesThePromiseSooner(
            @ForAll("scenarios") Scenario scenario,
            @ForAll @IntRange(min = 0, max = 50) int whichCandidate,
            @ForAll @IntRange(min = 0, max = 2_000) int extraSeconds) {
        if (scenario.candidates().isEmpty()) {
            return;
        }
        Scenario slower =
                scenario.withExtraTravel(whichCandidate % scenario.candidates().size(), extraSeconds);

        if (slower.decide() instanceof PromiseDecision.Offered slowerOffer) {
            assertThat(scenario.decide())
                    .isInstanceOfSatisfying(
                            PromiseDecision.Offered.class,
                            offer -> assertThat(offer.quote().etaMinutes())
                                    .isLessThanOrEqualTo(slowerOffer.quote().etaMinutes()));
        }
    }

    record Scenario(
            Basket basket, List<Candidate> candidates, Map<StoreId, Map<Sku, Integer>> shelves, Set<StoreId> busy) {

        PromiseCalculator calculator() {
            return new PromiseCalculator(
                    new PickTimeModel(ofSeconds(45), ofSeconds(8), ofSeconds(600)),
                    store -> busy.contains(store) ? BUSY : OPEN,
                    PromiseCalibration.constant(ofSeconds(90)));
        }

        Stock stock() {
            return (store, sku) -> shelves.getOrDefault(store, Map.of()).getOrDefault(sku, 0);
        }

        PromiseDecision decide() {
            return calculator().decide(basket, candidates, stock());
        }

        boolean canSupplyAll(StoreId store) {
            return basket.lines().stream().allMatch(line -> stock().covers(store, line));
        }

        /** How long a candidate that can supply the whole basket would take, were it the only store around. */
        int secondsFromAlone(Candidate candidate) {
            return switch (calculator().decide(basket, List.of(candidate), stock())) {
                case PromiseDecision.Offered offered ->
                    offered.quote().breakdown().totalSeconds();
                case PromiseDecision.Unavailable unavailable -> Integer.MAX_VALUE;
            };
        }

        /** The ETA of a candidate that can supply the whole basket, were it the only store around. */
        int etaFromAlone(Candidate candidate) {
            return switch (calculator().decide(basket, List.of(candidate), stock())) {
                case PromiseDecision.Offered offered -> offered.quote().etaMinutes();
                case PromiseDecision.Unavailable unavailable -> Integer.MAX_VALUE;
            };
        }

        Scenario withExtraTravel(int index, int extraSeconds) {
            List<Candidate> slower = new ArrayList<>(candidates);
            Candidate candidate = slower.get(index);
            slower.set(
                    index,
                    new Candidate(
                            candidate.storeId(),
                            candidate.travelTime().plus(Duration.ofSeconds(extraSeconds)),
                            candidate.distanceMeters()));
            return new Scenario(basket, slower, shelves, busy);
        }
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<Basket> baskets = Arbitraries.subsetOf(SKUS)
                .ofMinSize(1)
                .ofMaxSize(5)
                .flatMap(skus -> Arbitraries.integers()
                        .between(1, 5)
                        .list()
                        .ofSize(skus.size())
                        .map(quantities -> {
                            List<Sku> ordered = List.copyOf(skus);
                            return Basket.of(IntStream.range(0, ordered.size())
                                    .mapToObj(i -> new SkuQuantity(ordered.get(i), quantities.get(i)))
                                    .toList());
                        }));
        Arbitrary<List<Candidate>> candidates = Arbitraries.subsetOf(STORES)
                .flatMap(stores -> Combinators.combine(
                                Arbitraries.integers().between(0, 4_000).list().ofSize(stores.size()),
                                Arbitraries.integers().between(0, 10_000).list().ofSize(stores.size()))
                        .as((travel, distance) -> {
                            List<StoreId> ordered = List.copyOf(stores);
                            return IntStream.range(0, ordered.size())
                                    .mapToObj(i ->
                                            new Candidate(ordered.get(i), ofSeconds(travel.get(i)), distance.get(i)))
                                    .toList();
                        }));
        Arbitrary<Map<StoreId, Map<Sku, Integer>>> shelves = Arbitraries.integers()
                .between(0, 6)
                .list()
                .ofSize(STORES.size() * SKUS.size())
                .map(units -> {
                    Map<StoreId, Map<Sku, Integer>> byStore = new HashMap<>();
                    for (int s = 0; s < STORES.size(); s++) {
                        Map<Sku, Integer> shelf = new HashMap<>();
                        for (int k = 0; k < SKUS.size(); k++) {
                            shelf.put(SKUS.get(k), units.get(s * SKUS.size() + k));
                        }
                        byStore.put(STORES.get(s), shelf);
                    }
                    return byStore;
                });
        Arbitrary<Set<StoreId>> busy = Arbitraries.subsetOf(STORES);
        return Combinators.combine(baskets, candidates, shelves, busy).as(Scenario::new);
    }
}
