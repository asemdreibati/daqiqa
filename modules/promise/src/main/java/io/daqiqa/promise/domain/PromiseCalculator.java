package io.daqiqa.promise.domain;

import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.promise.api.DeliveryTier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The promise engine's decision (steps 3 to 6 of {@code docs/design/promise.md}), a pure function of already-fetched
 * data: no I/O, no clock, deterministic. Given the stores that reach the customer and what they have in stock:
 *
 * <ol>
 *   <li>Each store gets an ETA for the basket: its picker queue and rider wait (from its current load), the time to
 *       pick the basket's units, the ride, and the calibrated safety buffer.
 *   <li>The offer comes from the store with the smallest ETA among those able to supply the <em>whole</em> basket;
 *       equal ETAs go to the nearer store, then to the smaller id. No such store: {@code OUT_OF_STOCK}. Its ETA fits no
 *       delivery tier: {@code NO_CAPACITY}. No store at all: {@code OUT_OF_COVERAGE}.
 *   <li>Suggestions come from stores that lack some items: without exactly those items (never all of them), the store
 *       must deliver within a tier and strictly sooner than the offer (or at all, when there is no offer). A suggestion
 *       that asks the customer to give up more items than another without being faster is pointless and left out. At
 *       most {@value #MAX_SUGGESTIONS}, fastest first.
 * </ol>
 */
public final class PromiseCalculator {

    public static final int MAX_SUGGESTIONS = 2;

    /** Fastest first; equal ETAs go to the nearer store, then to the smaller id, so the decision is deterministic. */
    private static final Comparator<Estimate> FASTEST = Comparator.comparingInt(Estimate::etaMinutes)
            .thenComparingInt(estimate -> estimate.candidate().distanceMeters())
            .thenComparing(estimate -> estimate.candidate().storeId().value());

    /** Fastest first; among equally fast suggestions, the one that removes fewer items comes first. */
    private static final Comparator<Estimate> BEST_SUGGESTION_FIRST = Comparator.comparingInt(Estimate::etaMinutes)
            .thenComparingInt(estimate -> estimate.removed().size())
            .thenComparing(FASTEST);

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
        List<Estimate> wholeBasket = new ArrayList<>();
        List<Estimate> partBasket = new ArrayList<>();
        for (Candidate candidate : candidates) {
            List<SkuQuantity> supplied = new ArrayList<>();
            List<Sku> missing = new ArrayList<>();
            for (SkuQuantity line : basket.lines()) {
                if (stock.covers(candidate.storeId(), line)) {
                    supplied.add(line);
                } else {
                    missing.add(line.sku());
                }
            }
            if (missing.isEmpty()) {
                wholeBasket.add(estimate(candidate, basket.lines(), List.of()));
            } else if (!supplied.isEmpty()) {
                partBasket.add(estimate(candidate, supplied, missing));
            }
        }

        Optional<Estimate> fastest = wholeBasket.stream().min(FASTEST);
        Optional<Quote> offer = fastest.flatMap(Estimate::quote);
        List<Suggestion> suggestions =
                suggestions(partBasket, offer.map(Quote::etaMinutes).orElse(Integer.MAX_VALUE));
        if (offer.isPresent()) {
            return new PromiseDecision.Offered(offer.get(), suggestions);
        }
        UnavailableReason reason = fastest.isPresent() ? UnavailableReason.NO_CAPACITY : UnavailableReason.OUT_OF_STOCK;
        return new PromiseDecision.Unavailable(reason, suggestions);
    }

    private Estimate estimate(Candidate candidate, List<SkuQuantity> lines, List<Sku> removed) {
        StoreLoad load = storeLoad.loadAt(candidate.storeId());
        int units = lines.stream().mapToInt(SkuQuantity::quantity).sum();
        EtaBreakdown breakdown = EtaBreakdown.of(
                load.pickerQueue(),
                pickTime.pickTime(units),
                load.riderWait(),
                candidate.travelTime(),
                calibration.bufferAt(candidate.storeId()));
        return new Estimate(candidate, breakdown, removed);
    }

    /** @param etaToBeat the offer's ETA, or {@link Integer#MAX_VALUE} when there is no offer */
    private static List<Suggestion> suggestions(List<Estimate> partBasket, int etaToBeat) {
        // The spec asks for "a strictly better tier or ETA"; a faster tier always means fewer minutes, so a strictly
        // smaller ETA is the whole condition.
        List<Estimate> faster = partBasket.stream()
                .filter(estimate -> estimate.tier().isPresent() && estimate.etaMinutes() < etaToBeat)
                .sorted(BEST_SUGGESTION_FIRST)
                .toList();
        List<Estimate> chosen = new ArrayList<>(MAX_SUGGESTIONS);
        for (Estimate estimate : faster) {
            if (chosen.size() == MAX_SUGGESTIONS) {
                break;
            }
            if (chosen.stream().noneMatch(better -> better.dominates(estimate))) {
                chosen.add(estimate);
            }
        }
        return chosen.stream().map(Estimate::suggestion).toList();
    }

    /** One store's ETA for the whole basket ({@code removed} is empty) or for the part of it the store can supply. */
    private record Estimate(Candidate candidate, EtaBreakdown breakdown, List<Sku> removed) {

        int etaMinutes() {
            return breakdown.etaMinutes();
        }

        Optional<DeliveryTier> tier() {
            return DeliveryTier.forEta(etaMinutes());
        }

        Optional<Quote> quote() {
            return tier().map(tier -> new Quote(candidate.storeId(), tier, breakdown));
        }

        Suggestion suggestion() {
            return new Suggestion(candidate.storeId(), removed, etaMinutes(), tier().orElseThrow());
        }

        /** Asks the customer to give up no more than {@code other} does and is at least as fast: {@code other} is moot. */
        boolean dominates(Estimate other) {
            return other.removed().containsAll(removed) && etaMinutes() <= other.etaMinutes();
        }
    }
}
