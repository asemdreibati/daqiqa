package io.daqiqa.catalog.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.daqiqa.catalog.domain.CatalogItem;
import io.daqiqa.catalog.domain.Category;
import io.daqiqa.kernel.Sku;
import io.daqiqa.testing.postgres.PostgresTestDatabase;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Runs the real SQL against a real PostgreSQL (see PostgresTestDatabase). */
@Tag("integration")
class JdbcCatalogItemRepositoryTest {

    private static final PostgresTestDatabase DB =
            PostgresTestDatabase.shared().migrate("classpath:db/migration/catalog");

    private final JdbcClient jdbc = JdbcClient.create(DB.dataSource());
    private final JdbcCatalogItemRepository repository = new JdbcCatalogItemRepository(jdbc);

    @BeforeEach
    void seed() {
        DB.truncateSchemas("catalog");
        insert("BANANA-1KG", "Bananas 1kg", "موز 1 كغ", Category.FRUITS_VEGETABLES, 650);
        insert("APPLE-RED-1KG", "Red Apples 1kg", "تفاح أحمر 1 كغ", Category.FRUITS_VEGETABLES, 890);
        insert("MILK-FULL-1L", "Full Cream Milk 1L", "حليب كامل الدسم 1 لتر", Category.DAIRY_EGGS, 695);
        insert("EGGS-12", "Fresh Eggs × 12", "بيض طازج × 12", Category.DAIRY_EGGS, 1050);
        insert("TOMATO-1KG", "Tomatoes 1kg", "طماطم 1 كغ", Category.FRUITS_VEGETABLES, 550);
    }

    @Test
    void findsOneProductBySkuWithBothLanguages() {
        CatalogItem milk = repository.findBySku(Sku.of("MILK-FULL-1L")).orElseThrow();

        assertThat(milk.name().ar()).isEqualTo("حليب كامل الدسم 1 لتر");
        assertThat(milk.price().amountMinor()).isEqualTo(695);
        assertThat(repository.findBySku(Sku.of("DOES-NOT-EXIST"))).isEmpty();
    }

    @Test
    void findsManyProductsInOneQueryIgnoringUnknownSkus() {
        List<CatalogItem> items =
                repository.findAllBySku(List.of(Sku.of("EGGS-12"), Sku.of("BANANA-1KG"), Sku.of("NOPE-1")));

        assertThat(items)
                .extracting(CatalogItem::sku)
                .containsExactlyInAnyOrder(Sku.of("EGGS-12"), Sku.of("BANANA-1KG"));
    }

    @Test
    void pagesThroughACategoryWithoutGapsOrRepeats() {
        List<Sku> seen = new ArrayList<>();
        Sku after = null;
        List<CatalogItem> page;
        do {
            page = repository.browse(Category.FRUITS_VEGETABLES, after, 2);
            page.forEach(item -> seen.add(item.sku()));
            after = page.isEmpty() ? after : page.getLast().sku();
        } while (page.size() == 2);

        assertThat(seen).containsExactly(Sku.of("APPLE-RED-1KG"), Sku.of("BANANA-1KG"), Sku.of("TOMATO-1KG"));
    }

    private void insert(String sku, String en, String ar, Category category, long price) {
        jdbc.sql("""
                        INSERT INTO catalog.product (sku, name_en, name_ar, category, price_minor, currency)
                        VALUES (:sku, :en, :ar, :category, :price, 'AED')""")
                .param("sku", sku)
                .param("en", en)
                .param("ar", ar)
                .param("category", category.name())
                .param("price", price)
                .update();
    }
}
