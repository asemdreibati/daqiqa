package io.daqiqa.network.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.kernel.LocalizedText;
import io.daqiqa.kernel.StoreId;
import io.daqiqa.network.api.DarkStore;
import io.daqiqa.network.api.StoreStatus;
import io.daqiqa.network.application.StoreNotFoundException;
import io.daqiqa.network.application.StoreService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(StoreController.class)
class StoreControllerTest {

    private static final StoreId MARINA_ID = StoreId.of("DXB-MARINA");
    private static final DarkStore MARINA = new DarkStore(
            MARINA_ID,
            LocalizedText.of("Dubai Marina", "دبي مارينا"),
            GeoPoint.of(25.0805, 55.1403),
            StoreStatus.OPEN,
            7,
            3_000);

    @Autowired
    MockMvcTester mvc;

    @MockitoBean
    StoreService stores;

    @Test
    void listsStoresInTheContractShape() {
        given(stores.list()).willReturn(List.of(MARINA));

        assertThat(mvc.get().uri("/v1/stores")).hasStatusOk().bodyJson().isStrictlyEqualTo("""
                        {"items": [{
                          "id": "DXB-MARINA",
                          "name": {"en": "Dubai Marina", "ar": "دبي مارينا"},
                          "location": {"latitude": 25.0805, "longitude": 55.1403},
                          "status": "OPEN",
                          "activePickers": 7,
                          "serviceRadiusMeters": 3000}]}""");
    }

    @Test
    void returnsOneStore() {
        given(stores.get(MARINA_ID)).willReturn(MARINA);

        assertThat(mvc.get().uri("/v1/stores/DXB-MARINA"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.name.ar")
                .isEqualTo("دبي مارينا");
    }

    @Test
    void anUnknownStoreIsANotFoundProblemWithAStableCode() {
        given(stores.get(StoreId.of("DXB-NOWHERE"))).willThrow(new StoreNotFoundException(StoreId.of("DXB-NOWHERE")));

        assertThat(mvc.get().uri("/v1/stores/DXB-NOWHERE"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"type": "urn:daqiqa:problem:store-not-found", "status": 404, "code": "STORE_NOT_FOUND"}""");
    }

    @Test
    void aMalformedStoreIdIsAValidationProblem() {
        assertThat(mvc.get().uri("/v1/stores/dxb-marina"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"type": "urn:daqiqa:problem:validation-failed",
                         "code": "VALIDATION_FAILED",
                         "errors": [{"field": "storeId"}]}""");
        verifyNoInteractions(stores);
    }

    @Test
    void changesTheStatusOfAStore() {
        DarkStore paused = new DarkStore(MARINA_ID, MARINA.name(), MARINA.location(), StoreStatus.PAUSED, 7, 3_000);
        given(stores.changeStatus(MARINA_ID, StoreStatus.PAUSED)).willReturn(paused);

        assertThat(mvc.put()
                        .uri("/v1/stores/DXB-MARINA/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"PAUSED\"}"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("PAUSED");
    }

    @Test
    void changingTheStatusOfAnUnknownStoreIsANotFoundProblem() {
        StoreId nowhere = StoreId.of("DXB-NOWHERE");
        given(stores.changeStatus(nowhere, StoreStatus.OPEN)).willThrow(new StoreNotFoundException(nowhere));

        assertThat(mvc.put()
                        .uri("/v1/stores/DXB-NOWHERE/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"OPEN\"}"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("STORE_NOT_FOUND");
    }

    @Test
    void aMissingStatusIsAValidationProblem() {
        assertThat(mvc.put()
                        .uri("/v1/stores/DXB-MARINA/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"code": "VALIDATION_FAILED", "errors": [{"field": "status"}]}""");
        verifyNoInteractions(stores);
    }

    @Test
    void anUnknownStatusIsAnInvalidRequestThatNamesTheField() {
        assertThat(mvc.put()
                        .uri("/v1/stores/DXB-MARINA/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"OPENED\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"type": "urn:daqiqa:problem:invalid-request",
                         "code": "INVALID_REQUEST",
                         "detail": "Invalid value for 'status'"}""");
    }

    @Test
    void malformedJsonIsAnInvalidRequest() {
        assertThat(mvc.put()
                        .uri("/v1/stores/DXB-MARINA/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": "))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("INVALID_REQUEST");
    }
}
