package io.daqiqa.ordering.testsupport;

import io.daqiqa.catalog.api.Product;
import io.daqiqa.catalog.api.ProductCatalog;
import io.daqiqa.kernel.LocalizedText;
import io.daqiqa.kernel.Money;
import io.daqiqa.kernel.Sku;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** In-memory catalog. */
public final class FakeProductCatalog implements ProductCatalog {

    public static final Product MILK = new Product(
            Sku.of("MILK-FULL-1L"),
            LocalizedText.of("Full Cream Milk 1L", "حليب كامل الدسم 1 لتر"),
            "DAIRY_EGGS",
            Money.of(695, "AED"));
    public static final Product BREAD = new Product(
            Sku.of("BREAD-ARABIC-6PK"),
            LocalizedText.of("Arabic Bread × 6", "خبز عربي × 6"),
            "BAKERY",
            Money.of(300, "AED"));
    public static final Product POWERBANK = new Product(
            Sku.of("POWERBANK-10000"),
            LocalizedText.of("Power Bank 10,000mAh", "شاحن متنقل 10000 ملي أمبير"),
            "ELECTRONICS",
            Money.of(9_900, "AED"));

    private final Map<Sku, Product> products = new ConcurrentHashMap<>();

    public void add(Product product) {
        products.put(product.sku(), product);
    }

    public void reset() {
        products.clear();
    }

    @Override
    public Optional<Product> find(Sku sku) {
        return Optional.ofNullable(products.get(sku));
    }

    @Override
    public Map<Sku, Product> findAll(Collection<Sku> skus) {
        return skus.stream()
                .distinct()
                .map(products::get)
                .filter(product -> product != null)
                .collect(Collectors.toUnmodifiableMap(Product::sku, Function.identity()));
    }
}
