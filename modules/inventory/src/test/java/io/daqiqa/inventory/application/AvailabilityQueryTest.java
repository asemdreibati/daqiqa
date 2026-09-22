package io.daqiqa.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.daqiqa.inventory.api.AvailabilityQuery;
import io.daqiqa.inventory.api.StockSnapshot;
import io.daqiqa.inventory.support.InventoryIntegrationTest;
import io.daqiqa.kernel.StoreId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AvailabilityQueryTest extends InventoryIntegrationTest {

    @Autowired
    AvailabilityQuery availability;

    @Test
    void answersForEveryCandidateStoreAndSkuAtOnce() {
        tables.stock(MARINA, MILK, 10, 4);
        tables.stock(MARINA, BREAD, 3, 3);
        tables.stock(JLT, MILK, 7);
        tables.stock(JLT, WATER, 12);

        StockSnapshot snapshot =
                availability.availability(List.of(MARINA, JLT, StoreId.of("DXB-DOWNTOWN")), List.of(MILK, BREAD));

        assertThat(snapshot.available()).isEqualTo(Map.of(MARINA, Map.of(MILK, 6, BREAD, 0), JLT, Map.of(MILK, 7)));
        assertThat(snapshot.available(JLT, BREAD)).as("not stocked").isZero();
    }

    @Test
    void anEmptyQuestionHasAnEmptyAnswer() {
        tables.stock(MARINA, MILK, 10);

        assertThat(availability.availability(List.of(), List.of(MILK)).available())
                .isEmpty();
        assertThat(availability.availability(List.of(MARINA), List.of()).available())
                .isEmpty();
    }
}
