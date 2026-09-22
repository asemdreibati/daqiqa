package io.daqiqa.promise.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.kernel.PromiseId;
import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.kernel.StoreId;
import io.daqiqa.promise.api.DeliveryTier;
import io.daqiqa.promise.application.IssuedPromise;
import io.daqiqa.promise.application.PromiseResult;
import io.daqiqa.promise.application.PromiseService;
import io.daqiqa.promise.domain.EtaBreakdown;
import io.daqiqa.promise.domain.InvalidBasketException;
import io.daqiqa.promise.domain.Quote;
import io.daqiqa.promise.domain.Suggestion;
import io.daqiqa.promise.domain.UnavailableReason;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(PromiseController.class)
class PromiseControllerTest {

    private static final GeoPoint CUSTOMER = GeoPoint.of(25.0772, 55.139);
    private static final List<SkuQuantity> BREAKFAST =
            List.of(SkuQuantity.of("MILK-FULL-1L", 2), SkuQuantity.of("BREAD-ARABIC-6PK", 1));
    private static final String BREAKFAST_REQUEST = """
            {"location": {"latitude": 25.0772, "longitude": 55.1390},
             "items": [{"sku": "MILK-FULL-1L", "quantity": 2}, {"sku": "BREAD-ARABIC-6PK", "quantity": 1}]}""";

    @Autowired
    MockMvcTester mvc;

    @MockitoBean
    PromiseService promises;

    @Test
    void answersWithASignedOfferInTheContractShape() {
        IssuedPromise offer = new IssuedPromise(
                PromiseId.of("0192f7c4-7c1e-7cc2-9d3a-2f1a6c0e9b11"),
                new Quote(StoreId.of("DXB-MARINA"), DeliveryTier.EXPRESS_15, new EtaBreakdown(120, 96, 60, 330, 90)),
                Instant.parse("2026-09-22T12:01:30Z"),
                "v1.k1.cGF5bG9hZA.c2lnbmF0dXJl");
        given(promises.promise(CUSTOMER, BREAKFAST)).willReturn(new PromiseResult.Available(offer, List.of()));

        assertThat(post(BREAKFAST_REQUEST)).hasStatusOk().bodyJson().isStrictlyEqualTo("""
                        {"status": "AVAILABLE",
                         "offer": {
                           "promiseId": "0192f7c4-7c1e-7cc2-9d3a-2f1a6c0e9b11",
                           "storeId": "DXB-MARINA",
                           "tier": "EXPRESS_15",
                           "etaMinutes": 12,
                           "expiresAt": "2026-09-22T12:01:30Z",
                           "token": "v1.k1.cGF5bG9hZA.c2lnbmF0dXJl",
                           "breakdown": {"queueSeconds": 120, "pickSeconds": 96, "riderWaitSeconds": 60,
                                         "travelSeconds": 330, "bufferSeconds": 90}},
                         "suggestions": []}""");
    }

    @Test
    void answersThatItCannotDeliverWithTheReasonAndFasterOptions() {
        given(promises.promise(CUSTOMER, BREAKFAST))
                .willReturn(new PromiseResult.Unavailable(
                        UnavailableReason.OUT_OF_STOCK,
                        List.of(new Suggestion(
                                StoreId.of("DXB-JLT"),
                                List.of(Sku.of("BREAD-ARABIC-6PK")),
                                7,
                                DeliveryTier.EXPRESS_15))));

        assertThat(post(BREAKFAST_REQUEST)).hasStatusOk().bodyJson().isStrictlyEqualTo("""
                        {"status": "UNAVAILABLE",
                         "unavailableReason": "OUT_OF_STOCK",
                         "suggestions": [{"type": "REMOVE_ITEMS", "skus": ["BREAD-ARABIC-6PK"], "storeId": "DXB-JLT",
                                          "etaMinutes": 7, "tier": "EXPRESS_15"}]}""");
    }

    static Stream<Arguments> invalidRequests() {
        String location = "\"location\": {\"latitude\": 25.0772, \"longitude\": 55.139}";
        String items = "\"items\": [{\"sku\": \"MILK-FULL-1L\", \"quantity\": 2}]";
        String fiftyOneItems = IntStream.range(0, 51)
                .mapToObj(i -> "{\"sku\": \"SKU-" + i + "\", \"quantity\": 1}")
                .collect(Collectors.joining(",", "\"items\": [", "]"));
        return Stream.of(
                Arguments.of("{" + items + "}", "location"),
                Arguments.of(
                        "{\"location\": {\"latitude\": 91, \"longitude\": 55.1}, " + items + "}", "location.latitude"),
                Arguments.of(
                        "{\"location\": {\"latitude\": 25.1, \"longitude\": -181}, " + items + "}",
                        "location.longitude"),
                Arguments.of("{\"location\": {\"longitude\": 55.1}, " + items + "}", "location.latitude"),
                Arguments.of("{" + location + "}", "items"),
                Arguments.of("{" + location + ", \"items\": []}", "items"),
                Arguments.of("{" + location + ", " + fiftyOneItems + "}", "items"),
                Arguments.of("{" + location + ", \"items\": [null]}", "items[0]"),
                Arguments.of("{" + location + ", \"items\": [{\"sku\": \"milk\", \"quantity\": 1}]}", "items[0].sku"),
                Arguments.of(
                        "{" + location + ", \"items\": [{\"sku\": \"MILK-1L\", \"quantity\": 0}]}",
                        "items[0].quantity"),
                Arguments.of(
                        "{" + location + ", \"items\": [{\"sku\": \"MILK-1L\", \"quantity\": 100}]}",
                        "items[0].quantity"),
                Arguments.of("{" + location + ", \"items\": [{\"sku\": \"MILK-1L\"}]}", "items[0].quantity"));
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void anInvalidRequestIsAValidationProblemNamingTheField(String body, String field) {
        assertThat(post(body))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"type": "urn:daqiqa:problem:validation-failed", "status": 400, "code": "VALIDATION_FAILED",
                         "errors": [{"field": "%s"}]}""".formatted(field));
        verifyNoInteractions(promises);
    }

    @Test
    void malformedJsonIsAnInvalidRequest() {
        assertThat(post("{\"location\": "))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"type": "urn:daqiqa:problem:invalid-request", "code": "INVALID_REQUEST"}""");
    }

    @Test
    void aValueOfTheWrongTypeIsAnInvalidRequestNamingTheField() {
        assertThat(post("""
                        {"location": {"latitude": 25.0772, "longitude": 55.139},
                         "items": [{"sku": "MILK-FULL-1L", "quantity": "two"}]}""")).hasStatus(HttpStatus.BAD_REQUEST).bodyJson().isLenientlyEqualTo("""
                        {"code": "INVALID_REQUEST", "detail": "Invalid value for 'items[0].quantity'"}""");
    }

    @Test
    void aBasketThatBreaksARuleOnceMergedIsAnInvalidRequest() {
        given(promises.promise(any(), any()))
                .willThrow(new InvalidBasketException("Once duplicate lines are merged, MILK-FULL-1L has 120 units"));

        assertThat(post(BREAKFAST_REQUEST))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"code": "INVALID_REQUEST", "detail": "Once duplicate lines are merged, MILK-FULL-1L has 120 units"}""");
    }

    private MockMvcTester.MockMvcRequestBuilder post(String body) {
        return mvc.post()
                .uri("/v1/promises")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
