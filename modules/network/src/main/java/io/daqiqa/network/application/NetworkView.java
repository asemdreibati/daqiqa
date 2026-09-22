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
import io.daqiqa.network.domain.StoreReach;
import io.daqiqa.network.domain.StoreRepository;
import io.daqiqa.network.domain.TravelTimeModel;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The in-memory view of the store network that answers {@link CoverageIndex} and {@link StoreDirectory} queries on the
 * promise hot path without touching the database.
 *
 * <p><b>Reads never lock.</b> The view is a {@link NetworkSnapshot} held in an {@link AtomicReference}: a reader takes
 * the current snapshot with one volatile read and works on it, however long it takes; a refresh builds a complete new
 * snapshot and swaps it in. A reader therefore sees either the old network or the new one, never a mixture.
 *
 * <p><b>Refreshes are serialised.</b> {@link #refresh()} reloads every store from the database and swaps the result in
 * while holding a lock, so a refresh that read older data can never overwrite one that read newer data. It runs when
 * the bean starts (before the application serves traffic), after every committed status change on this instance, and
 * periodically when the application enables scheduling (see {@code NetworkConfiguration}), which bounds how stale an
 * instance can be about changes made through another instance.
 */
@Service
public class NetworkView implements CoverageIndex, StoreDirectory, InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(NetworkView.class);

    private final StoreRepository repository;
    private final TravelTimeModel travel;
    private final AtomicReference<NetworkSnapshot> current;
    // A ReentrantLock rather than synchronized: the critical section blocks on JDBC, and virtual threads must not pin.
    private final ReentrantLock refreshLock = new ReentrantLock();
    private final Timer refreshTimer;

    public NetworkView(StoreRepository repository, CellGrid grid, TravelTimeModel travel, MeterRegistry meters) {
        this.repository = repository;
        this.travel = travel;
        this.current = new AtomicReference<>(NetworkSnapshot.empty(grid));
        this.refreshTimer = Timer.builder("daqiqa.network.refresh")
                .description("Time to reload the store network and publish a new coverage snapshot")
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

    /** Reloads every store from the database and publishes a new snapshot. Safe to call from any thread. */
    public void refresh() {
        refreshLock.lock();
        try {
            refreshTimer.record(() -> current.set(current.get().withStores(repository.findAll())));
        } finally {
            refreshLock.unlock();
        }
    }

    /**
     * Applies a status change to this instance once it is committed (immediately when there is no transaction), so
     * the coverage index never shows a change that was rolled back.
     */
    @TransactionalEventListener(fallbackExecution = true)
    public void onStoreStatusChanged(StoreStatusChanged event) {
        refresh();
        log.info("Store {} is now {}; coverage refreshed", event.storeId(), event.status());
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
