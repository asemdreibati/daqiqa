package io.daqiqa.inventory.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntFunction;

/** Runs many tasks that really collide: each on its own thread, all released by one starting gate. */
public final class Concurrently {

    private static final long TIMEOUT_SECONDS = 120;

    private Concurrently() {}

    /**
     * Runs {@code task.apply(i)} for {@code i} in {@code 0..tasks-1}, each on its own platform thread. Threads first
     * park at a gate; the gate opens once all of them are parked. Returns the results in task order.
     *
     * @throws ExecutionException if any task failed (with the first failure as its cause)
     */
    public static <T> List<T> run(int tasks, IntFunction<T> task)
            throws InterruptedException, ExecutionException, TimeoutException {
        ExecutorService threads = Executors.newFixedThreadPool(tasks);
        CountDownLatch parked = new CountDownLatch(tasks);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < tasks; i++) {
                int index = i;
                futures.add(threads.submit(() -> {
                    parked.countDown();
                    gate.await();
                    return task.apply(index);
                }));
            }
            if (!parked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new TimeoutException("Not every task reached the starting gate");
            }
            gate.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            threads.shutdownNow();
        }
    }
}
