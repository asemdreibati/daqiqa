package io.daqiqa.catalog.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import io.daqiqa.catalog.api.Product;
import io.daqiqa.catalog.application.CatalogPage;
import io.daqiqa.catalog.application.CatalogService;
import io.daqiqa.catalog.application.ProductNotFoundException;
import io.daqiqa.catalog.domain.Category;
import io.daqiqa.kernel.LocalizedText;
import io.daqiqa.kernel.Money;
import io.daqiqa.kernel.Sku;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(ProductController.class)
class ProductControllerTest {

    private static final Product MILK = new Product(
            Sku.of("MILK-FULL-1L"),
            LocalizedText.of("Full Cream Milk 1L", "حليب كامل الدسم 1 لتر"),
            "DAIRY_EGGS",
            Money.of(695, "AED"));

    @Autowired
    MockMvcTester mvc;

    @MockitoBean
    CatalogService catalog;

    @Test
    void returnsAProductWithBilingualNameAndMinorUnitPrice() {
        given(catalog.get(Sku.of("MILK-FULL-1L"))).willReturn(MILK);

        assertThat(mvc.get().uri("/v1/products/MILK-FULL-1L"))
                .hasStatusOk()
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"sku": "MILK-FULL-1L",
                         "name": {"en": "Full Cream Milk 1L", "ar": "حليب كامل الدسم 1 لتر"},
                         "category": "DAIRY_EGGS",
                         "price": {"amountMinor": 695, "currency": "AED"}}""");
    }

    @Test
    void returnsTheNextCursorWhenThereIsAnotherPage() {
        given(catalog.browse(eq(Category.DAIRY_EGGS), any(), eq(1)))
                .willReturn(new CatalogPage(List.of(MILK), Sku.of("MILK-FULL-1L")));

        assertThat(mvc.get().uri("/v1/products?category=DAIRY_EGGS&limit=1"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.nextCursor")
                .isEqualTo("MILK-FULL-1L");
    }

    @Test
    void unknownProductIsAProblemDetailWithAStableCode() {
        given(catalog.get(Sku.of("NOPE-1"))).willThrow(new ProductNotFoundException(Sku.of("NOPE-1")));

        assertThat(mvc.get().uri("/v1/products/NOPE-1"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("PRODUCT_NOT_FOUND");
    }

    @Test
    void rejectsAPageSizeOutsideTheAllowedRange() {
        assertThat(mvc.get().uri("/v1/products?limit=500")).hasStatus(HttpStatus.BAD_REQUEST);
    }
}
