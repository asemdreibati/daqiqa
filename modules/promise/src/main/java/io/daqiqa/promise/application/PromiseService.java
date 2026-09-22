package io.daqiqa.promise.application;

import io.daqiqa.inventory.api.AvailabilityQuery;
import io.daqiqa.inventory.api.StockSnapshot;
import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.network.api.CoverageIndex;
import io.daqiqa.network.api.StoreCoverage;
import io.daqiqa.promise.domain.Basket;
import io.daqiqa.promise.domain.Candidate;
import io.daqiqa.promise.domain.PromiseCalculator;
import io.daqiqa.promise.domain.PromiseDecision;
import io.daqiqa.promise.domain.Stock;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The promise use case, the hot path of the product: an in-memory coverage lookup, <em>one</em> database round trip
 * for stock (none when no store covers the location), a pure calculation and an HMAC. Nothing is written and nothing
 * is locked.
 */
@Service
public class PromiseService {

    private final CoverageIndex coverage;
    private final AvailabilityQuery availability;
    private final PromiseCalculator calculator;
    private final PromiseTokenCodec tokens;
    private final PromiseMetrics metrics;

    PromiseService(
            CoverageIndex coverage,
            AvailabilityQuery availability,
            PromiseCalculator calculator,
            PromiseTokenCodec tokens,
            PromiseMetrics metrics) {
        this.coverage = coverage;
        this.availability = availability;
        this.calculator = calculator;
        this.tokens = tokens;
        this.metrics = metrics;
    }

    /**
     * How fast {@code items} can reach {@code location}, as a signed promise or the reason there is none.
     *
     * @param items the basket as the customer sent it; duplicate SKUs are merged
     * @throws io.daqiqa.promise.domain.InvalidBasketException if no promise can be made for such a basket
     */
    public PromiseResult promise(GeoPoint location, List<SkuQuantity> items) {
        Timer.Sample sample = metrics.start();
        Basket basket = new Basket(items);
        List<Candidate> candidates = coverage.storesServing(location).stream()
                .map(PromiseService::toCandidate)
                .toList();
        PromiseResult result =
                switch (calculator.decide(basket, candidates, stockAt(candidates, basket))) {
                    case PromiseDecision.Offered offered ->
                        new PromiseResult.Available(tokens.issue(offered.quote(), basket), offered.suggestions());
                    case PromiseDecision.Unavailable unavailable ->
                        new PromiseResult.Unavailable(unavailable.reason(), unavailable.suggestions());
                };
        metrics.record(sample, result);
        return result;
    }

    /** One round trip for every candidate store and every SKU of the basket; none when there is no candidate. */
    private Stock stockAt(List<Candidate> candidates, Basket basket) {
        if (candidates.isEmpty()) {
            return Stock.NONE;
        }
        StockSnapshot snapshot = availability.availability(
                candidates.stream().map(Candidate::storeId).toList(), basket.skus());
        return snapshot::available;
    }

    private static Candidate toCandidate(StoreCoverage coverage) {
        return new Candidate(coverage.storeId(), coverage.estimatedTravelTime(), coverage.distanceMeters());
    }
}
