package io.daqiqa.ordering.adapter.in.web;

import io.daqiqa.ordering.adapter.in.web.Problems.InvalidField;
import io.daqiqa.ordering.application.CheckoutInProgressException;
import io.daqiqa.ordering.application.IdempotencyKeyReusedException;
import io.daqiqa.ordering.application.OrderNotFoundException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.Errors;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Maps ordering failures, and the framework's validation failures on these endpoints, to RFC 9457 problems with the
 * contract's codes. Checkout's business outcomes do not come through here: they are stored responses (see
 * {@link JsonCheckoutResponseRenderer}).
 *
 * <p>Scoped to the ordering controllers and ordered first, so it answers for them before the application-wide
 * problem handler, without replacing that handler for the other modules.
 */
@RestControllerAdvice(basePackageClasses = OrderController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class OrderingProblemHandler {

    /** The longest a client is asked to wait for a request with the same key that is still running. */
    static final long MAX_RETRY_AFTER_SECONDS = 2;

    @ExceptionHandler
    ProblemDetail missingHeader(MissingRequestHeaderException e) {
        if (OrderController.IDEMPOTENCY_KEY.equalsIgnoreCase(e.getHeaderName())) {
            return Problems.of(
                    HttpStatus.BAD_REQUEST,
                    "IDEMPOTENCY_KEY_MISSING",
                    "Idempotency key missing",
                    "Placing an order requires an Idempotency-Key header; a new UUID per order is ideal.");
        }
        return Problems.validationFailed(List.of(new InvalidField(e.getHeaderName(), "is required")));
    }

    @ExceptionHandler
    ProblemDetail invalidBody(MethodArgumentNotValidException e) {
        return Problems.validationFailed(invalidFields(e.getBindingResult()));
    }

    @ExceptionHandler
    ProblemDetail invalidArguments(HandlerMethodValidationException e) {
        List<InvalidField> errors = new ArrayList<>();
        for (ParameterValidationResult result : e.getParameterValidationResults()) {
            if (result instanceof ParameterErrors body) {
                errors.addAll(invalidFields(body));
            } else {
                String parameter = label(result.getMethodParameter());
                result.getResolvableErrors().forEach(error -> errors.add(new InvalidField(parameter, message(error))));
            }
        }
        return Problems.validationFailed(errors);
    }

    /** Unreadable JSON, a wrong type or an unknown enum value. The parser's message would leak internals. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadableBody() {
        return Problems.invalidRequest("The request body is missing or is not valid JSON of the expected shape");
    }

    @ExceptionHandler
    ProblemDetail malformedPathVariable(MethodArgumentTypeMismatchException e) {
        return Problems.invalidRequest("Invalid value for '" + e.getName() + "'");
    }

    @ExceptionHandler
    ProblemDetail invalidRequest(InvalidRequestException e) {
        return Problems.invalidRequest(Objects.requireNonNullElse(e.getMessage(), "Invalid request"));
    }

    @ExceptionHandler
    ProblemDetail keyReused(IdempotencyKeyReusedException e) {
        return Problems.of(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "IDEMPOTENCY_KEY_REUSED",
                "Idempotency key reused",
                Objects.requireNonNullElse(e.getMessage(), "The key was used for a different request"));
    }

    @ExceptionHandler
    ResponseEntity<ProblemDetail> inProgress(CheckoutInProgressException e) {
        ProblemDetail problem = Problems.of(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_REQUEST_IN_PROGRESS",
                "Request in progress",
                Objects.requireNonNullElse(e.getMessage(), "A request with this key is still being processed"));
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds(e.retryAfter())))
                .body(problem);
    }

    @ExceptionHandler
    ProblemDetail notFound(OrderNotFoundException e) {
        return Problems.of(
                HttpStatus.NOT_FOUND,
                "ORDER_NOT_FOUND",
                "Order not found",
                Objects.requireNonNullElse(e.getMessage(), "No such order"));
    }

    /**
     * The first request usually finishes within a second or two, so the client is asked back soon rather than at the
     * end of the lease, which is only the worst case (the instance processing it died) and may be a minute away; never
     * later than the lease itself, after which a retry takes the claim over. Whole seconds, rounded up, at least one.
     */
    static long retryAfterSeconds(Duration remainingLease) {
        long leaseSeconds = remainingLease.toSeconds() + (remainingLease.toNanosPart() > 0 ? 1 : 0);
        return Math.max(1, Math.min(MAX_RETRY_AFTER_SECONDS, leaseSeconds));
    }

    /** The body's invalid fields; an error on the object itself is reported under the object's name. */
    private static List<InvalidField> invalidFields(Errors errors) {
        return Stream.concat(
                        errors.getFieldErrors().stream()
                                .map(error -> new InvalidField(error.getField(), message(error))),
                        errors.getGlobalErrors().stream()
                                .map(error -> new InvalidField(error.getObjectName(), message(error))))
                .toList();
    }

    private static String message(MessageSourceResolvable error) {
        return Objects.requireNonNullElse(error.getDefaultMessage(), "is invalid");
    }

    /** Headers are reported by their HTTP name, which is what the client sent. */
    private static String label(MethodParameter parameter) {
        RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
        if (header != null && !header.value().isEmpty()) {
            return header.value();
        }
        return Objects.requireNonNullElse(parameter.getParameterName(), "argument");
    }
}
