package io.daqiqa.catalog.adapter.in.web;

import io.daqiqa.catalog.application.ProductNotFoundException;
import java.net.URI;
import java.util.Locale;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps catalog failures to RFC 9457 problem details with a stable, machine-readable {@code code}.
 *
 * <p>Ordered first, like every module's handler, so that it answers for these controllers before the
 * application-wide handler, which takes anything left as an internal error.
 */
@RestControllerAdvice(basePackageClasses = ProductController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class CatalogProblemHandler {

    @ExceptionHandler
    ProblemDetail productNotFound(ProductNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Product not found", e.getMessage());
    }

    /** Value objects reject malformed input (a bad SKU in the path, for example) with IllegalArgumentException. */
    @ExceptionHandler
    ProblemDetail invalidInput(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid request", e.getMessage());
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
