package io.daqiqa.server.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.assertj.core.api.AssertProvider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.test.json.AbstractJsonContentAssert;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.assertj.MvcTestResultAssert;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a client receives when a request fails outside any module's own problem handler: the house format for Spring
 * MVC's errors, 503 for a database that cannot answer now, and 500 that says nothing about us.
 */
@WebMvcTest(
        controllers = GlobalProblemHandlerTest.FailingController.class,
        properties = "spring.mvc.problemdetails.enabled=true")
@Import(GlobalProblemHandlerTest.FailingController.class)
@ExtendWith(OutputCaptureExtension.class)
class GlobalProblemHandlerTest {

    @Autowired
    MockMvcTester mvc;

    @ParameterizedTest(name = "{0}")
    @MethodSource("failuresTheDatabaseCallsTemporary")
    void aDatabaseThatCannotAnswerNowIsATemporaryUnavailability(String name, RuntimeException failure) {
        MvcTestResult result = failWith(failure);

        assertProblem(result, HttpStatus.SERVICE_UNAVAILABLE, "TEMPORARILY_UNAVAILABLE")
                .extractingPath("$.detail")
                .isEqualTo("The service could not answer this request in time. Try again in a moment.");
        assertThat(result)
                .as("a client may retry at once; every mutating endpoint is idempotent")
                .hasHeader(HttpHeaders.RETRY_AFTER, "1");
    }

    static Stream<Arguments> failuresTheDatabaseCallsTemporary() {
        return Stream.of(
                Arguments.of(
                        "lock timeout, which Spring 7 leaves uncategorised",
                        new UncategorizedSQLException(
                                "StatementCallback",
                                "UPDATE inventory.stock_level SET reserved = reserved + ?",
                                sqlException("55P03", "ERROR: canceling statement due to lock timeout"))),
                Arguments.of(
                        "deadlock",
                        new UncategorizedSQLException(
                                "StatementCallback",
                                "UPDATE ordering.order SET status = ?",
                                sqlException("40P01", "ERROR: deadlock detected"))),
                Arguments.of(
                        "serialization failure",
                        new UncategorizedSQLException(
                                "StatementCallback",
                                "UPDATE inventory.stock_level SET on_hand = ?",
                                sqlException("40001", "ERROR: could not serialize access due to concurrent update"))),
                Arguments.of(
                        "no connection in the pool",
                        new CannotCreateTransactionException(
                                "Could not open JDBC Connection for transaction",
                                new SQLTransientConnectionException(
                                        "HikariPool-1 - Connection is not available, request timed out after 2000ms"))),
                Arguments.of(
                        "no connection at all",
                        new CannotGetJdbcConnectionException(
                                "Failed to obtain JDBC Connection", sqlException("08006", "connection refused"))),
                Arguments.of(
                        "a failure Spring itself calls transient",
                        new PessimisticLockingFailureException("Could not acquire the row lock")));
    }

