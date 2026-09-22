package io.daqiqa.catalog.adapter.in.web;

import io.daqiqa.catalog.application.ProductNotFoundException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps catalog failures to RFC 9457 problem details with a stable, machine-readable {@code code}. */
@RestControllerAdvice(basePackageClasses = ProductController.class)
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
        problem.setType(URI.create("urn:daqiqa:problem:" + code.toLowerCase().replace('_', '-')));
        problem.setTitle(title);
        problem.setProperty("code", code);
        return problem;
    }
}
