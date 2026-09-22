package io.daqiqa.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.daqiqa.kernel.ReservationId;
import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.kernel.StoreId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

/**
 * Whatever happens, in whatever order, a store never holds more units than it has: {@code 0 <= reserved <= onHand}
 * for every SKU, and {@code reserved} is exactly what the reservations still holding stock hold.
 *
 * <p>The properties drive the domain's decisions (shortfalls, the reservation state machine, the adjustment rule)
 * through random sequences of operations. The stock arithmetic is modelled here the way the SQL applies it (reserve
 * adds a basket's lines, ending a reservation subtracts them); an operation the domain let through that broke the
 * invariant would fail {@link StockLevel}'s own validation.
 */
class InventoryInvariantProperties {

    private static final StoreId STORE = StoreId.of("DXB-MARINA");
    private static final List<Sku> SKUS = List.of(Sku.of("MILK-FULL-1L"), Sku.of("BREAD-WHITE"), Sku.of("EGGS-12"));

    @Property(tries = 1_000)
    void reservedStaysBetweenZeroAndOnHandWhateverTheSequenceOfOperations(
            @ForAll @Size(3) List<@IntRange(max = 12) Integer> initialOnHand,
            @ForAll("operations") List<Operation> operations) {
        Store store = new Store(initialOnHand);

        for (Operation operation : operations) {
            store.apply(operation);

            for (Sku sku : SKUS) {
                StockLevel level = store.level(sku);
                assertThat(level.reserved())
                        .as("reserved %s after %s", sku, operation)
                        .isBetween(0, level.onHand());
                assertThat(level.reserved())
                        .as("reserved %s equals what active reservations hold, after %s", sku, operation)
                        .isEqualTo(store.heldByActiveReservations(sku));
            }
        }
    }

    @Property(tries = 1_000)
    void aBasketHasNoShortfallExactlyWhenEveryLineIsCovered(
            @ForAll @Size(3) List<@IntRange(max = 6) Integer> onHand,
            @ForAll @Size(3) List<@IntRange(max = 6) Integer> reserved,
            @ForAll("baskets") List<SkuQuantity> basket) {
        List<StockLevel> levels = new ArrayList<>();
        for (int i = 0; i < SKUS.size(); i++) {
            levels.add(new StockLevel(STORE, SKUS.get(i), onHand.get(i) + reserved.get(i), reserved.get(i)));
        }
        StoreStock stock = StoreStock.of(STORE, levels);

        List<Shortfall> shortfalls = stock.shortfalls(basket);

        assertThat(shortfalls.isEmpty())
                .isEqualTo(
                        basket.stream().allMatch(line -> stock.level(line.sku()).available() >= line.quantity()));
        assertThat(shortfalls).allSatisfy(shortfall -> {
            assertThat(shortfall.available())
                    .isEqualTo(stock.level(shortfall.sku()).available());
            assertThat(shortfall.available()).isLessThan(shortfall.requested());
        });
    }

    @Provide
    Arbitrary<List<Operation>> operations() {
        Arbitrary<Integer> anyReservation = Arbitraries.integers().between(0, 1_000);
        Arbitrary<Operation> operation = Arbitraries.<Operation>frequencyOf(
                Tuple.of(4, baskets().map(Reserve::new)),
                Tuple.of(2, anyReservation.map(Confirm::new)),
                Tuple.of(2, anyReservation.map(Release::new)),
                Tuple.of(1, Arbitraries.just(new Sweep())),
                Tuple.of(
                        2,
                        Arbitraries.integers()
                                .between(1, 600)
                                .map(seconds -> new Advance(Duration.ofSeconds(seconds)))),
                Tuple.of(
                        2,
                        Combinators.combine(
                                        Arbitraries.of(SKUS),
                                        Arbitraries.integers().between(-8, 8).filter(delta -> delta != 0))
                                .as(Adjust::new)));
        return operation.list().ofMaxSize(60);
    }

    @Provide
    Arbitrary<List<SkuQuantity>> baskets() {
        Arbitrary<Set<Sku>> skus = Arbitraries.of(SKUS).set().ofMinSize(1).ofMaxSize(SKUS.size());
        Arbitrary<List<Integer>> quantities =
                Arbitraries.integers().between(1, 5).list().ofSize(SKUS.size());
        return Combinators.combine(skus, quantities).as((chosen, quantity) -> {
            List<SkuQuantity> lines = new ArrayList<>();
            int i = 0;
            for (Sku sku : chosen) {
                lines.add(new SkuQuantity(sku, quantity.get(i++)));
            }
            return List.copyOf(lines);
        });
    }

