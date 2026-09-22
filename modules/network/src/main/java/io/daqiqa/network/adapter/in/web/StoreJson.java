package io.daqiqa.network.adapter.in.web;

import io.daqiqa.network.api.DarkStore;
import io.daqiqa.network.api.StoreStatus;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** JSON shapes of the store endpoints. Kept separate from API types so the wire format is explicit. */
final class StoreJson {

    private StoreJson() {}

    record StoreDto(
            String id,
            NameDto name,
            LocationDto location,
            StoreStatus status,
            int activePickers,
            int serviceRadiusMeters) {

        static StoreDto from(DarkStore store) {
            return new StoreDto(
                    store.id().value(),
                    new NameDto(store.name().en(), store.name().ar()),
                    new LocationDto(
                            store.location().latitude(), store.location().longitude()),
                    store.status(),
                    store.activePickers(),
                    store.serviceRadiusMeters());
        }
    }

    record NameDto(String en, String ar) {}

    record LocationDto(double latitude, double longitude) {}

    record StoreListDto(List<StoreDto> items) {}

    /** Nullable so that a missing status is a validation error, not a default. */
    record StoreStatusUpdateDto(@NotNull @Nullable StoreStatus status) {}
}
