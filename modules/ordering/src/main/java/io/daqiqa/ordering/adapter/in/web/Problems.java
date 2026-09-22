package io.daqiqa.ordering.adapter.in.web;

import io.daqiqa.inventory.api.Shortage;
import io.daqiqa.kernel.Sku;
import io.daqiqa.ordering.application.CheckoutRejection;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * RFC 9457 problems of the ordering endpoints, with the stable {@code code} and the
 * {@code urn:daqiqa:problem:<code-in-kebab-case>} type of the API contract.
 */
final class Problems {

    private Problems() {}

    static ProblemDetail of(HttpStatus status, String code, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(
                URI.create("urn:daqiqa:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setTitle(title);
        problem.setProperty("code", code);
        return problem;
    }

    static ProblemDetail validationFailed(String detail) {
        return of(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed", detail);
    }

    /** The problem a checkout ends with when it ends without an order. */
    static ProblemDetail rejected(CheckoutRejection rejection) {
        return switch (rejection) {
            case CheckoutRejection.PromiseRejected promise ->
                switch (promise.reason()) {
                    case EXPIRED ->
                        of(
                                HttpStatus.UNPROCESSABLE_CONTENT,
                                "PROMISE_EXPIRED",
                                "Promise expired",
                                "The delivery promise has expired. Ask for a new promise and order again.");
                    case CART_MISMATCH ->
                        of(
                                HttpStatus.UNPROCESSABLE_CONTENT,
                                "PROMISE_CART_MISMATCH",
                                "Promise is for another basket",
                                "The basket differs from the one the promise was made for. Ask for a new promise.");
                    case MALFORMED, TAMPERED ->
                        of(
                                HttpStatus.UNPROCESSABLE_CONTENT,
                                "PROMISE_INVALID",
                                "Invalid promise",
                                "The promise token is not one we issued, or it was altered.");
                };
            case CheckoutRejection.UnknownSkus unknown ->
                of(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "UNKNOWN_SKU",
                        "Unknown SKU",
                        "Not in the catalog: "
                                + unknown.skus().stream().map(Sku::value).collect(Collectors.joining(", ")));
            case CheckoutRejection.OutOfStock outOfStock -> {
                ProblemDetail problem = of(
                        HttpStatus.CONFLICT,
                        "OUT_OF_STOCK",
                        "Out of stock",
                        "Some items sold out after the promise was made. Ask for a new promise.");
                problem.setProperty("shortages", shortages(outOfStock.shortages()));
                yield problem;
            }
            case CheckoutRejection.PaymentDeclined declined ->
                of(HttpStatus.PAYMENT_REQUIRED, "PAYMENT_DECLINED", "Payment declined", declined.reason());
            case CheckoutRejection.HoldExpired expired ->
                of(
                        HttpStatus.CONFLICT,
                        "HOLD_EXPIRED",
                        "Hold expired",
                        "The payment completed after the items' hold had expired, so they were released and nothing "
                                + "was ordered. Ask for a new promise and order again.");
        };
    }

    private static List<ShortageJson> shortages(List<Shortage> shortages) {
        return shortages.stream()
                .map(shortage -> new ShortageJson(shortage.sku().value(), shortage.requested(), shortage.available()))
                .toList();
    }

    /** The contract's shortage: what was asked for and what can still be sold. */
    record ShortageJson(String sku, int requested, int available) {}
}
