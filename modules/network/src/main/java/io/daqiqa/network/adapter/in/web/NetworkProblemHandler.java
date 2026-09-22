package io.daqiqa.network.adapter.in.web;

import io.daqiqa.network.application.StoreNotFoundException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import tools.jackson.core.JacksonException;

/**
 * Maps network failures to RFC 9457 problem details with a stable, machine-readable {@code code}.
 *
 * <p>Highest precedence, so that for the store endpoints it wins over Spring Boot's generic problem-details handler
 * and validation failures carry this API's codes too.
 */
@RestControllerAdvice(basePackageClasses = StoreController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class NetworkProblemHandler {

    @ExceptionHandler
    ProblemDetail storeNotFound(StoreNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "STORE_NOT_FOUND", "Store not found", e.getMessage());
    }

    /**
     * A constraint on a path variable or on the request body was violated. Store endpoints always have a constrained
     * path variable, so Spring validates the whole method at once and reports body errors here too.
     */
    @ExceptionHandler
    ProblemDetail invalidInput(HandlerMethodValidationException e) {
        List<Map<String, String>> errors = e.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> Map.of(
                                "field", fieldOf(error, result),
                                "message", Objects.requireNonNullElse(error.getDefaultMessage(), "is invalid"))))
                .toList();
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed", "The request has invalid fields");
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler
    ProblemDetail unreadableBody(HttpMessageNotReadableException e) {
        String detail =
                e.getCause() instanceof JacksonException json && !json.getPath().isEmpty()
                        ? "Invalid value for '" + jsonPath(json.getPath()) + "'"
                        : "The request body is not valid JSON";
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid request", detail);
    }

    /** The body field (for {@code @Valid} objects) or the parameter (for constrained path variables) at fault. */
    private static String fieldOf(MessageSourceResolvable error, ParameterValidationResult result) {
        if (error instanceof FieldError field) {
            return field.getField();
        }
        return Objects.requireNonNullElse(result.getMethodParameter().getParameterName(), "request");
    }

    private static String jsonPath(List<JacksonException.Reference> path) {
        StringBuilder result = new StringBuilder();
        for (JacksonException.Reference reference : path) {
            if (reference.getPropertyName() != null) {
                result.append(result.isEmpty() ? "" : ".").append(reference.getPropertyName());
            } else if (reference.getIndex() >= 0) {
                result.append('[').append(reference.getIndex()).append(']');
            }
        }
        return result.toString();
    }

    private static ProblemDetail problem(HttpStatus status, String code, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:daqiqa:problem:" + code.toLowerCase().replace('_', '-')));
        problem.setTitle(title);
        problem.setProperty("code", code);
        return problem;
    }
}
