package io.daqiqa.promise.adapter.in.web;

import io.daqiqa.promise.domain.InvalidBasketException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.core.JacksonException;

/**
 * Maps promise request failures to RFC 9457 problem details with a stable, machine-readable {@code code}.
 *
 * <p>Highest precedence, so that for the promise endpoint it wins over the application-wide handler and validation
 * failures carry this API's codes too.
 */
@RestControllerAdvice(basePackageClasses = PromiseController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class PromiseProblemHandler {

    @ExceptionHandler
    ProblemDetail invalidFields(MethodArgumentNotValidException e) {
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", Objects.requireNonNullElse(error.getDefaultMessage(), "is invalid")))
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

    /** Rules the JSON schema cannot express, such as the per-SKU limit once duplicate lines are merged. */
    @ExceptionHandler
    ProblemDetail invalidBasket(InvalidBasketException e) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid request", e.getMessage());
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
        problem.setType(
                URI.create("urn:daqiqa:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setTitle(title);
        problem.setProperty("code", code);
        return problem;
    }
}
