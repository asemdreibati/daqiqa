package io.daqiqa.ordering.adapter.in.web;

import io.daqiqa.kernel.CustomerId;
import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.ordering.application.PlaceOrderCommand;
import io.daqiqa.ordering.domain.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The {@code PlaceOrderRequest} schema of the API contract. Components are nullable because a client may omit them;
 * Bean Validation turns that into a 400 before {@link #toCommand()} runs.
 */
record PlaceOrderRequest(
        @NotBlank @Size(max = 4096) @Nullable String promiseToken,
        @NotNull @Nullable UUID customerId,

        @NotNull @Size(min = 1, max = PlaceOrderCommand.MAX_LINES) @Nullable
        List<@NotNull @Valid CartLine> items,

        @NotNull @Valid @Nullable Location deliveryLocation,
        @NotNull @Nullable PaymentMethod paymentMethod) {

    record CartLine(
            @NotNull @Pattern(regexp = "^[A-Z0-9][A-Z0-9-]{2,39}$") @Nullable
            String sku,

            @NotNull @Min(1) @Max(SkuQuantity.MAX_QUANTITY) @Nullable
            Integer quantity) {}

    record Location(
            @NotNull @DecimalMin("-90") @DecimalMax("90") @Nullable
            Double latitude,

            @NotNull @DecimalMin("-180") @DecimalMax("180") @Nullable
            Double longitude) {}

    /**
     * Builds the command. The value objects check what Bean Validation cannot, such as a SKU split over several lines
     * whose quantities add up to more than one line may hold.
     *
     * @throws InvalidRequestException if a value object refuses its input
     */
    PlaceOrderCommand toCommand() {
        try {
            Location location = present(deliveryLocation);
            List<SkuQuantity> lines = present(items).stream()
                    .map(line -> SkuQuantity.of(present(line.sku()), present(line.quantity())))
                    .toList();
            return new PlaceOrderCommand(
                    CustomerId.of(present(customerId)),
                    present(promiseToken),
                    lines,
                    GeoPoint.of(present(location.latitude()), present(location.longitude())),
                    present(paymentMethod));
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(e.getMessage());
        }
    }

    /** Bean Validation has already rejected absent values; this only tells the compiler so. */
    private static <T> T present(@Nullable T value) {
        return Objects.requireNonNull(value, "validated as present");
    }
}
