package io.daqiqa.network.application;

import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.kernel.StoreId;
import io.daqiqa.network.api.CoverageIndex;
import io.daqiqa.network.api.DarkStore;
import io.daqiqa.network.api.StoreCoverage;
import io.daqiqa.network.api.StoreDirectory;
import io.daqiqa.network.api.StoreStatus;
import io.daqiqa.network.domain.CellGrid;
import io.daqiqa.network.domain.NetworkSnapshot;
import io.daqiqa.network.domain.Store;
import io.daqiqa.network.domain.StoreReach;
import io.daqiqa.network.domain.StoreRepository;
import io.daqiqa.network.domain.TravelTimeModel;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The in-memory view of the store network that answers {@link CoverageIndex} and {@link StoreDirectory} queries on the
 * promise hot path without touching the database.
 *
 * <p><b>Nothing locks.</b> The view is a {@link NetworkSnapshot} held in an {@link AtomicReference}. A reader takes the
 * current snapshot with one volatile read and works on it, however long it takes, so it sees either the old network or
 * the new one, never a mixture. A writer derives the next snapshot from the current one with a pure function and swaps
 * it in with a compare-and-set.
 *
 * <p><b>Two ways in.</b>
 *
 * <ul>
 *   <li>A status change on this instance reaches the view as the row the update returned, once the transaction
 *       commits ({@link #onStoreStatusChanged}). Applying it is pure computation: no I/O, no lock, nothing that can
 *       fail while a request waits, and a rolled-back change never becomes visible.
 *   <li>{@link #refresh()} reloads the whole network from the database and reconciles the view with it: when the bean
 *       starts, before the application serves traffic, and periodically when the application enables scheduling
 *       (see {@code NetworkConfiguration}). It picks up changes made through other instances or directly in the
 *       database.
 * </ul>
 *
 * Store versions make the order of the two irrelevant: a snapshot never replaces a store with an older version of it.
 */
@Service
public class NetworkView implements CoverageIndex, StoreDirectory, InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(NetworkView.class);

    private final StoreRepository repository;
    private final TravelTimeModel travel;
    private final AtomicReference<NetworkSnapshot> current;
    private final Timer refreshTimer;

    public NetworkView(StoreRepository repository, CellGrid grid, TravelTimeModel travel, MeterRegistry meters) {
        this.repository = repository;
        this.travel = travel;
        this.current = new AtomicReference<>(NetworkSnapshot.empty(grid));
        this.refreshTimer = Timer.builder("daqiqa.network.refresh")
                .description("Time to reload the store network and reconcile the coverage snapshot with it")
                .register(meters);
        for (StoreStatus status : StoreStatus.values()) {
            Gauge.builder("daqiqa.network.stores", this, view -> view.countWithStatus(status))
                    .description("Stores by operational status, as this instance sees them")
                    .tag("status", status.name())
                    .register(meters);
        }
        Gauge.builder(
                        "daqiqa.network.coverage.cells",
                        this,
                        view -> view.current.get().indexedCells())
                .description("H3 cells covered by at least one store")
                .register(meters);
    }

    /** Builds the index from the database when the application starts, before it serves any request. */
    @Override
    public void afterPropertiesSet() {
        refresh();
        NetworkSnapshot snapshot = current.get();
        log.info("Coverage index built: {} stores, {} cells", snapshot.stores().size(), snapshot.indexedCells());
    }

    /** Reloads every store from the database and reconciles the view with it. Safe to call from any thread. */
    public void refresh() {
        refreshTimer.record(() -> {
            List<Store> committed = repository.findAll();
            current.updateAndGet(snapshot -> snapshot.reconciledWith(committed));
        });
    }

    /**
     * Applies a status change to this instance once it is committed (immediately when there is no transaction). An
     * older version than the view already holds, from a callback that arrived late, is ignored.
     */
    @TransactionalEventListener(fallbackExecution = true)
    public void onStoreStatusChanged(StoreStatusChanged event) {
        Store store = event.store();
        current.updateAndGet(snapshot -> snapshot.with(store));
        log.info("Store {} is now {} (version {})", store.id(), store.status(), store.version());
    }

    @Override
    public List<StoreCoverage> storesServing(GeoPoint point) {
        return current.get().storesServing(point, travel).stream()
                .map(NetworkView::toCoverage)
                .toList();
    }

    @Override
    public Optional<DarkStore> find(StoreId storeId) {
        return current.get().store(storeId).map(DarkStores::from);
    }

    @Override
    public List<DarkStore> findAll() {
        return current.get().stores().stream().map(DarkStores::from).toList();
    }

    private long countWithStatus(StoreStatus status) {
        return current.get().stores().stream()
                .filter(store -> store.status() == status)
                .count();
    }

    private static StoreCoverage toCoverage(StoreReach reach) {
        return new StoreCoverage(reach.store().id(), reach.travelTime(), (int) Math.round(reach.distanceMeters()));
    }
}