    @Test
    void anUnexpectedFailureIsAnInternalErrorThatTellsTheClientNothingAboutUs(CapturedOutput output) {
        MvcTestResult result = failWith(new IllegalStateException("stock row corrupt: reserved -1 for MILK-FULL-1L"));

        AbstractJsonContentAssert<?> problem =
                assertProblem(result, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
        String answer = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        String incidentId = JsonPath.read(answer, "$.incidentId");
        problem.extractingPath("$.detail")
                .isEqualTo("Something went wrong on our side. Quote incident " + incidentId + " if you report it.");
        assertThat(answer)
                .as("no internals: neither the message nor the exception's name")
                .doesNotContain("stock row corrupt")
                .doesNotContain("IllegalStateException");
        assertThat(output)
                .as("the log has the whole failure under the id the client was given")
                .contains("GET /test/failure failed with an unhandled exception (incident " + incidentId + ")")
                .contains("java.lang.IllegalStateException: stock row corrupt: reserved -1 for MILK-FULL-1L");
    }

    @Test
    void aDatabaseFailureThatIsNotTemporaryIsAnInternalErrorLikeAnyOther() {
        MvcTestResult result = failWith(new DataIntegrityViolationException("duplicate key value violates ..."));

        assertProblem(result, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
        assertThat(result).doesNotContainHeader(HttpHeaders.RETRY_AFTER);
    }

    @Test
    void anUnknownPathIsANotFoundProblem() {
        assertProblem(mvc.get().uri("/v1/nothing-here").exchange(), HttpStatus.NOT_FOUND, "NOT_FOUND")
                .extractingPath("$.detail")
                .isEqualTo("There is no resource at /v1/nothing-here");
    }

    @Test
    void aMethodTheEndpointDoesNotSupportIsAProblemWithTheAllowedMethods() {
        MvcTestResult result = mvc.delete().uri("/test/echo").exchange();

        assertProblem(result, HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED");
        assertThat(result.getResponse().getHeader(HttpHeaders.ALLOW)).contains("POST");
    }

    @Test
    void aBodyWeCannotAcceptOrProduceIsAProblemToo() {
        assertProblem(
                mvc.post()
                        .uri("/test/echo")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("sku=MILK-FULL-1L")
                        .exchange(),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_MEDIA_TYPE");
        assertProblem(
                mvc.get()
                        .uri("/test/items/{id}", UUID.randomUUID())
                        .accept(MediaType.IMAGE_PNG)
                        .exchange(),
                HttpStatus.NOT_ACCEPTABLE,
                "NOT_ACCEPTABLE");
    }

    @Test
    void anUnreadableBodyOrAnUnconvertibleValueIsAnInvalidRequest() {
        assertProblem(post("{\"sku\": "), HttpStatus.BAD_REQUEST, "INVALID_REQUEST")
                .extractingPath("$.detail")
                .isEqualTo("The request body is missing or is not valid JSON of the expected shape");
        assertProblem(mvc.get().uri("/test/items/not-a-uuid").exchange(), HttpStatus.BAD_REQUEST, "INVALID_REQUEST")
                .extractingPath("$.detail")
                .isEqualTo("Invalid value for 'id'");
    }

    @Test
    void everyInvalidFieldIsReportedWithItsName() {
        MvcTestResult result = post("{\"sku\": \" \", \"quantity\": 0}");

        assertProblem(result, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED")
                .extractingPath("$.errors")
                .asArray()
                .as("one entry per field, sorted, so the same request always gets the same answer")
                .containsExactly(
                        Map.of("field", "quantity", "message", "must be greater than or equal to 1"),
                        Map.of("field", "sku", "message", "must not be blank"));
    }

    @Test
    void aMissingParameterIsAValidationFailureNamingIt() {
        assertProblem(mvc.get().uri("/test/search").exchange(), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED")
                .extractingPath("$.errors")
                .asArray()
                .containsExactly(Map.of("field", "sku", "message", "is required"));
    }

    private MvcTestResult failWith(RuntimeException failure) {
        FailingController.next = () -> failure;
        return mvc.get().uri("/test/failure").exchange();
    }

    private MvcTestResult post(String body) {
        return mvc.post()
                .uri("/test/echo")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private static SQLException sqlException(String sqlState, String message) {
        return new SQLException(message, sqlState);
    }

    /** An RFC 9457 problem with the contract's stable code, and a type URN and title derived from it. */
    private static AbstractJsonContentAssert<?> assertProblem(
            AssertProvider<MvcTestResultAssert> response, HttpStatus status, String code) {
        AbstractJsonContentAssert<?> body = assertThat(response)
                .hasStatus(status)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson();
        body.extractingPath("$.code").isEqualTo(code);
        body.extractingPath("$.status").isEqualTo(status.value());
        body.extractingPath("$.type")
                .isEqualTo("urn:daqiqa:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-'));
        return body;
    }

    /** Endpoints that fail the way real ones can, without a database to fail against. */
    @RestController
    static class FailingController {

        /** What the next request to {@code /test/failure} fails with. */
        static Supplier<RuntimeException> next = () -> new IllegalStateException("no failure was set");

        @GetMapping("/test/failure")
        String failure() {
            throw next.get();
        }

        @PostMapping("/test/echo")
        Map<String, Object> echo(@Valid @RequestBody Line line) {
            return Map.of("sku", String.valueOf(line.sku()), "quantity", String.valueOf(line.quantity()));
        }

        /** Answers JSON and nothing else, so that an impossible {@code Accept} is a 406. */
        @GetMapping("/test/items/{id}")
        Map<String, Object> item(@PathVariable UUID id) {
            return Map.of("id", id.toString());
        }

        @GetMapping("/test/search")
        List<String> search(@RequestParam String sku) {
            return List.of(sku);
        }

        record Line(@NotBlank @Nullable String sku, @Min(1) int quantity) {}
    }
}
