package io.daqiqa.catalog.adapter.out.persistence;

import io.daqiqa.catalog.domain.CatalogItem;
import io.daqiqa.catalog.domain.CatalogItemRepository;
import io.daqiqa.catalog.domain.Category;
import io.daqiqa.kernel.LocalizedText;
import io.daqiqa.kernel.Money;
import io.daqiqa.kernel.Sku;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcCatalogItemRepository implements CatalogItemRepository {

    private static final String SELECT =
            "SELECT sku, name_en, name_ar, category, price_minor, currency FROM catalog.product";

    private final JdbcClient jdbc;

    JdbcCatalogItemRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<CatalogItem> findBySku(Sku sku) {
        return jdbc.sql(SELECT + " WHERE sku = :sku")
                .param("sku", sku.value())
                .query(JdbcCatalogItemRepository::map)
                .optional();
    }

    @Override
    public List<CatalogItem> findAllBySku(Collection<Sku> skus) {
        if (skus.isEmpty()) {
            return List.of();
        }
        // One bound array parameter instead of an IN list: a single cached plan whatever the basket size.
        String[] values = skus.stream().map(Sku::value).distinct().toArray(String[]::new);
        return jdbc.sql(SELECT + " WHERE sku = ANY(:skus)")
                .param("skus", values)
                .query(JdbcCatalogItemRepository::map)
                .list();
    }

    @Override
    public List<CatalogItem> browse(@Nullable Category category, @Nullable Sku after, int limit) {
        // Served by index (category, sku) when filtering, by the primary key otherwise.
        return jdbc.sql(SELECT + " " + """
                        WHERE (CAST(:category AS text) IS NULL OR category = :category)
                          AND (CAST(:after AS text) IS NULL OR sku > :after)
                        ORDER BY sku
                        LIMIT :limit""")
                .param("category", category == null ? null : category.name())
                .param("after", after == null ? null : after.value())
                .param("limit", limit)
                .query(JdbcCatalogItemRepository::map)
                .list();
    }

    private static CatalogItem map(ResultSet rs, int rowNum) throws SQLException {
        return new CatalogItem(
                Sku.of(rs.getString("sku")),
                LocalizedText.of(rs.getString("name_en"), rs.getString("name_ar")),
                Category.valueOf(rs.getString("category")),
                Money.of(rs.getLong("price_minor"), rs.getString("currency")));
    }
}
