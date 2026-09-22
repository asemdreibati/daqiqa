package io.daqiqa.network.domain;

import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.kernel.StoreId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * An immutable snapshot of the store network, indexed to answer "which stores can serve this point?" from memory.
 *
 * <p><b>How a lookup works.</b> A grid index maps each cell to the stores whose service area may touch it
 * (precomputed per store with {@link CellGrid#cellsCovering}). A lookup converts the point to its cell and applies the
 * exact rules to the few stores listed there: the store accepts orders, and the great-circle distance from the store
 * to the point is within its service radius. Cells only narrow the search down; the answer itself is exact.
 *
 * <p><b>Why snapshots.</b> Coverage is read on every promise but changes a few times a day. Readers therefore never
 * lock: they hold on to one snapshot, which never changes, while a writer builds the next one and publishes it
 * atomically. The index covers every store whatever its status, so {@link #withStores} reuses it when only statuses
 * (or names, or picker counts) changed: pausing a store costs O(stores), not a re-indexing.
 */
public final class NetworkSnapshot {

    /** Fastest first; equal times go to the nearer store, then to the smaller id, so answers are deterministic. */
    private static final Comparator<StoreReach> FASTEST_FIRST = Comparator.comparing(StoreReach::travelTime)
            .thenComparingDouble(StoreReach::distanceMeters)
            .thenComparing(reach -> reach.store().id().value());

    private static final Comparator<Store> BY_ID =
            Comparator.comparing(store -> store.id().value());

    private static final int[] NO_STORES = {};

    private final CellGrid grid;

    /** Every store, ordered by id. The grid index refers to stores by their position in this list. */
    private final List<Store> stores;

    private final Map<StoreId, Store> storesById;

    /** What the grid index was built from, position by position. Equal footprints mean the index can be reused. */
    private final List<Footprint> footprints;

    /** Cell → positions in {@link #stores} of the stores whose service area may touch that cell. Never mutated. */
    private final Map<Long, int[]> storesByCell;

    private NetworkSnapshot(
            CellGrid grid, List<Store> stores, List<Footprint> footprints, Map<Long, int[]> storesByCell) {
        this.grid = grid;
        this.stores = stores;
        this.storesById = stores.stream().collect(Collectors.toUnmodifiableMap(Store::id, Function.identity()));
        this.footprints = footprints;
        this.storesByCell = storesByCell;
    }

    /** A network without stores: nothing is covered. */
    public static NetworkSnapshot empty(CellGrid grid) {
        return new NetworkSnapshot(grid, List.of(), List.of(), Map.of());
    }

    /**
     * A snapshot of the network made of exactly {@code stores}. Reuses this snapshot's grid index when no store
     * appeared, disappeared, moved or changed its service radius.
     *
     * @throws IllegalStateException if two stores have the same id
     */
    public NetworkSnapshot withStores(Collection<Store> stores) {
        List<Store> sorted = stores.stream().sorted(BY_ID).toList();
        List<Footprint> newFootprints = sorted.stream().map(Footprint::of).toList();
        Map<Long, int[]> index = newFootprints.equals(footprints) ? storesByCell : index(newFootprints, grid);
        return new NetworkSnapshot(grid, sorted, newFootprints, index);
    }

    /** Every store, whatever its status, ordered by id. */
    public List<Store> stores() {
        return stores;
    }

    public Optional<Store> store(StoreId id) {
        return Optional.ofNullable(storesById.get(id));
    }

    /** Number of cells in the grid index: how much of the map the network covers, for operators. */
    public int indexedCells() {
        return storesByCell.size();
    }

    /** Stores that accept orders and whose service area contains {@code point}, fastest first. */
    public List<StoreReach> storesServing(GeoPoint point, TravelTimeModel travel) {
        int[] candidates = storesByCell.getOrDefault(grid.cellOf(point), NO_STORES);
        List<StoreReach> serving = new ArrayList<>(candidates.length);
        for (int position : candidates) {
            Store store = stores.get(position);
            if (!store.acceptsOrders()) {
                continue;
            }
            double distance = store.location().distanceMetersTo(point);
            if (distance <= store.serviceRadiusMeters()) {
                serving.add(new StoreReach(store, distance, travel.travelTime(distance)));
            }
        }
        serving.sort(FASTEST_FIRST);
        return Collections.unmodifiableList(serving);
    }

    private static Map<Long, int[]> index(List<Footprint> footprints, CellGrid grid) {
        Map<Long, int[]> index = new HashMap<>();
        for (int position = 0; position < footprints.size(); position++) {
            Footprint footprint = footprints.get(position);
            for (long cell : grid.cellsCovering(footprint.location(), footprint.radiusMeters())) {
                index.merge(cell, new int[] {position}, NetworkSnapshot::append);
            }
        }
        return Collections.unmodifiableMap(index);
    }

    private static int[] append(int[] positions, int[] more) {
        int[] merged = new int[positions.length + more.length];
        System.arraycopy(positions, 0, merged, 0, positions.length);
        System.arraycopy(more, 0, merged, positions.length, more.length);
        return merged;
    }

    /** The part of a store the grid index depends on. */
    private record Footprint(StoreId id, GeoPoint location, int radiusMeters) {

        static Footprint of(Store store) {
            return new Footprint(store.id(), store.location(), store.serviceRadiusMeters());
        }
    }
}
