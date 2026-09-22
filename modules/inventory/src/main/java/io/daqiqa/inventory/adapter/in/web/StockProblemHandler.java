package io.daqiqa.inventory.adapter.in.web;

import io.daqiqa.inventory.application.StoreNotFoundException;
import io.daqiqa.inventory.application.UnknownSkuException;
import io.daqiqa.inventory.domain.StockBelowReservedException;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * Maps stock endpoint failures to RFC 9457 problem details with a stable, machine-readable {@code code}.
 *
 * <p>Ordered first so that, for these controllers, it wins over the application-wide problem-details handler (which
 * would answer validation failures without a {@code code}).
 */
@RestControllerAdvice(basePackageClasses = StockController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class StockProblemHandler {

    @ExceptionHandler
    ProblemDetail storeNotFound(StoreNotFoundException e) {
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "STORE_NOT_FOUND", "Store not found", e.getMessage());
        problem.setProperty("storeId", e.storeId().value());
        return problem;
    }

    @ExceptionHandler
    ProblemDetail unknownSku(UnknownSkuException e) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_CONTENT, "UNKNOWN_SKU", "Unknown SKU", e.getMessage());
        problem.setProperty("sku", e.sku().value());
        return problem;
    }

    /** Carries the numbers, so a client can see how far the change can go without parsing the detail. */
    @ExceptionHandler
    ProblemDetail stockBelowReserved(StockBelowReservedException e) {
        ProblemDetail problem =
                problem(HttpStatus.CONFLICT, "STOCK_BELOW_RESERVED", "Stock below reserved", e.getMessage());
        problem.setProperty("sku", e.level().sku().value());
        problem.setProperty("onHand", e.level().onHand());
        problem.setProperty("reserved", e.level().reserved());
        problem.setProperty("delta", e.delta());
        return problem;
    }

    @ExceptionHandler
    ProblemDetail invalidBody(MethodArgumentNotValidException e) {
        return validationFailed(e.getBindingResult().getFieldErrors().stream()
                .map(error -> new InvalidField(error.getField(), messageOf(error)))
                .toList());
    }

    @ExceptionHandler
    ProblemDetail invalidParameters(HandlerMethodValidationException e) {
        return validationFailed(e.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new InvalidField(fieldOf(error, result), messageOf(error))))
                .toList());
    }

    @ExceptionHandler
    ProblemDetail missingParameter(MissingServletRequestParameterException e) {
        return validationFailed(List.of(new InvalidField(e.getParameterName(), "is required")));
    }

    /** Unreadable JSON, a wrong type or an unknown enum value. The parser's message would leak internals. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadableBody() {
        return problem(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST",
                "Invalid request",
                "The request body is missing or is not valid JSON of the expected shape");
    }

    /**
     * A malformed store id or SKU. Only identifiers parsed from the request land here; any other
     * IllegalArgumentException is a server-side failure and stays a 500 that does not echo its message.
     */
    @ExceptionHandler
    ProblemDetail malformedIdentifier(MalformedIdentifierException e) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid request", e.getMessage());
    }

    /** Every field the request got wrong, sorted, so the same invalid request always gets the same answer. */
    private static ProblemDetail validationFailed(List<InvalidField> errors) {
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed", "The request has invalid fields");
        problem.setProperty(
                "errors",
                errors.stream()
                        .sorted(Comparator.comparing(InvalidField::field).thenComparing(InvalidField::message))
                        .toList());
        return problem;
    }

    /** The body field (for {@code @Valid} objects) or the parameter (for constrained query parameters) at fault. */
    private static String fieldOf(MessageSourceResolvable error, ParameterValidationResult result) {
        if (error instanceof FieldError field) {
            return field.getField();
        }
        return Objects.requireNonNullElse(result.getMethodParameter().getParameterName(), "request");
    }

    private static String messageOf(MessageSourceResolvable error) {
        return Objects.requireNonNullElse(error.getDefaultMessage(), "is invalid");
    }

    /** One entry of a {@code VALIDATION_FAILED} problem's {@code errors}, as the contract defines it. */
    record InvalidField(String field, String message) {}

    private static ProblemDetail problem(HttpStatus status, String code, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(
                URI.create("urn:daqiqa:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setTitle(title);
        problem.setProperty("code", code);
        return problem;
    }
}
