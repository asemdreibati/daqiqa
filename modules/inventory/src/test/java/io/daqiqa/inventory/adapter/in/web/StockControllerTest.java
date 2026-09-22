package io.daqiqa.inventory.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.daqiqa.inventory.application.AdjustStock;
import io.daqiqa.inventory.application.StockService;
import io.daqiqa.inventory.application.StoreNotFoundException;
import io.daqiqa.inventory.application.UnknownSkuException;
import io.daqiqa.inventory.domain.AdjustmentReason;
import io.daqiqa.inventory.domain.StockBelowReservedException;
import io.daqiqa.inventory.domain.StockLevel;
import io.daqiqa.inventory.domain.StoreStock;
import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.StoreId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.assertj.core.api.AssertProvider;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.AbstractJsonContentAssert;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.assertj.MvcTestResultAssert;

/** The wire contract of the stock endpoints. Problem details are enabled application-wide, as in the server. */
@WebMvcTest(controllers = StockController.class, properties = "spring.mvc.problemdetails.enabled=true")
class StockControllerTest {

    private static final StoreId MARINA = StoreId.of("DXB-MARINA");
    private static final Sku MILK = Sku.of("MILK-FULL-1L");
    private static final Sku BREAD = Sku.of("BREAD-ARABIC-6PK");

    @Autowired
    MockMvcTester mvc;

    @MockitoBean
    StockService stock;

    @Nested
    class GettingLevels {

        @Test
        void returnsEveryRequestedSkuInRequestOrderWithZerosForSkusNotStocked() {
            given(stock.levels(MARINA, List.of(MILK, BREAD)))
                    .willReturn(StoreStock.of(MARINA, List.of(new StockLevel(MARINA, MILK, 10, 4))));

            assertThat(mvc.get()
                            .uri("/v1/stores/DXB-MARINA/stock?sku=MILK-FULL-1L&sku=BREAD-ARABIC-6PK&sku=MILK-FULL-1L"))
                    .hasStatusOk()
                    .bodyJson()
                    .isStrictlyEqualTo("""
                            {"storeId": "DXB-MARINA",
                             "items": [
                               {"sku": "MILK-FULL-1L", "onHand": 10, "reserved": 4, "available": 6},
                               {"sku": "BREAD-ARABIC-6PK", "onHand": 0, "reserved": 0, "available": 0}]}""");
        }

        @Test
        void anUnknownStoreIsNotFound() {
            given(stock.levels(eq(MARINA), anyCollection())).willThrow(new StoreNotFoundException(MARINA));

            assertProblem(
                    mvc.get().uri("/v1/stores/DXB-MARINA/stock?sku=MILK-FULL-1L"),
                    HttpStatus.NOT_FOUND,
                    "STORE_NOT_FOUND");
        }

