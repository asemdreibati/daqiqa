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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Readers never lock and never see a half-built network; concurrent changes and reloads converge on the database. */
class NetworkViewConcurrencyTest {

    private static final int READERS = 4;

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
        CountDownLatch start = new CountDownLatch(1);
        // The writer waits until every reader is reading, so on two CPUs the flips cannot all happen before reads.
        CountDownLatch readersReading = new CountDownLatch(READERS);
        AtomicBoolean writing = new AtomicBoolean(true);

        List<Future<Integer>> readers = IntStream.range(0, READERS)
                .mapToObj(i -> threads.submit(() -> {
                    start.await();
                    int reads = 0;
                    do {
                        assertThat(ids(view.storesServing(DUBAI_MARINA)))
                                .isIn(List.of("DXB-STEADY", "DXB-FLIP"), List.of("DXB-STEADY"));
                        if (reads++ == 0) {
                            readersReading.countDown();
                        }
                    } while (writing.get());
                    return reads;
                }))
                .toList();
        Future<?> writer = threads.submit(() -> {
            start.await();
            readersReading.await();
            try {
                for (int i = 0; i < 500; i++) {
                    StoreStatus status = i % 2 == 0 ? StoreStatus.PAUSED : StoreStatus.OPEN;
                    Store changed =
                            repository.updateStatus(flipping, status, NOW).orElseThrow();
                    view.onStoreStatusChanged(new StoreStatusChanged(changed));
                    if (i % 50 == 0) {
                        view.refresh();
                    }
                }
            } finally {
                writing.set(false);
            }
            return null;
        });
        start.countDown();

        writer.get(1, TimeUnit.MINUTES);
        for (Future<Integer> reader : readers) {
            assertThat(reader.get(1, TimeUnit.MINUTES)).isPositive();
        }
    }

    /**
     * Several threads change the same store while another reloads the network: the callbacks arrive in any order and
     * reloads read the database at any time, yet the view ends up exactly as the database is.
     */
    @Test
    void concurrentChangesAndReloadsConvergeOnTheDatabase() throws Exception {
        List<Store> network = IntStream.range(0, 3)
                .mapToObj(i -> openStore("DXB-S" + i, offset(DUBAI_MARINA, i * 120, 1_000), 2_000))
                .toList();
        repository.replaceAll(network);
        view.refresh();
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean changing = new AtomicBoolean(true);

        List<Future<?>> writers = new ArrayList<>();
        for (int thread = 0; thread < 6; thread++) {
            StoreId store = network.get(thread % network.size()).id();
            int offset = thread;
            writers.add(threads.submit(() -> {
                start.await();
                for (int i = 0; i < 300; i++) {
                    StoreStatus status = StoreStatus.values()[(i + offset) % StoreStatus.values().length];
                    Store changed = repository.updateStatus(store, status, NOW).orElseThrow();
                    view.onStoreStatusChanged(new StoreStatusChanged(changed));
                }
                return null;
            }));
        }
        Future<Integer> reloader = threads.submit(() -> {
            start.await();
            int reloads = 0;
            do {
                view.refresh();
                reloads++;
            } while (changing.get());
            return reloads;
        });
        start.countDown();
        for (Future<?> writer : writers) {
            writer.get(1, TimeUnit.MINUTES);
        }
        changing.set(false);
        assertThat(reloader.get(1, TimeUnit.MINUTES)).isPositive();

        Map<StoreId, StoreStatus> inDatabase =
                repository.findAll().stream().collect(Collectors.toMap(Store::id, Store::status));
        Map<StoreId, StoreStatus> inView =
                view.findAll().stream().collect(Collectors.toMap(DarkStore::id, DarkStore::status));
        assertThat(inView).isEqualTo(inDatabase);
    }
}
