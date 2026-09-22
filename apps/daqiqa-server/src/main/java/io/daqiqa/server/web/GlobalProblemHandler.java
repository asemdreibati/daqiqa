package io.daqiqa.server.web;

import static java.util.Comparator.comparing;

import io.daqiqa.kernel.Ids;
import java.net.URI;
import java.sql.SQLException;
import java.sql.SQLTransientException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.validation.Errors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.util.DisconnectedClientHelper;
import org.springframework.web.util.WebUtils;

/**
 * The last word on every failure the modules' own problem handlers leave unanswered, in the house format: RFC 9457
 * with {@code type = urn:daqiqa:problem:<code-in-kebab-case>} and a stable {@code code}.
 *
 * <ul>
 *   <li>Spring MVC's own errors keep the contract's codes: {@code NOT_FOUND}, {@code METHOD_NOT_ALLOWED},
 *       {@code NOT_ACCEPTABLE}, {@code UNSUPPORTED_MEDIA_TYPE}, {@code INVALID_REQUEST} (a body or value we cannot
 *       read) and {@code VALIDATION_FAILED} (read, but invalid) with one {@code errors} entry per field.
 *   <li>A database that cannot answer <em>now</em> — a lock wait timed out, a deadlock or serialization failure, no
 *       connection within the pool's timeout — is {@code 503 TEMPORARILY_UNAVAILABLE} with {@code Retry-After: 1}.
 *       These say "again in a moment", not "wrong", and every mutating endpoint is safe to retry (ADR-0008).
 *   <li>Anything else is {@code 500 INTERNAL_ERROR}: logged in full under an incident id the client is given, with
 *       nothing of our internals in the response.
 * </ul>
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} replaces Spring Boot's default problem-details handler. Lowest
 * precedence: each module's handler is scoped to its own controllers and ordered first, so this one only ever answers
 * what they do not.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
class GlobalProblemHandler extends ResponseEntityExceptionHandler {

    static final String TEMPORARILY_UNAVAILABLE = "TEMPORARILY_UNAVAILABLE";
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    static final String INVALID_REQUEST = "INVALID_REQUEST";
    static final String VALIDATION_FAILED = "VALIDATION_FAILED";

    /** Long enough for a lock to be released or a connection to free up, short enough to feel like one slow request. */
    static final long RETRY_AFTER_SECONDS = 1;

    /**
     * PostgreSQL states that mean "not now": lock timeout (`lock_timeout`, see application.yaml), deadlock, and
     * serialization failure. Spring 7 leaves {@code 55P03} uncategorised, so the state itself decides.
     */
    private static final Set<String> TRANSIENT_SQL_STATES = Set.of("55P03", "40P01", "40001");

    /** A cause chain is a few links deep; the bound only stops a cyclic one. */
    private static final int MAX_CAUSE_DEPTH = 16;

    private static final Logger log = LoggerFactory.getLogger(GlobalProblemHandler.class);

    /** Failures no handler answered: the database cannot serve this request now, or something here is broken. */
    @ExceptionHandler(Exception.class)
    @Nullable
    ResponseEntity<Object> handleUnexpectedFailure(Exception failure, WebRequest request) {
        if (DisconnectedClientHelper.isClientDisconnectedException(failure)) {
            return null; // the client is gone: there is nobody to answer
        }
        return isTransient(failure) ? temporarilyUnavailable(failure, request) : internalError(failure, request);
    }

    /**
     * Whether the failure is the database saying "not now", wherever in the cause chain it says so: the layer that
     * noticed it wraps it, and a lock timeout arrives as an uncategorised SQL exception.
     */
    static boolean isTransient(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; cause = cause.getCause(), depth++) {
            boolean transientFailure = cause instanceof TransientDataAccessException
                    || cause instanceof CannotGetJdbcConnectionException
                    || cause instanceof SQLTransientException
                    || (cause instanceof SQLException sql && TRANSIENT_SQL_STATES.contains(sql.getSQLState()));
            if (transientFailure) {
                return true;
            }
        }
        return false;
    }

    /** 503 and {@code Retry-After}: nothing is wrong with the request, and retrying it is safe. */
    private @Nullable ResponseEntity<Object> temporarilyUnavailable(Exception failure, WebRequest request) {
        log.warn("{} cannot be served now ({}); answering 503", describe(request), summarise(failure));
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(RETRY_AFTER_SECONDS));
        ProblemDetail problem = problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                TEMPORARILY_UNAVAILABLE,
                "The service could not answer this request in time. Try again in a moment.");
        return super.handleExceptionInternal(failure, problem, headers, HttpStatus.SERVICE_UNAVAILABLE, request);
    }

    /** 500 with an incident id and nothing else: the failure is ours, and its message is not the client's business. */
    private @Nullable ResponseEntity<Object> internalError(Exception failure, WebRequest request) {
        String incidentId = Ids.newTimeOrderedId().toString();
        log.error("{} failed with an unhandled exception (incident {})", describe(request), incidentId, failure);
        // As Spring does for unhandled failures: the observation of this request records the exception.
        request.setAttribute(WebUtils.ERROR_EXCEPTION_ATTRIBUTE, failure, WebRequest.SCOPE_REQUEST);
        ProblemDetail problem = problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                INTERNAL_ERROR,
                "Something went wrong on our side. Quote incident " + incidentId + " if you report it.");
        problem.setProperty("incidentId", incidentId);
        return super.handleExceptionInternal(
                failure, problem, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    /**
     * Gives Spring MVC's own problems the house format. Handlers below build their problem themselves and go straight
     * to {@code super}, so nothing is branded twice.
     */
    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception failure, @Nullable Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) {
            // Spring's 5xx problems describe a handler of ours that is wrong; the client learns nothing from that.
            return status.value() == HttpStatus.SERVICE_UNAVAILABLE.value()
                    ? temporarilyUnavailable(failure, request)
                    : internalError(failure, request);
        }
        ResponseEntity<Object> response = super.handleExceptionInternal(failure, body, headers, status, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            brand(problem, codeOf(failure, status));
        }
        return response;
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleNoResourceFoundException(
            NoResourceFoundException failure, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem =
                problem(HttpStatus.NOT_FOUND, HttpStatus.NOT_FOUND.name(), "There is no resource at " + path(request));
        return super.handleExceptionInternal(failure, problem, headers, status, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException failure, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // The parser's message names internal types; the client only needs to know the body is not what we accept.
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                INVALID_REQUEST,
                "The request body is missing or is not valid JSON of the expected shape");
        return super.handleExceptionInternal(failure, problem, headers, status, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException failure, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String name = failure instanceof MethodArgumentTypeMismatchException mismatch
                ? mismatch.getName()
                : Objects.requireNonNullElse(failure.getPropertyName(), "value");
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, INVALID_REQUEST, "Invalid value for '" + name + "'");
        return super.handleExceptionInternal(failure, problem, headers, status, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException failure, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return validationFailed(failure, invalidFields(failure.getBindingResult()), headers, status, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException failure, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<InvalidField> errors = new ArrayList<>();
        failure.getParameterValidationResults().forEach(result -> {
            if (result instanceof Errors body) {
                errors.addAll(invalidFields(body));
            } else {
                String name = nameOf(result.getMethodParameter());
                result.getResolvableErrors().forEach(error -> errors.add(new InvalidField(name, messageOf(error))));
            }
        });
        return validationFailed(failure, errors, headers, status, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException failure,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        List<InvalidField> errors = List.of(new InvalidField(failure.getParameterName(), "is required"));
        return validationFailed(failure, errors, headers, status, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleServletRequestBindingException(
            ServletRequestBindingException failure, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (failure instanceof MissingRequestHeaderException missing) {
            List<InvalidField> errors = List.of(new InvalidField(missing.getHeaderName(), "is required"));
            return validationFailed(failure, errors, headers, status, request);
        }
        return super.handleServletRequestBindingException(failure, headers, status, request);
    }

    /** One problem for every invalid field, sorted, so the same request always gets the same answer. */
    private @Nullable ResponseEntity<Object> validationFailed(
            Exception failure,
            List<InvalidField> errors,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, VALIDATION_FAILED, "The request has invalid fields");
        problem.setProperty(
                "errors",
                errors.stream()
                        .sorted(comparing(InvalidField::field).thenComparing(InvalidField::message))
                        .toList());
        return super.handleExceptionInternal(failure, problem, headers, status, request);
    }

    private static List<InvalidField> invalidFields(Errors errors) {
        return Stream.concat(
                        errors.getFieldErrors().stream()
                                .map(error -> new InvalidField(error.getField(), messageOf(error))),
                        errors.getGlobalErrors().stream()
                                .map(error -> new InvalidField(error.getObjectName(), messageOf(error))))
                .toList();
    }

    /** The code the contract gives this failure; for anything else, the one it gives the status. */
    private static String codeOf(Exception failure, HttpStatusCode status) {
        return switch (failure) {
            case MethodArgumentNotValidException ignored -> VALIDATION_FAILED;
            case HandlerMethodValidationException ignored -> VALIDATION_FAILED;
            case MissingRequestValueException ignored -> VALIDATION_FAILED;
            case TypeMismatchException ignored -> INVALID_REQUEST;
            case HttpMessageNotReadableException ignored -> INVALID_REQUEST;
            default -> codeOf(status);
        };
    }

    private static String codeOf(HttpStatusCode status) {
        if (status.value() == HttpStatus.SERVICE_UNAVAILABLE.value()) {
            return TEMPORARILY_UNAVAILABLE;
        }
        if (status.is5xxServerError()) {
            return INTERNAL_ERROR;
        }
        HttpStatus known = HttpStatus.resolve(status.value());
        return known == null || known == HttpStatus.BAD_REQUEST ? INVALID_REQUEST : known.name();
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        brand(problem, code);
        return problem;
    }

    private static void brand(ProblemDetail problem, String code) {
        problem.setType(
                URI.create("urn:daqiqa:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setTitle(title(code));
        problem.setProperty("code", code);
    }

    /** {@code METHOD_NOT_ALLOWED} reads "Method not allowed", like every other title in this API. */
    private static String title(String code) {
        String words = code.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /** What the client called the argument: its header, parameter or path variable name, else the Java name. */
    private static String nameOf(MethodParameter parameter) {
        RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
        if (header != null && !header.value().isEmpty()) {
            return header.value();
        }
        RequestParam requestParameter = parameter.getParameterAnnotation(RequestParam.class);
        if (requestParameter != null && !requestParameter.value().isEmpty()) {
            return requestParameter.value();
        }
        PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
        if (pathVariable != null && !pathVariable.value().isEmpty()) {
            return pathVariable.value();
        }
        return Objects.requireNonNullElse(parameter.getParameterName(), "request");
    }

    private static String messageOf(MessageSourceResolvable error) {
        return Objects.requireNonNullElse(error.getDefaultMessage(), "is invalid");
    }

    private static String describe(WebRequest request) {
        return request instanceof ServletWebRequest servlet
                ? servlet.getHttpMethod() + " " + path(request)
                : request.getDescription(false);
    }

    /** The path, never the query string: query values are the client's data. */
    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest servlet
                ? servlet.getRequest().getRequestURI()
                : request.getDescription(false);
    }

    /** One line naming the failure that matters: the innermost cause, which is the one that says what happened. */
    private static String summarise(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause.getCause() != null && depth < MAX_CAUSE_DEPTH; cause = cause.getCause(), depth++) {
            // walk to the root cause
        }
        String message = Objects.requireNonNullElse(cause.getMessage(), "");
        return cause.getClass().getSimpleName() + ": "
                + message.lines().findFirst().orElse("");
    }

    /** One entry of a {@code VALIDATION_FAILED} problem's {@code errors}, as the contract defines it. */
    record InvalidField(String field, String message) {}
}
