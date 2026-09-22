package io.daqiqa.catalog.application;

import io.daqiqa.catalog.api.Product;
import io.daqiqa.kernel.Sku;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** One page of products; {@code nextAfter} is the cursor for the next page, or null on the last page. */
public record CatalogPage(List<Product> items, @Nullable Sku nextAfter) {

    public CatalogPage {
        items = List.copyOf(items);
    }
}
