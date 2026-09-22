package io.daqiqa.ordering.application;

import io.daqiqa.kernel.CustomerId;
import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.ordering.domain.PaymentMethod;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A request to turn a promise into an order.
 *
 * @param promiseToken the signed token returned with the promise
 * @param lines the basket; duplicate SKUs are merged, keeping the order in which they first appear
 */
public record PlaceOrderCommand(
        CustomerId customerId,
        String promiseToken,
        List<SkuQuantity> lines,
        GeoPoint deliveryLocation,
        PaymentMethod paymentMethod) {

    /** Quick-commerce baskets are small; the contract allows at most 50 lines. */
    public static final int MAX_LINES = 50;

    public PlaceOrderCommand {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(promiseToken, "promiseToken");
        Objects.requireNonNull(deliveryLocation, "deliveryLocation");
        Objects.requireNonNull(paymentMethod, "paymentMethod");
        if (promiseToken.isBlank()) {
            throw new IllegalArgumentException("A promise token is required");
        }
        lines = SkuQuantity.merge(lines);
        if (lines.isEmpty() || lines.size() > MAX_LINES) {
            throw new IllegalArgumentException("A basket has 1 to " + MAX_LINES + " lines, had " + lines.size());
        }
    }

    /**
     * The fingerprint of this request's canonical form: the basket merged and sorted by SKU, numbers in Java's
     * canonical notation. Retries that list the same basket in another order, or split a line in two, are the same
     * request; any change of customer, token, basket, address or payment method is a different one.
     */
    public RequestFingerprint fingerprint() {
        String basket = lines.stream()
                .sorted(Comparator.comparing(line -> line.sku().value()))
                .map(line -> line.sku().value() + ":" + line.quantity())
                .collect(Collectors.joining(","));
        return RequestFingerprint.of(String.join(
                "\n",
                "place-order/v1",
                "customer=" + customerId,
                "promise=" + promiseToken,
                "items=" + basket,
                "deliverTo=" + deliveryLocation.latitude() + "," + deliveryLocation.longitude(),
                "payment=" + paymentMethod));
    }
}
