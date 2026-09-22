package io.daqiqa.promise.domain;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;

import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.kernel.StoreId;
import io.daqiqa.promise.api.DeliveryTier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
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
    private static final int PICK_CAP_SECONDS = 600;

    /** Guards the generator: every case must come up often enough for the other properties to mean something. */
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
        if (decision instanceof PromiseDecision.Offered offered) {
            EtaBreakdown breakdown = offered.quote().breakdown();
            Statistics.label("offer's pick capped").collect(breakdown.pickSeconds() == PICK_CAP_SECONDS);
            Statistics.label("offer's pick longer than the ride")
                    .collect(breakdown.pickSeconds() > breakdown.travelSeconds());
        }

        // Observed over several seeds: 27-31% offered, 53-56% out of stock, 9-11% out of coverage, 5-7% no capacity;
        // 14-16% with one suggestion, 19-21% with two; 5-6% an offer plus a faster suggestion; among offers, 8-12% with
        // a capped pick and 71-76% where picking takes longer than the ride. The bounds sit about four standard
        // deviations below: room for randomness, not for a generator that stopped producing a case.
        Statistics.label("outcome").coverage(coverage -> {
            coverage.check("OFFERED").percentage(percent -> percent >= 15);
            coverage.check("OUT_OF_STOCK").percentage(percent -> percent >= 15);
            coverage.check("OUT_OF_COVERAGE").percentage(percent -> percent >= 3);
            coverage.check("NO_CAPACITY").percentage(percent -> percent >= 1);
        });
        Statistics.label("suggestions").coverage(coverage -> {
            coverage.check(1).percentage(percent -> percent >= 5);
            coverage.check(2).percentage(percent -> percent >= 2);
        });
        Statistics.label("offer and a faster suggestion")
                .coverage(coverage -> coverage.check(true).percentage(percent -> percent >= 2));
        Statistics.label("offer's pick capped")
                .coverage(coverage -> coverage.check(true).percentage(percent -> percent >= 2));
        Statistics.label("offer's pick longer than the ride")
                .coverage(coverage -> coverage.check(true).percentage(percent -> percent >= 10));
    }

    @Property
    void theChosenStoreCanAlwaysSupplyTheWholeBasket(@ForAll("scenarios") Scenario scenario) {
        if (scenario.decide() instanceof PromiseDecision.Offered offered) {
            StoreId store = offered.quote().storeId();
            assertThat(scenario.candidates()).extracting(Candidate::storeId).contains(store);
            assertThat(scenario.lacking(store)).isEmpty();
        }
    }

    @Property
    void noStoreThatCouldSupplyTheWholeBasketWouldBeFasterToTheSecond(@ForAll("scenarios") Scenario scenario) {
        if (scenario.decide() instanceof PromiseDecision.Offered offered) {
            for (Candidate candidate : scenario.candidates()) {
                if (scenario.lacking(candidate.storeId()).isEmpty()) {
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

    /** Never the whole basket, and never an item the suggested store has: exactly what keeps it from delivering. */
    @Property
    void aSuggestionDropsExactlyWhatTheSuggestedStoreLacks(@ForAll("scenarios") Scenario scenario) {
        for (Suggestion suggestion : scenario.decide().suggestions()) {
            assertThat(suggestion.skus()).isEqualTo(scenario.lacking(suggestion.storeId()));
            assertThat(suggestion.skus())
                    .isNotEmpty()
                    .hasSizeLessThan(scenario.basket().lines().size());
        }
    }

    /** A suggestion is honest: promising the rest of the basket gives exactly the suggested store, ETA and tier. */
    @Property
    void promisingTheRestOfTheBasketGivesExactlyTheSuggestion(@ForAll("scenarios") Scenario scenario) {
        for (Suggestion suggestion : scenario.decide().suggestions()) {
            Basket rest = Basket.of(scenario.basket().lines().stream()
                    .filter(line -> !suggestion.skus().contains(line.sku()))
                    .toList());

            PromiseDecision again = scenario.calculator().decide(rest, scenario.candidates(), scenario.stock());

            assertThat(again).isInstanceOfSatisfying(PromiseDecision.Offered.class, offered -> {
                assertThat(offered.quote().storeId()).isEqualTo(suggestion.storeId());
                assertThat(offered.quote().etaMinutes()).isEqualTo(suggestion.etaMinutes());
                assertThat(offered.quote().tier()).isEqualTo(suggestion.tier());
            });
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
    void theOrderOfTheCandidatesNeverChangesTheDecision(@ForAll("scenarios") Scenario scenario, @ForAll Random random) {
        List<Candidate> shuffled = new ArrayList<>(scenario.candidates());
        Collections.shuffle(shuffled, random);

        assertThat(scenario.calculator().decide(scenario.basket(), shuffled, scenario.stock()))
                .isEqualTo(scenario.decide());
    }

    @Property
    void theReasonForNoPromiseIsTheRightOne(@ForAll("scenarios") Scenario scenario) {
        if (scenario.decide() instanceof PromiseDecision.Unavailable unavailable) {
            List<Candidate> complete = scenario.candidates().stream()
                    .filter(candidate -> scenario.lacking(candidate.storeId()).isEmpty())
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
                    new PickTimeModel(ofSeconds(45), ofSeconds(8), ofSeconds(PICK_CAP_SECONDS)),
                    store -> busy.contains(store) ? BUSY : OPEN,
                    PromiseCalibration.constant(ofSeconds(90)));
        }

        Stock stock() {
            return (store, sku) -> shelves.getOrDefault(store, Map.of()).getOrDefault(sku, 0);
        }

        PromiseDecision decide() {
            return calculator().decide(basket, candidates, stock());
        }

        /** What {@code store} cannot supply in full, in SKU order. */
        List<Sku> lacking(StoreId store) {
            return basket.lines().stream()
                    .filter(line -> !stock().covers(store, line))
                    .map(SkuQuantity::sku)
                    .toList();
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

    /**
     * Mostly everyday baskets, sometimes big ones (dozens of units of a SKU) so that picking dominates the ETA and hits
     * its cap; shelves are empty, short or plentiful, so every outcome and suggestion shape comes up.
     */
    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<Integer> quantities = Arbitraries.frequencyOf(
                Tuple.of(3, (Arbitrary<Integer>) Arbitraries.integers().between(1, 5)),
                Tuple.of(1, (Arbitrary<Integer>) Arbitraries.integers().between(30, 99)));
        Arbitrary<Basket> baskets = Arbitraries.subsetOf(SKUS)
                .ofMinSize(1)
                .ofMaxSize(5)
                .flatMap(skus -> quantities.list().ofSize(skus.size()).map(units -> {
                    List<Sku> ordered = List.copyOf(skus);
                    return Basket.of(IntStream.range(0, ordered.size())
                            .mapToObj(i -> new SkuQuantity(ordered.get(i), units.get(i)))
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
        Arbitrary<Integer> shelf = Arbitraries.frequencyOf(
                Tuple.of(3, Arbitraries.just(0)),
                Tuple.of(3, (Arbitrary<Integer>) Arbitraries.integers().between(1, 6)),
                Tuple.of(3, Arbitraries.just(99)));
        Arbitrary<Map<StoreId, Map<Sku, Integer>>> shelves = shelf.list()
                .ofSize(STORES.size() * SKUS.size())
                .map(units -> {
                    Map<StoreId, Map<Sku, Integer>> byStore = new HashMap<>();
                    for (int s = 0; s < STORES.size(); s++) {
                        Map<Sku, Integer> onShelf = new HashMap<>();
                        for (int k = 0; k < SKUS.size(); k++) {
                            onShelf.put(SKUS.get(k), units.get(s * SKUS.size() + k));
                        }
                        byStore.put(STORES.get(s), onShelf);
                    }
                    return byStore;
                });
        Arbitrary<Set<StoreId>> busy = Arbitraries.subsetOf(STORES);
        return Combinators.combine(baskets, candidates, shelves, busy).as(Scenario::new);
    }
}
