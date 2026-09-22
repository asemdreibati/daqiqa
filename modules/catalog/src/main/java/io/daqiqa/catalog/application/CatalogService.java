package io.daqiqa.catalog.application;

import io.daqiqa.catalog.api.Product;
import io.daqiqa.catalog.api.ProductCatalog;
import io.daqiqa.catalog.domain.CatalogItem;
import io.daqiqa.catalog.domain.CatalogItemRepository;
import io.daqiqa.catalog.domain.Category;
import io.daqiqa.kernel.Sku;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-side use cases of the catalog, and the implementation of {@link ProductCatalog} for other modules. */
@Service
@Transactional(readOnly = true)
public class CatalogService implements ProductCatalog {

    public static final int MAX_PAGE_SIZE = 100;

    private final CatalogItemRepository repository;

    public CatalogService(CatalogItemRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Product> find(Sku sku) {
        return repository.findBySku(sku).map(CatalogService::toProduct);
    }

    @Override
    public Map<Sku, Product> findAll(Collection<Sku> skus) {
        if (skus.isEmpty()) {
            return Map.of();
        }
        return repository.findAllBySku(skus).stream()
                .map(CatalogService::toProduct)
                .collect(Collectors.toUnmodifiableMap(Product::sku, Function.identity()));
    }

    public Product get(Sku sku) {
        return find(sku).orElseThrow(() -> new ProductNotFoundException(sku));
    }

    public CatalogPage browse(@Nullable Category category, @Nullable Sku after, int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE_SIZE);
        }
        // Fetch one extra row to learn whether another page exists, without a COUNT query.
        List<CatalogItem> rows = repository.browse(category, after, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<CatalogItem> page = hasMore ? rows.subList(0, limit) : rows;
        Sku next = hasMore ? page.getLast().sku() : null;
        return new CatalogPage(page.stream().map(CatalogService::toProduct).toList(), next);
    }

    private static Product toProduct(CatalogItem item) {
        return new Product(item.sku(), item.name(), item.category().name(), item.price());
    }
}
