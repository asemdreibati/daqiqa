package io.daqiqa.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.daqiqa.kernel.ReservationId;
import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.kernel.StoreId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class HeldUnitsTest {

    private static final StoreId MARINA = StoreId.of("DXB-MARINA");
    private static final StoreId JLT = StoreId.of("DXB-JLT");
    private static final Sku MILK = Sku.of("MILK-FULL-1L");
    private static final Sku BREAD = Sku.of("BREAD-ARABIC-6PK");

    @Test
    void sumsWhatReservationsHoldPerStoreAndSkuInTheOrderRowsAreLocked() {
        List<Reservation> reservations = List.of(
                hold(MARINA, new SkuQuantity(MILK, 2), new SkuQuantity(BREAD, 1)),
                hold(JLT, new SkuQuantity(MILK, 4)),
                hold(MARINA, new SkuQuantity(MILK, 3)));

        assertThat(HeldUnits.heldBy(reservations))
                .containsExactly(
                        new HeldUnits(JLT, MILK, 4), new HeldUnits(MARINA, BREAD, 1), new HeldUnits(MARINA, MILK, 5));
    }

    private static Reservation hold(StoreId store, SkuQuantity... lines) {
        return Reservation.hold(
                ReservationId.newId(),
                store,
                "ref-" + ReservationId.newId(),
                List.of(lines),
                Instant.parse("2026-09-22T10:00:00Z"),
                Duration.ofMinutes(5));
    }
}
