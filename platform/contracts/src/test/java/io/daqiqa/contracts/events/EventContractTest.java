package io.daqiqa.contracts.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Guards the rules in {@code package-info.java} so they cannot erode silently. */
class EventContractTest {

    private static final List<Class<? extends IntegrationEvent>> EVENTS =
            List.of(OrderPlacedV1.class, OrderAbandonedV1.class, StockAdjustedV1.class, ReservationExpiredV1.class);

    @Test
    void everyEventIsARecordWithAVersionedTypeName() throws Exception {
        for (Class<? extends IntegrationEvent> event : EVENTS) {
            assertThat(event.isRecord()).as("%s is a record", event).isTrue();
            String type = (String) event.getField("TYPE").get(null);
            assertThat(type).as("%s type name", event).matches("^daqiqa\\.[a-z]+\\.[a-z-]+\\.v\\d+$");
            assertThat(event.getSimpleName())
                    .as("class name carries the same version")
                    .endsWith("V" + type.substring(type.lastIndexOf('v') + 1));
        }
    }

    @Test
    void eventsUseOnlyJdkTypesSoTheContractStaysDecoupled() {
        for (Class<? extends IntegrationEvent> event : EVENTS) {
            for (RecordComponent component : event.getRecordComponents()) {
                Class<?> type = component.getType();
                boolean allowed = type.isPrimitive()
                        || type.getName().startsWith("java.")
                        || type.getPackageName().equals(IntegrationEvent.class.getPackageName());
                assertThat(allowed)
                        .as("%s.%s uses %s", event.getSimpleName(), component.getName(), type)
                        .isTrue();
            }
        }
        assertThat(Arrays.stream(OrderPlacedV1.class.getRecordComponents()).map(RecordComponent::getName))
                .contains("eventId", "occurredAt", "storeId");
    }
}
