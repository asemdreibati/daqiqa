package io.daqiqa.catalog.adapter.in.web;

import io.daqiqa.catalog.api.Product;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** JSON shapes of the catalog endpoints. Kept separate from domain and API types so the wire format is explicit. */
final class ProductResponse {

    private ProductResponse() {}

    record ProductDto(String sku, NameDto name, String category, MoneyDto price) {

        static ProductDto from(Product product) {
            return new ProductDto(
                    product.sku().value(),
                    new NameDto(product.name().en(), product.name().ar()),
                    product.category(),
                    new MoneyDto(
                            product.price().amountMinor(),
                            product.price().currency().getCurrencyCode()));
        }
    }

    record NameDto(String en, String ar) {}

    record MoneyDto(long amountMinor, String currency) {}

    record ProductPageDto(List<ProductDto> items, @Nullable String nextCursor) {}
}
