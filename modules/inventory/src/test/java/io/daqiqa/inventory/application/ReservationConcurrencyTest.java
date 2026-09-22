package io.daqiqa.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.daqiqa.contracts.events.ReservationExpiredV1;
import io.daqiqa.inventory.api.InventoryReservations;
import io.daqiqa.inventory.api.ReleaseReason;
import io.daqiqa.inventory.api.ReservationNoLongerHeldException;
import io.daqiqa.inventory.api.ReservationRequest;
import io.daqiqa.inventory.api.ReservationResult;
import io.daqiqa.inventory.api.ReservationResult.Rejected;
import io.daqiqa.inventory.api.ReservationResult.Reserved;
import io.daqiqa.inventory.api.Shortage;
import io.daqiqa.inventory.domain.AdjustmentReason;
import io.daqiqa.inventory.domain.StockBelowReservedException;
import io.daqiqa.inventory.support.Concurrently;
import io.daqiqa.inventory.support.InventoryIntegrationTest;
import io.daqiqa.inventory.support.InventoryTables.AdjustmentRow;
import io.daqiqa.inventory.support.InventoryTables.ReservationRow;
import io.daqiqa.inventory.support.InventoryTables.StockRow;
import io.daqiqa.kernel.ReservationId;
import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.SkuQuantity;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The headline guarantees under real contention, against real PostgreSQL: many threads, released together by a
 * starting gate, and the database checked afterwards (not just the return values).
 */
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class ReservationConcurrencyTest extends InventoryIntegrationTest {

    private static final Duration HOLD = Duration.ofMinutes(5);
    private static final List<Sku> SKUS = List.of(MILK, BREAD, WATER);

    @Autowired
    InventoryReservations reservations;

    @Autowired
    ReservationService reservationService;

    @Autowired
    ReservationExpirySweeper sweeper;

    @Autowired
    StockService stock;

    @Autowired
    InventoryMetrics metrics;

    @Test
    void twoHundredCustomersRacingForFiftyUnitsGetExactlyFiftyOfThem() throws Exception {
        tables.stock(MARINA, MILK, 50);

        List<ReservationResult> results =
                Concurrently.run(200, i -> reservations.reserve(basket("order-" + i, new SkuQuantity(MILK, 1))));

        assertThat(results).filteredOn(Reserved.class::isInstance).hasSize(50);
        assertThat(results)
                .filteredOn(Rejected.class::isInstance)
                .hasSize(150)
                .allSatisfy(rejected ->
                        assertThat(((Rejected) rejected).shortages()).containsExactly(new Shortage(MILK, 1, 0)));
        assertThat(tables.stockOf(MARINA, MILK)).isEqualTo(new StockRow(50, 50, clock.instant()));
        assertThat(tables.reservations())
                .hasSize(50)
                .extracting(ReservationRow::status)
                .containsOnly("HELD");
        assertThat(tables.unbalancedBooks()).isEmpty();
    }

    @Test
    void basketsSharingSkusInOppositeOrdersQueueInsteadOfDeadlocking() throws Exception {
        SKUS.forEach(sku -> tables.stock(MARINA, sku, 40));
        List<ReservationRequest> requests = IntStream.range(0, 120)
                .mapToObj(i -> i % 2 == 0
                        ? basket("order-" + i, line(MILK, 1 + i % 3), line(BREAD, 1), line(WATER, 2))
                        : basket("order-" + i, line(WATER, 1 + i % 3), line(BREAD, 2), line(MILK, 1)))
                .toList();

        List<ReservationResult> results = Concurrently.run(120, i -> reservations.reserve(requests.get(i)));

        Map<Sku, Integer> heldBySuccessfulBaskets = IntStream.range(0, 120)
                .filter(i -> results.get(i) instanceof Reserved)
                .boxed()
                .flatMap(i -> requests.get(i).lines().stream())
                .collect(Collectors.groupingBy(SkuQuantity::sku, Collectors.summingInt(SkuQuantity::quantity)));
        assertThat(results).filteredOn(Reserved.class::isInstance).isNotEmpty();
        assertThat(results).filteredOn(Rejected.class::isInstance).isNotEmpty();
        for (Sku sku : SKUS) {
            StockRow row = tables.stockOf(MARINA, sku);
            assertThat(row.reserved()).as("reserved %s", sku).isEqualTo(heldBySuccessfulBaskets.get(sku));
            assertThat(row.reserved()).as("reserved %s", sku).isLessThanOrEqualTo(row.onHand());
        }
        assertThat(tables.unbalancedBooks()).isEmpty();
    }

    @Test
    void reservingConfirmingReleasingSweepingAndAdjustingAllAtOnceKeepsTheBooksBalanced() throws Exception {
        stores.add(MARINA);
        catalog.add(MILK, BREAD, WATER);
        SKUS.forEach(sku -> tables.stock(MARINA, sku, 40));

        Concurrently.run(32, worker -> {
            Random random = new Random(worker);
            List<ReservationId> mine = new ArrayList<>();
            for (int step = 0; step < 25; step++) {
                switch (random.nextInt(10)) {
                    case 0, 1, 2, 3 -> {
                        if (reservations.reserve(randomBasket(random, "w" + worker + "-" + step))
                                instanceof Reserved reserved) {
                            mine.add(reserved.reservationId());
                        }
                    }
                    case 4 -> {
                        if (!mine.isEmpty()) {
                            confirmUnlessGone(mine.get(random.nextInt(mine.size())));
                        }
                    }
                    case 5, 6 -> {
                        if (!mine.isEmpty()) {
                            reservations.release(mine.get(random.nextInt(mine.size())), ReleaseReason.ORDER_CANCELLED);
                        }
                    }
                    case 7 -> sweeper.sweep();
                    case 8 -> clock.advance(Duration.ofSeconds(10));
                    default -> adjustUnlessRefused(SKUS.get(random.nextInt(SKUS.size())), random.nextInt(11) - 5);
                }
            }
            return mine.size();
        });

        assertThat(tables.unbalancedBooks()).isEmpty();
        List<AdjustmentRow> adjustments = tables.adjustments();
        for (Sku sku : SKUS) {
            int adjusted = adjustments.stream()
                    .filter(adjustment -> adjustment.sku().equals(sku.value()))
                    .mapToInt(AdjustmentRow::delta)
                    .sum();
            assertThat(tables.stockOf(MARINA, sku).onHand())
                    .as("on hand %s", sku)
                    .isEqualTo(40 + adjusted);
        }
        List<ReservationRow> expired = tables.reservations().stream()
                .filter(reservation -> reservation.status().equals("EXPIRED"))
                .toList();
        assertThat(outbox.committedEventsOfType(ReservationExpiredV1.class))
                .as("exactly one event per expired hold")
                .extracting(ReservationExpiredV1::reservationId)
                .containsExactlyInAnyOrderElementsOf(
                        expired.stream().map(ReservationRow::id).toList());
    }

    @ParameterizedTest(name = "{0} units on hand")
    @ValueSource(ints = {3, 300})
    void simultaneousFirstCallsWithOneReferenceHoldTheBasketOnce(int onHand) throws Exception {
        tables.stock(MARINA, MILK, onHand);

        List<ReservationResult> results =
                Concurrently.run(40, i -> reservations.reserve(basket("order-1", new SkuQuantity(MILK, 3))));

        assertThat(results).allSatisfy(result -> assertThat(result).isInstanceOf(Reserved.class));
        assertThat(results)
                .extracting(result -> ((Reserved) result).reservationId())
                .containsOnly(((Reserved) results.getFirst()).reservationId());
        assertThat(tables.reservations()).hasSize(1);
        assertThat(tables.stockOf(MARINA, MILK).reserved()).isEqualTo(3);
    }

    @Test
    void sweepersRunningSideBySideExpireEveryHoldExactlyOnce() throws Exception {
        SKUS.forEach(sku -> {
            tables.stock(MARINA, sku, 1_000);
            tables.stock(JLT, sku, 1_000);
        });
        Random random = new Random(42);
        for (int i = 0; i < 300; i++) {
            ReservationRequest request = randomBasket(random, "order-" + i);
            reservations.reserve(new ReservationRequest(
                    i % 2 == 0 ? MARINA : JLT, request.reference(), request.lines(), Duration.ofSeconds(30)));
        }
        clock.advance(Duration.ofMinutes(1));

        List<Integer> expired = Concurrently.run(
                6,
                i -> new ReservationExpirySweeper(reservationService, metrics, clock, 10, Duration.ofSeconds(5))
                        .sweep());

        assertThat(expired.stream().mapToInt(Integer::intValue).sum()).isEqualTo(300);
        assertThat(tables.reservations()).extracting(ReservationRow::status).containsOnly("EXPIRED");
        assertThat(outbox.committedEventsOfType(ReservationExpiredV1.class))
                .extracting(ReservationExpiredV1::reservationId)
                .doesNotHaveDuplicates()
                .hasSize(300);
        SKUS.forEach(sku -> {
            assertThat(tables.stockOf(MARINA, sku).reserved()).isZero();
            assertThat(tables.stockOf(JLT, sku).reserved()).isZero();
        });
    }

    @Test
    void retriesOfRunOutHoldsRacingCheckoutsAndSweepersExpireEachHoldOnceWithoutDeadlocks() throws Exception {
        SKUS.forEach(sku -> {
            tables.stock(MARINA, sku, 60);
            tables.stock(JLT, sku, 60);
        });
        Random random = new Random(7);
        List<ReservationRequest> originals = IntStream.range(0, 20)
                .mapToObj(i -> randomBasket(random, "order-" + i))
                .toList();
        originals.forEach(reservations::reserve);
        clock.advance(Duration.ofMinutes(1)); // every original hold has run out, none swept yet

        Concurrently.run(64, task -> {
            if (task < 40) { // two retries per reference, at the other store, with another basket
                ReservationRequest retry =
                        randomBasket(new Random(task), originals.get(task % 20).reference());
                return reservations.reserve(new ReservationRequest(JLT, retry.reference(), retry.lines(), HOLD));
            }
            if (task < 60) {
                return reservations.reserve(randomBasket(new Random(task), "fresh-" + task));
            }
            return sweeper.sweep();
        });

        assertThat(tables.unbalancedBooks()).isEmpty();
        Map<String, Long> activePerReference = tables.reservations().stream()
                .filter(reservation -> reservation.status().equals("HELD")
                        || reservation.status().equals("CONFIRMED"))
                .collect(Collectors.groupingBy(ReservationRow::reference, Collectors.counting()));
        assertThat(activePerReference.values())
                .allSatisfy(count -> assertThat(count).isEqualTo(1));
        assertThat(outbox.committedEventsOfType(ReservationExpiredV1.class))
                .extracting(ReservationExpiredV1::reference)
                .as("each original hold expired exactly once, by a retry or a sweeper")
                .containsExactlyInAnyOrderElementsOf(
                        originals.stream().map(ReservationRequest::reference).toList());
    }

    private void confirmUnlessGone(ReservationId reservation) {
        try {
            reservations.confirm(reservation);
        } catch (ReservationNoLongerHeldException expected) {
            // released or expired by now: a normal outcome under this mix
        }
    }

    private void adjustUnlessRefused(Sku sku, int delta) {
        if (delta == 0) {
            return;
        }
        try {
            stock.adjust(new AdjustStock(
                    MARINA, sku, delta, delta > 0 ? AdjustmentReason.RECEIVED : AdjustmentReason.DAMAGED));
        } catch (StockBelowReservedException expected) {
            // those units are promised: a normal outcome under this mix
        }
    }

    private static ReservationRequest randomBasket(Random random, String reference) {
        List<Sku> shuffled = new ArrayList<>(SKUS);
        java.util.Collections.shuffle(shuffled, random);
        List<SkuQuantity> lines = shuffled.subList(0, 1 + random.nextInt(SKUS.size())).stream()
                .map(sku -> line(sku, 1 + random.nextInt(3)))
                .toList();
        return new ReservationRequest(MARINA, reference, lines, Duration.ofSeconds(30 + random.nextInt(31)));
    }

    private static ReservationRequest basket(String reference, SkuQuantity... lines) {
        return new ReservationRequest(MARINA, reference, List.of(lines), HOLD);
    }

    private static SkuQuantity line(Sku sku, int quantity) {
        return new SkuQuantity(sku, quantity);
    }
}