        @Test
        void needsAtLeastOneSku() {
            assertProblem(mvc.get().uri("/v1/stores/DXB-MARINA/stock"), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED")
                    .extractingPath("$.errors")
                    .asArray()
                    .containsExactly(Map.of("field", "sku", "message", "is required"));
        }

        @Test
        void acceptsAtMostOneHundredSkus() {
            String skus =
                    IntStream.rangeClosed(1, 101).mapToObj(i -> "sku=SKU-" + i).collect(Collectors.joining("&"));

            assertProblem(
                            mvc.get().uri("/v1/stores/DXB-MARINA/stock?" + skus),
                            HttpStatus.BAD_REQUEST,
                            "VALIDATION_FAILED")
                    .extractingPath("$.errors[0].field")
                    .isEqualTo("sku");
            verifyNoInteractions(stock);
        }

        @Test
        void aMalformedSkuOrStoreIdIsAnInvalidRequest() {
            assertProblem(
                    mvc.get().uri("/v1/stores/DXB-MARINA/stock?sku=not a sku"),
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST");
            assertProblem(
                    mvc.get().uri("/v1/stores/marina/stock?sku=MILK-FULL-1L"),
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST");
            verifyNoInteractions(stock);
        }
    }

    @Nested
    class AdjustingStock {

        private static final String RECEIVED_FIVE = """
                {"sku": "MILK-FULL-1L", "delta": 5, "reason": "RECEIVED"}""";
        private static final String DAMAGED_SEVEN = """
                {"sku": "MILK-FULL-1L", "delta": -7, "reason": "DAMAGED"}""";

        @Test
        void appliesTheAdjustmentAndReturnsTheNewLevel() {
            given(stock.adjust(any())).willReturn(new StockLevel(MARINA, MILK, 34, 4));

            assertThat(post("""
                            {"sku": "MILK-FULL-1L", "delta": 24, "reason": "RECEIVED"}""")).hasStatusOk().bodyJson().isStrictlyEqualTo("""
                            {"sku": "MILK-FULL-1L", "onHand": 34, "reserved": 4, "available": 30}""");
            verify(stock).adjust(new AdjustStock(MARINA, MILK, 24, AdjustmentReason.RECEIVED));
        }

        @Test
        void refusingToGoBelowReservedIsAConflictThatCarriesTheNumbers() {
            StockBelowReservedException refused = catchThrowableOfType(
                    StockBelowReservedException.class, () -> new StockLevel(MARINA, MILK, 10, 4).adjust(-7));
            given(stock.adjust(any())).willThrow(refused);

            AbstractJsonContentAssert<?> problem =
                    assertProblem(post(DAMAGED_SEVEN), HttpStatus.CONFLICT, "STOCK_BELOW_RESERVED");

            problem.extractingPath("$.detail").asString().contains("4 are reserved");
            problem.extractingPath("$.sku").isEqualTo("MILK-FULL-1L");
            problem.extractingPath("$.onHand").isEqualTo(10);
            problem.extractingPath("$.reserved").isEqualTo(4);
            problem.extractingPath("$.delta").isEqualTo(-7);
        }

        @Test
        void aSkuTheCatalogDoesNotKnowIsUnprocessable() {
            given(stock.adjust(any())).willThrow(new UnknownSkuException(MILK));

            assertProblem(post(RECEIVED_FIVE), HttpStatus.UNPROCESSABLE_CONTENT, "UNKNOWN_SKU")
                    .extractingPath("$.sku")
                    .isEqualTo("MILK-FULL-1L");
        }

        @Test
        void anUnknownStoreIsNotFound() {
            given(stock.adjust(any())).willThrow(new StoreNotFoundException(MARINA));

            assertProblem(post(RECEIVED_FIVE), HttpStatus.NOT_FOUND, "STORE_NOT_FOUND")
                    .extractingPath("$.storeId")
                    .isEqualTo("DXB-MARINA");
        }

        @Test
        void rejectsAZeroMissingOrOutOfRangeDeltaAndAMissingReasonOrSku() {
            assertValidationFailed("""
                    {"sku": "MILK-FULL-1L", "delta": 0, "reason": "RECEIVED"}""", List.of(invalid("delta", "must not be zero")));
            assertValidationFailed("""
                    {"sku": "MILK-FULL-1L", "reason": "RECEIVED"}""", List.of(invalid("delta", "must not be null")));
            assertValidationFailed("""
                    {"sku": "MILK-FULL-1L", "delta": 10001, "reason": "RECEIVED"}""", List.of(invalid("delta", "must be less than or equal to 10000")));
            assertValidationFailed("""
                    {"sku": "MILK-FULL-1L", "delta": -10001, "reason": "RECEIVED"}""", List.of(invalid("delta", "must be greater than or equal to -10000")));
            // One entry per field, sorted: the same invalid request always gets the same answer.
            assertValidationFailed(
                    """
                    {"delta": 3}""", List.of(invalid("reason", "must not be null"), invalid("sku", "must not be null")));
            verifyNoInteractions(stock);
        }

        @Test
        void anUnreadableBodyOrAnUnknownReasonIsAnInvalidRequest() {
            assertProblem(post("{\"sku\": "), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
            assertProblem(post("""
                            {"sku": "MILK-FULL-1L", "delta": 1, "reason": "STOLEN"}"""), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
            assertProblem(post("""
                            {"sku": "milk", "delta": 1, "reason": "RECEIVED"}"""), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
            verifyNoInteractions(stock);
        }

        @Test
        void aFailureDeeperDownIsNotBlamedOnTheClient() {
            given(stock.adjust(any())).willThrow(new IllegalArgumentException("stock row corrupt: reserved -1"));

            assertThat(post("""
                            {"sku": "MILK-FULL-1L", "delta": 5, "reason": "RECEIVED"}"""))
                    .as("not mapped to a 400 that echoes the message; the server answers 500")
                    .hasFailed()
                    .failure()
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        }

        private MvcTestResult post(String json) {
            return mvc.post()
                    .uri("/v1/stores/DXB-MARINA/stock/adjustments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json)
                    .exchange();
        }

        private void assertValidationFailed(String json, List<Map<String, String>> errors) {
            assertProblem(post(json), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED")
                    .extractingPath("$.errors")
                    .asArray()
                    .containsExactlyElementsOf(errors);
        }

        private static Map<String, String> invalid(String field, String message) {
            return Map.of("field", field, "message", message);
        }
    }

    /** An RFC 9457 problem with the contract's stable code, and a type URN derived from it. */
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
}
