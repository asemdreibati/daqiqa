package io.daqiqa.inventory.application;

import io.daqiqa.inventory.api.AvailabilityQuery;
import io.daqiqa.inventory.api.StockSnapshot;
import io.daqiqa.inventory.domain.StockLevelRepository;
import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.StoreId;
import java.util.Collection;
import java.util.Map;
import org.springframework.stereotype.Service;

/** What can be sold where, for the promise engine's hot path. */
@Service
class AvailabilityService implements AvailabilityQuery {

    private final StockLevelRepository stockLevels;

    AvailabilityService(StockLevelRepository stockLevels) {
        this.stockLevels = stockLevels;
    }

    /**
     * {@inheritDoc}
     *
     * <p>One statement served by the primary key. Deliberately not transactional: a single statement is consistent on
     * its own, and the hot path saves the round trip of a COMMIT (it still joins a caller's transaction if there is
     * one).
     */
    @Override
    public StockSnapshot availability(Collection<StoreId> stores, Collection<Sku> skus) {
        if (stores.isEmpty() || skus.isEmpty()) {
            return new StockSnapshot(Map.of());
        }
        return new StockSnapshot(stockLevels.available(stores, skus));
    }
}