    sealed interface Operation {}

    record Reserve(List<SkuQuantity> lines) implements Operation {}

    record Confirm(int reservation) implements Operation {}

    record Release(int reservation) implements Operation {}

    record Sweep() implements Operation {}

    record Advance(Duration duration) implements Operation {}

    record Adjust(Sku sku, int delta) implements Operation {}

    /** One store in memory: stock levels, reservations and a clock, changed only through the domain's decisions. */
    private static final class Store {

        private static final Duration HOLD = Duration.ofMinutes(5);

        private final Map<Sku, StockLevel> levels = new HashMap<>();
        private final List<Reservation> reservations = new ArrayList<>();
        private Instant now = Instant.parse("2026-09-22T10:00:00Z");

        Store(List<Integer> initialOnHand) {
            for (int i = 0; i < SKUS.size(); i++) {
                levels.put(SKUS.get(i), new StockLevel(STORE, SKUS.get(i), initialOnHand.get(i), 0));
            }
        }

        StockLevel level(Sku sku) {
            return levels.get(sku);
        }

        int heldByActiveReservations(Sku sku) {
            return reservations.stream()
                    .filter(reservation -> reservation.status().holdsStock())
                    .flatMap(reservation -> reservation.lines().stream())
                    .filter(line -> line.sku().equals(sku))
                    .mapToInt(SkuQuantity::quantity)
                    .sum();
        }

        void apply(Operation operation) {
            switch (operation) {
                case Reserve(List<SkuQuantity> lines) -> reserve(lines);
                case Confirm(int index) -> {
                    if (!reservations.isEmpty()) {
                        confirm(index % reservations.size());
                    }
                }
                case Release(int index) -> {
                    if (!reservations.isEmpty()) {
                        release(index % reservations.size());
                    }
                }
                case Sweep() -> sweep();
                case Advance(Duration duration) -> now = now.plus(duration);
                case Adjust(Sku sku, int delta) -> adjust(sku, delta);
            }
        }

        private void reserve(List<SkuQuantity> lines) {
            if (!StoreStock.of(STORE, levels.values()).shortfalls(lines).isEmpty()) {
                return; // rejected: nothing is held
            }
            reservations.add(Reservation.hold(ReservationId.newId(), STORE, "ref", lines, now, HOLD));
            lines.forEach(line -> changeReserved(line.sku(), line.quantity()));
        }

        private void confirm(int index) {
            switch (reservations.get(index).confirm(now)) {
                case ConfirmOutcome.Confirmed(Reservation confirmed) -> reservations.set(index, confirmed);
                case ConfirmOutcome.HoldElapsed(Reservation expired) -> end(index, expired);
                case ConfirmOutcome.AlreadyConfirmed() -> {}
                case ConfirmOutcome.NoLongerHeld(ReservationStatus status) -> {}
            }
        }

        private void release(int index) {
            switch (reservations.get(index).release()) {
                case ReleaseOutcome.Released(Reservation released) -> end(index, released);
                case ReleaseOutcome.AlreadyEnded(ReservationStatus status) -> {}
            }
        }

        private void sweep() {
            for (int i = 0; i < reservations.size(); i++) {
                Reservation reservation = reservations.get(i);
                if (reservation.status() == ReservationStatus.HELD && reservation.isElapsedAt(now)) {
                    end(i, reservation.expire(now));
                }
            }
        }

        private void adjust(Sku sku, int delta) {
            try {
                levels.put(sku, levels.get(sku).adjust(delta));
            } catch (StockBelowReservedException refused) {
                // refused: nothing changes
            }
        }

        /** A reservation stopped holding stock: its units go back on sale. */
        private void end(int index, Reservation ended) {
            reservations.set(index, ended);
            ended.lines().forEach(line -> changeReserved(line.sku(), -line.quantity()));
        }

        private void changeReserved(Sku sku, int change) {
            StockLevel level = levels.get(sku);
            // StockLevel refuses to exist outside 0 <= reserved <= onHand: a bad decision fails right here.
            levels.put(sku, new StockLevel(STORE, sku, level.onHand(), level.reserved() + change));
        }
    }
}
