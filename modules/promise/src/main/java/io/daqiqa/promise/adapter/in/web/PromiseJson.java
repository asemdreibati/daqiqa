package io.daqiqa.promise.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.promise.api.DeliveryTier;
import io.daqiqa.promise.application.IssuedPromise;
import io.daqiqa.promise.application.PromiseResult;
import io.daqiqa.promise.domain.EtaBreakdown;
import io.daqiqa.promise.domain.Suggestion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * JSON shapes of {@code POST /v1/promises}. Kept separate from domain and API types so the wire format is explicit.
 *
 * <p>Request fields are nullable so that a missing field is a validation error rather than a silent default; the
 * controller only reads them after validation.
 */
final class PromiseJson {

    private PromiseJson() {}

    record PromiseRequest(
            @NotNull @Valid @Nullable LocationDto location,
            @NotNull @Size(min = 1, max = 50) @Nullable List<@NotNull @Valid CartLineDto> items) {

        GeoPoint geoPoint() {
            LocationDto point = Objects.requireNonNull(location);
            return GeoPoint.of(Objects.requireNonNull(point.latitude()), Objects.requireNonNull(point.longitude()));
        }

        List<SkuQuantity> lines() {
            return Objects.requireNonNull(items).stream()
                    .map(line ->
                            SkuQuantity.of(Objects.requireNonNull(line.sku()), Objects.requireNonNull(line.quantity())))
                    .toList();
        }
    }

    record LocationDto(
            @NotNull @DecimalMin("-90") @DecimalMax("90") @Nullable
            Double latitude,

            @NotNull @DecimalMin("-180") @DecimalMax("180") @Nullable
            Double longitude) {}

    record CartLineDto(
            @NotNull @Pattern(regexp = "^[A-Z0-9][A-Z0-9-]{2,39}$") @Nullable
            String sku,

            @NotNull @Min(1) @Max(99) @Nullable Integer quantity) {}

    /** Absent rather than null: {@code offer} only when AVAILABLE, {@code unavailableReason} only when UNAVAILABLE. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PromiseResponse(
            String status,
            @Nullable OfferDto offer,
            @Nullable String unavailableReason,
            List<SuggestionDto> suggestions) {

        static PromiseResponse from(PromiseResult result) {
            List<SuggestionDto> suggestions =
                    result.suggestions().stream().map(SuggestionDto::from).toList();
            return switch (result) {
                case PromiseResult.Available available ->
                    new PromiseResponse("AVAILABLE", OfferDto.from(available.offer()), null, suggestions);
                case PromiseResult.Unavailable unavailable ->
                    new PromiseResponse(
                            "UNAVAILABLE", null, unavailable.reason().name(), suggestions);
            };
        }
    }

    record OfferDto(
            UUID promiseId,
            String storeId,
            DeliveryTier tier,
            int etaMinutes,
            Instant expiresAt,
            String token,
            BreakdownDto breakdown) {

        static OfferDto from(IssuedPromise promise) {
            return new OfferDto(
                    promise.promiseId().value(),
                    promise.quote().storeId().value(),
                    promise.quote().tier(),
                    promise.quote().etaMinutes(),
                    promise.expiresAt(),
                    promise.token(),
                    BreakdownDto.from(promise.quote().breakdown()));
        }
    }

    record BreakdownDto(int queueSeconds, int pickSeconds, int riderWaitSeconds, int travelSeconds, int bufferSeconds) {

        static BreakdownDto from(EtaBreakdown breakdown) {
            return new BreakdownDto(
                    breakdown.queueSeconds(),
                    breakdown.pickSeconds(),
                    breakdown.riderWaitSeconds(),
                    breakdown.travelSeconds(),
                    breakdown.bufferSeconds());
        }
    }

    record SuggestionDto(String type, List<String> skus, String storeId, int etaMinutes, DeliveryTier tier) {

        static SuggestionDto from(Suggestion suggestion) {
            return new SuggestionDto(
                    "REMOVE_ITEMS",
                    suggestion.skus().stream().map(sku -> sku.value()).toList(),
                    suggestion.storeId().value(),
                    suggestion.etaMinutes(),
                    suggestion.tier());
        }
    }
}
