package io.daqiqa.inventory.adapter.in.web;

import io.daqiqa.inventory.application.AdjustStock;
import io.daqiqa.inventory.domain.AdjustmentReason;
import io.daqiqa.inventory.domain.StockLevel;
import io.daqiqa.kernel.StoreId;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** JSON shapes of the stock endpoints. Kept apart from domain and API types so the wire format is explicit. */
final class StockMessages {

    private StockMessages() {}

    record StockLevelDto(String sku, int onHand, int reserved, int available) {

        static StockLevelDto from(StockLevel level) {
            return new StockLevelDto(level.sku().value(), level.onHand(), level.reserved(), level.available());
        }
    }

    record StockLevelsDto(String storeId, List<StockLevelDto> items) {}

    /**
     * Components are nullable because a client can leave them out; {@code @Valid} rejects that before the controller
     * sees the request.
     */
    record StockAdjustmentRequest(
            @NotNull @Nullable String sku,

            @NotNull @NonZero @Min(-AdjustStock.MAX_DELTA) @Max(AdjustStock.MAX_DELTA) @Nullable
            Integer delta,

            @NotNull @Nullable AdjustmentReason reason) {

        AdjustStock toCommand(StoreId storeId) {
            return new AdjustStock(
                    storeId,
                    Identifiers.sku(Objects.requireNonNull(sku, "sku")),
                    Objects.requireNonNull(delta, "delta"),
                    Objects.requireNonNull(reason, "reason"));
        }
    }
}
