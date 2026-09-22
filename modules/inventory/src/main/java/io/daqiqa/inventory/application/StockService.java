package io.daqiqa.inventory.application;

import io.daqiqa.catalog.api.ProductCatalog;
import io.daqiqa.contracts.events.StockAdjustedV1;
import io.daqiqa.inventory.domain.StockAdjustment;
import io.daqiqa.inventory.domain.StockAdjustmentLog;
import io.daqiqa.inventory.domain.StockBelowReservedException;
import io.daqiqa.inventory.domain.StockLevel;
import io.daqiqa.inventory.domain.StockLevelRepository;
import io.daqiqa.inventory.domain.StoreStock;
import io.daqiqa.kernel.Ids;
import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.StoreId;
import io.daqiqa.messaging.OutboxWriter;
import io.daqiqa.network.api.StoreDirectory;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stock levels as store operations see them: reading them, and recording deliveries, damage and counts. */
@Service
public class StockService {

    private final StockLevelRepository stockLevels;
    private final StockAdjustmentLog adjustments;
    private final StoreDirectory stores;
    private final ProductCatalog catalog;
    private final OutboxWriter outbox;
    private final Clock clock;

    StockService(
            StockLevelRepository stockLevels,
            StockAdjustmentLog adjustments,
            StoreDirectory stores,
            ProductCatalog catalog,
            OutboxWriter outbox,
            Clock clock) {
        this.stockLevels = stockLevels;
        this.adjustments = adjustments;
        this.stores = stores;
        this.catalog = catalog;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * The current stock of {@code skus} at {@code storeId}; SKUs the store does not stock have none.
     *
     * @throws StoreNotFoundException if there is no such store
     */
    public StoreStock levels(StoreId storeId, Collection<Sku> skus) {
        requireStore(storeId);
        return stockLevels.find(storeId, skus);
    }

    /**
     * Applies a stock change from store operations and records it: the new level, an audit entry and
     * {@code StockAdjustedV1}, in one transaction. The event id is the audit entry's id: one fact, one id.
     *
     * <p>The level's row is locked while the change is checked, so adjustments and reservations of the same SKU at the
     * same store queue behind each other; a refused change writes nothing.
     *
     * @return the level after the change
     * @throws StoreNotFoundException if there is no such store
     * @throws UnknownSkuException if the SKU is not in the catalog
     * @throws StockBelowReservedException if fewer units would be on hand than are reserved for orders
     */
    @Transactional
    public StockLevel adjust(AdjustStock command) {
        requireStore(command.storeId());
        if (catalog.find(command.sku()).isEmpty()) {
            throw new UnknownSkuException(command.sku());
        }
        Instant now = Timestamps.now(clock);
        StockLevel adjusted =
                stockLevels.lockOrCreate(command.storeId(), command.sku(), now).adjust(command.delta());
        stockLevels.adjustOnHand(command.storeId(), command.sku(), command.delta(), now);
        UUID adjustmentId = Ids.newTimeOrderedId();
        adjustments.append(new StockAdjustment(
                adjustmentId,
                command.storeId(),
                command.sku(),
                command.delta(),
                command.reason(),
                adjusted.onHand(),
                now));
        outbox.append(new StockAdjustedV1(
                adjustmentId,
                now,
                command.storeId().value(),
                command.sku().value(),
                command.delta(),
                command.reason().name(),
                adjusted.onHand(),
                adjusted.reserved()));
        return adjusted;
    }

    private void requireStore(StoreId storeId) {
        if (stores.find(storeId).isEmpty()) {
            throw new StoreNotFoundException(storeId);
        }
    }
}
