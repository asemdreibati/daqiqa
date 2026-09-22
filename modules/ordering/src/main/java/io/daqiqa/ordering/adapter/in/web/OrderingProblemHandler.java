package io.daqiqa.ordering.adapter.in.web;

import io.daqiqa.ordering.application.CheckoutInProgressException;
import io.daqiqa.ordering.application.IdempotencyKeyReusedException;
import io.daqiqa.ordering.application.OrderNotFoundException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
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

    @ExceptionHandler
    ProblemDetail missingHeader(MissingRequestHeaderException e) {
        if (OrderController.IDEMPOTENCY_KEY.equalsIgnoreCase(e.getHeaderName())) {
            return Problems.of(
                    HttpStatus.BAD_REQUEST,
                    "IDEMPOTENCY_KEY_MISSING",
                    "Idempotency key missing",
                    "Placing an order requires an Idempotency-Key header; a new UUID per order is ideal.");
        }
        return Problems.validationFailed("Header " + e.getHeaderName() + " is required");
    }

    @ExceptionHandler
    ProblemDetail invalidBody(MethodArgumentNotValidException e) {
        List<String> errors = new ArrayList<>();
        e.getBindingResult().getFieldErrors().forEach(error -> errors.add(describe(error)));
        e.getBindingResult().getGlobalErrors().forEach(error -> errors.add(message(error)));
        return validationFailed(errors);
    }

    @ExceptionHandler
    ProblemDetail invalidArguments(HandlerMethodValidationException e) {
        List<String> errors = new ArrayList<>();
        for (ParameterValidationResult result : e.getParameterValidationResults()) {
            if (result instanceof ParameterErrors body) {
                body.getFieldErrors().forEach(error -> errors.add(describe(error)));
            } else {
                String parameter = label(result.getMethodParameter());
                result.getResolvableErrors().forEach(error -> errors.add(parameter + ": " + message(error)));
            }
        }
        return validationFailed(errors);
    }

    @ExceptionHandler
    ProblemDetail unreadableBody(HttpMessageNotReadableException e) {
        // The parser's message names internal types; the client only needs to know the body is not what we accept.
        return Problems.validationFailed("The request body is not a valid JSON document of the expected shape");
    }

    @ExceptionHandler
    ProblemDetail malformedPathVariable(MethodArgumentTypeMismatchException e) {
        return Problems.validationFailed(e.getName() + " is malformed");
    }

    @ExceptionHandler
    ProblemDetail invalidRequest(InvalidRequestException e) {
        return Problems.validationFailed(Objects.requireNonNullElse(e.getMessage(), "Invalid request"));
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

    /** Whole seconds, rounded up and at least one: retrying sooner could only meet the same answer. */
    static long retryAfterSeconds(Duration retryAfter) {
        long seconds = retryAfter.toSeconds() + (retryAfter.toNanosPart() > 0 ? 1 : 0);
        return Math.max(1, seconds);
    }

    private static ProblemDetail validationFailed(List<String> errors) {
        // Sorted, so the same invalid request always gets the same answer.
        return Problems.validationFailed(
                errors.isEmpty()
                        ? "Invalid request"
                        : String.join("; ", errors.stream().sorted().toList()));
    }

    private static String describe(FieldError error) {
        return error.getField() + ": " + message(error);
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
