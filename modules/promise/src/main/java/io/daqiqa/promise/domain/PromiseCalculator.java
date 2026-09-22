package io.daqiqa.promise.domain;

import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.promise.api.DeliveryTier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The promise engine's decision (steps 3 to 6 of {@code docs/design/promise.md}), a pure function of already-fetched
 * data: no I/O, no clock, and the same answer whatever the order of the candidates. Given the stores that reach the
 * customer and what they have in stock:
 *
 * <ol>
 *   <li>A store able to supply a basket gets an ETA for it: its picker queue and rider wait (from its current load),
 *       the time to pick the basket's units, the ride, and the calibrated safety buffer.
 *   <li>The offer comes from the store with the smallest ETA, to the second, among those able to supply the
 *       <em>whole</em> basket; equal times go to the nearer store, then to the smaller id. No such store:
 *       {@code OUT_OF_STOCK}. Its ETA fits no delivery tier: {@code NO_CAPACITY}. No store at all:
 *       {@code OUT_OF_COVERAGE}.
 *   <li>Suggestions answer "what if I dropped what a store lacks?". For every set of items that some store lacks (never
 *       the whole basket), the rest of the basket is promised again against every candidate, whole-basket stores
 *       included, and the winner is the suggestion. It is made only if the winner lacks exactly those items (otherwise
 *       dropping them would merely shorten the pick at a store that could deliver more), fits a tier, and beats the
 *       offer by at least a minute (or there is no offer). Promising the rest of the basket therefore gives exactly the
 *       suggested store, ETA and tier. A suggestion that drops more items than another without being faster is left
 *       out. At most {@value #MAX_SUGGESTIONS}, fastest first.
 * </ol>
 */
public final class PromiseCalculator {

    public static final int MAX_SUGGESTIONS = 2;

    /**
     * Fastest first, to the second: within the same minute, the store with more slack is more likely to keep the
     * promise. Equal times go to the nearer store, then to the smaller id, so the decision is deterministic.
     */
    private static final Comparator<Estimate> FASTEST = Comparator.comparingInt(Estimate::totalSeconds)
            .thenComparingInt(estimate -> estimate.candidate().distanceMeters())
            .thenComparing(estimate -> estimate.candidate().storeId().value());

    /**
     * Fastest first as the customer sees it, in whole minutes; within a minute, fewer items to drop first, then the
     * offer order; finally the items themselves, so that the order is total and never depends on the candidates' order.
     */
    private static final Comparator<Option> BEST_FIRST = Comparator.comparingInt(
                    (Option option) -> option.estimate().etaMinutes())
            .thenComparingInt(option -> option.removed().size())
            .thenComparing(Option::estimate, FASTEST)
            .thenComparing(option -> option.removed().stream().map(Sku::value).collect(Collectors.joining(",")));

    private final PickTimeModel pickTime;
    private final StoreLoadProvider storeLoad;
    private final PromiseCalibration calibration;

    public PromiseCalculator(PickTimeModel pickTime, StoreLoadProvider storeLoad, PromiseCalibration calibration) {
        this.pickTime = Objects.requireNonNull(pickTime, "pickTime");
        this.storeLoad = Objects.requireNonNull(storeLoad, "storeLoad");
        this.calibration = Objects.requireNonNull(calibration, "calibration");
    }

    /**
     * @param candidates the stores that accept orders and reach the customer
     * @param stock what those stores have, for the basket's SKUs
     */
    public PromiseDecision decide(Basket basket, List<Candidate> candidates, Stock stock) {
        if (candidates.isEmpty()) {
            return new PromiseDecision.Unavailable(UnavailableReason.OUT_OF_COVERAGE, List.of());
        }
        Optional<Estimate> fastest = fastestFor(basket.lines(), candidates, stock);
        Optional<Quote> offer = fastest.flatMap(Estimate::quote);
        List<Suggestion> suggestions = suggestions(
                basket, candidates, stock, offer.map(Quote::etaMinutes).orElse(Integer.MAX_VALUE));
        if (offer.isPresent()) {
            return new PromiseDecision.Offered(offer.get(), suggestions);
        }
        UnavailableReason reason = fastest.isPresent() ? UnavailableReason.NO_CAPACITY : UnavailableReason.OUT_OF_STOCK;
        return new PromiseDecision.Unavailable(reason, suggestions);
    }

    /** The fastest of the candidates able to supply every one of {@code lines}, if any. */
    private Optional<Estimate> fastestFor(List<SkuQuantity> lines, List<Candidate> candidates, Stock stock) {
        int units = lines.stream().mapToInt(SkuQuantity::quantity).sum();
        return candidates.stream()
                .filter(candidate -> lines.stream().allMatch(line -> stock.covers(candidate.storeId(), line)))
                .map(candidate -> estimate(candidate, units))
                .min(FASTEST);
    }

    /** @param etaToBeat the offer's ETA in minutes, or {@link Integer#MAX_VALUE} when there is no offer */
    private List<Suggestion> suggestions(Basket basket, List<Candidate> candidates, Stock stock, int etaToBeat) {
        List<List<Sku>> removals = candidates.stream()
                .map(candidate -> lacking(basket, candidate, stock))
                .filter(removed ->
                        !removed.isEmpty() && removed.size() < basket.lines().size())
                .distinct()
                .toList();
        List<Option> options = new ArrayList<>();
        for (List<Sku> removed : removals) {
            List<SkuQuantity> rest = basket.lines().stream()
                    .filter(line -> !removed.contains(line.sku()))
                    .toList();
            fastestFor(rest, candidates, stock)
                    .filter(best -> lacking(basket, best.candidate(), stock).equals(removed))
                    // The spec's "strictly better tier or ETA" is exactly this: a faster tier always means fewer
                    // minutes, and fewer seconds within the same minute change nothing the customer sees.
                    .filter(best -> best.tier().isPresent() && best.etaMinutes() < etaToBeat)
                    .ifPresent(best -> options.add(new Option(best, removed)));
        }
        options.sort(BEST_FIRST);
        List<Option> chosen = new ArrayList<>(MAX_SUGGESTIONS);
        for (Option option : options) {
            if (chosen.size() == MAX_SUGGESTIONS) {
                break;
            }
            if (chosen.stream().noneMatch(better -> better.dominates(option))) {
                chosen.add(option);
            }
        }
        return chosen.stream().map(Option::suggestion).toList();
    }

    /** The items of {@code basket} that {@code candidate} cannot supply in full, in SKU order. */
    private static List<Sku> lacking(Basket basket, Candidate candidate, Stock stock) {
        return basket.lines().stream()
                .filter(line -> !stock.covers(candidate.storeId(), line))
                .map(SkuQuantity::sku)
                .toList();
    }

    /** @param units how many units the store would pick */
    private Estimate estimate(Candidate candidate, int units) {
        StoreLoad load = storeLoad.loadAt(candidate.storeId());
        EtaBreakdown breakdown = EtaBreakdown.of(
                load.pickerQueue(),
                pickTime.pickTime(units),
                load.riderWait(),
                candidate.travelTime(),
                calibration.bufferAt(candidate.storeId()));
        return new Estimate(candidate, breakdown);
    }

    /** One store's ETA for a basket it can supply. */
    private record Estimate(Candidate candidate, EtaBreakdown breakdown) {

        int totalSeconds() {
            return breakdown.totalSeconds();
        }

        int etaMinutes() {
            return breakdown.etaMinutes();
        }

        Optional<DeliveryTier> tier() {
            return DeliveryTier.forEta(etaMinutes());
        }

        Optional<Quote> quote() {
            return tier().map(tier -> new Quote(candidate.storeId(), tier, breakdown));
        }
    }

    /** A suggestion in the making: without {@code removed}, the rest of the basket is promised as {@code estimate}. */
    private record Option(Estimate estimate, List<Sku> removed) {

        Suggestion suggestion() {
            return new Suggestion(
                    estimate.candidate().storeId(),
                    removed,
                    estimate.etaMinutes(),
                    estimate.tier().orElseThrow());
        }

        /** Asks the customer to give up no more than {@code other} does and is at least as fast: {@code other} is moot. */
        boolean dominates(Option other) {
            return other.removed().containsAll(removed)
                    && estimate.etaMinutes() <= other.estimate().etaMinutes();
        }
    }
}
