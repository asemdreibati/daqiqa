package io.daqiqa.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.daqiqa.contracts.events.LineItemV1;
import io.daqiqa.contracts.events.ReservationExpiredV1;
import io.daqiqa.inventory.api.InventoryReservations;
import io.daqiqa.inventory.api.ReservationRequest;
import io.daqiqa.inventory.api.ReservationResult.Reserved;
import io.daqiqa.inventory.support.InventoryIntegrationTest;
import io.daqiqa.inventory.support.InventoryTables.ReservationRow;
import io.daqiqa.inventory.support.InventoryTables.StockRow;
import io.daqiqa.kernel.SkuQuantity;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReservationExpirySweeperTest extends InventoryIntegrationTest {

    @Autowired
    InventoryReservations reservations;

    @Autowired
    ReservationService reservationService;

    @Autowired
    ReservationExpirySweeper sweeper;

    @Test
    void returnsTheStockOfHoldsNobodyConfirmedInTime() {
        tables.stock(MARINA, MILK, 10);
        tables.stock(MARINA, BREAD, 10);
        Reserved abandoned =
                reserve("order-1", Duration.ofMinutes(1), new SkuQuantity(MILK, 2), new SkuQuantity(BREAD, 1));
        reserve("order-2", Duration.ofMinutes(5), new SkuQuantity(MILK, 3));
        Reserved confirmed = reserve("order-3", Duration.ofMinutes(1), new SkuQuantity(MILK, 4));
        reservations.confirm(confirmed.reservationId());
        double swept = counter("daqiqa.inventory.expirations", "trigger", "sweeper");
        clock.advance(Duration.ofMinutes(2));

        assertThat(sweeper.sweep()).isEqualTo(1);

        assertThat(tables.reservations())
                .extracting(ReservationRow::reference, ReservationRow::status, ReservationRow::releaseReason)
                .containsExactly(
                        tuple("order-1", "EXPIRED", "EXPIRED"),
                        tuple("order-2", "HELD", null),
                        tuple("order-3", "CONFIRMED", null));
        assertThat(tables.stockOf(MARINA, MILK)).extracting(StockRow::reserved).isEqualTo(7);
        assertThat(tables.stockOf(MARINA, BREAD)).extracting(StockRow::reserved).isEqualTo(0);
        assertThat(outbox.committedEventsOfType(ReservationExpiredV1.class))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.reservationId())
                            .isEqualTo(abandoned.reservationId().value());
                    assertThat(event.reference()).isEqualTo("order-1");
                    assertThat(event.storeId()).isEqualTo("DXB-MARINA");
                    assertThat(event.lines())
                            .containsExactly(new LineItemV1(BREAD.value(), 1), new LineItemV1(MILK.value(), 2));
                    assertThat(event.occurredAt()).isEqualTo(clock.instant());
                    assertThat(event.partitionKey()).isEqualTo("DXB-MARINA");
                });
        assertThat(counter("daqiqa.inventory.expirations", "trigger", "sweeper"))
                .isEqualTo(swept + 1);
        assertThat(tables.unbalancedBooks()).isEmpty();
    }

    @Test
    void aHoldExpiresAtItsExpiryTimeAndNotAMomentBefore() {
        tables.stock(MARINA, MILK, 10);
        reserve("order-1", Duration.ofMinutes(1), new SkuQuantity(MILK, 2));

        clock.advance(Duration.ofMinutes(1).minusMillis(1));
        assertThat(sweeper.sweep()).isZero();

        clock.advance(Duration.ofMillis(1));
        assertThat(sweeper.sweep()).isEqualTo(1);
        assertThat(sweeper.sweep()).as("nothing left to do").isZero();
    }

    @Test
    void drainsABacklogLargerThanOneBatchInSeveralShortTransactions() {
        tables.stock(MARINA, MILK, 50);
        tables.stock(JLT, MILK, 50);
        IntStream.range(0, 11).forEach(i -> reserve("marina-" + i, Duration.ofMinutes(1), new SkuQuantity(MILK, 1)));
        IntStream.range(0, 12)
                .forEach(i -> reservations.reserve(new ReservationRequest(
                        JLT, "jlt-" + i, List.of(new SkuQuantity(MILK, 2)), Duration.ofMinutes(1))));
        clock.advance(Duration.ofMinutes(1));

        int expired = new ReservationExpirySweeper(reservationService, clock, 4).sweep();

        assertThat(expired).isEqualTo(23);
        assertThat(tables.reservations()).extracting(ReservationRow::status).containsOnly("EXPIRED");
        assertThat(tables.stockOf(MARINA, MILK).reserved()).isZero();
        assertThat(tables.stockOf(JLT, MILK).reserved()).isZero();
        assertThat(outbox.committedEventsOfType(ReservationExpiredV1.class)).hasSize(23);
    }

    private Reserved reserve(String reference, Duration holdFor, SkuQuantity... lines) {
        return (Reserved) reservations.reserve(new ReservationRequest(MARINA, reference, List.of(lines), holdFor));
    }
}
