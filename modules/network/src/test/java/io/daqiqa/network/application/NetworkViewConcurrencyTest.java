package io.daqiqa.network.application;

import static io.daqiqa.network.StoreFixtures.DUBAI_MARINA;
import static io.daqiqa.network.StoreFixtures.offset;
import static io.daqiqa.network.StoreFixtures.openStore;
import static io.daqiqa.network.application.CoverageIndexTest.GRID;
import static io.daqiqa.network.application.CoverageIndexTest.NOW;
import static io.daqiqa.network.application.CoverageIndexTest.TRAVEL;
import static io.daqiqa.network.application.CoverageIndexTest.ids;
import static org.assertj.core.api.Assertions.assertThat;

import io.daqiqa.kernel.StoreId;
import io.daqiqa.network.api.DarkStore;
import io.daqiqa.network.api.StoreStatus;
import io.daqiqa.network.domain.Store;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Readers never lock and never see a half-built network; concurrent refreshes converge on the database's state. */
class NetworkViewConcurrencyTest {

    private final InMemoryStoreRepository repository = new InMemoryStoreRepository();
    private final NetworkView view = new NetworkView(repository, GRID, TRAVEL, new SimpleMeterRegistry());
    private final ExecutorService threads = Executors.newFixedThreadPool(8);

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    @Test
    void readersSeeEitherTheOldOrTheNewNetworkWhileStatusesFlip() throws Exception {
        StoreId flipping = StoreId.of("DXB-FLIP");
        repository.replaceAll(List.of(
                openStore("DXB-STEADY", DUBAI_MARINA, 3_000),
                openStore(flipping.value(), offset(DUBAI_MARINA, 90, 500), 3_000)));
        view.refresh();
        AtomicBoolean writing = new AtomicBoolean(true);

        Future<?> writer = threads.submit(() -> {
            try {
                for (int i = 0; i < 500; i++) {
                    StoreStatus status = i % 2 == 0 ? StoreStatus.PAUSED : StoreStatus.OPEN;
                    repository.updateStatus(flipping, status, NOW);
                    view.onStoreStatusChanged(new StoreStatusChanged(flipping, status));
                }
            } finally {
                writing.set(false);
            }
        });
        List<Future<Integer>> readers = IntStream.range(0, 4)
                .mapToObj(i -> threads.submit(() -> {
                    int reads = 0;
                    while (writing.get()) {
                        assertThat(ids(view.storesServing(DUBAI_MARINA)))
                                .isIn(List.of("DXB-STEADY", "DXB-FLIP"), List.of("DXB-STEADY"));
                        reads++;
                    }
                    return reads;
                }))
                .toList();

        writer.get(1, TimeUnit.MINUTES);
        for (Future<Integer> reader : readers) {
            assertThat(reader.get(1, TimeUnit.MINUTES)).isPositive();
        }
    }

    @Test
    void concurrentRefreshesLeaveTheViewEqualToTheDatabase() throws Exception {
        List<Store> network = IntStream.range(0, 6)
                .mapToObj(i -> openStore("DXB-S" + i, offset(DUBAI_MARINA, i * 60, 1_000), 2_000))
                .toList();
        repository.replaceAll(network);
        view.refresh();

        List<Future<?>> writers = network.stream()
                .map(store -> threads.submit(() -> {
                    for (int i = 0; i < 200; i++) {
                        StoreStatus status = StoreStatus.values()[i % StoreStatus.values().length];
                        repository.updateStatus(store.id(), status, NOW);
                        view.onStoreStatusChanged(new StoreStatusChanged(store.id(), status));
                    }
                }))
                .collect(Collectors.toList());
        for (Future<?> writer : writers) {
            writer.get(1, TimeUnit.MINUTES);
        }

        Map<StoreId, StoreStatus> inDatabase =
                repository.findAll().stream().collect(Collectors.toMap(Store::id, Store::status));
        Map<StoreId, StoreStatus> inView =
                view.findAll().stream().collect(Collectors.toMap(DarkStore::id, DarkStore::status));
        assertThat(inView).isEqualTo(inDatabase);
    }
}
