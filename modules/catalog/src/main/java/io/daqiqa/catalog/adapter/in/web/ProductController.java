package io.daqiqa.catalog.adapter.in.web;

import io.daqiqa.catalog.adapter.in.web.ProductResponse.ProductDto;
import io.daqiqa.catalog.adapter.in.web.ProductResponse.ProductPageDto;
import io.daqiqa.catalog.application.CatalogPage;
import io.daqiqa.catalog.application.CatalogService;
import io.daqiqa.catalog.domain.Category;
import io.daqiqa.kernel.Sku;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/products")
class ProductController {

    private final CatalogService catalog;

    ProductController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    ProductPageDto browse(
            @RequestParam(required = false) @Nullable Category category,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(CatalogService.MAX_PAGE_SIZE) int limit) {
        CatalogPage page = catalog.browse(category, cursor == null ? null : Sku.of(cursor), limit);
        return new ProductPageDto(
                page.items().stream().map(ProductDto::from).toList(),
                page.nextAfter() == null ? null : page.nextAfter().value());
    }

    @GetMapping("/{sku}")
    ProductDto get(@PathVariable String sku) {
        return ProductDto.from(catalog.get(Sku.of(sku)));
    }
}
