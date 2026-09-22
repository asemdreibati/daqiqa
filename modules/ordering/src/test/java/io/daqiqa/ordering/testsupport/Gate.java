package io.daqiqa.ordering.testsupport;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Holds one call at a known point so a test can act while it waits: the test learns that the call has arrived, does
 * what it wants to happen meanwhile, then lets the call go on.
 */
public final class Gate {

    private static final long TIMEOUT_SECONDS = 30;

    private final CountDownLatch arrived = new CountDownLatch(1);
    private final CountDownLatch opened = new CountDownLatch(1);

    /** Blocks until the held call has arrived at the gate. */
    public void awaitArrival() {
        await(arrived);
    }

    /** Lets the held call go on. */
    public void open() {
        opened.countDown();
    }

    void arriveAndWait() {
        arrived.countDown();
        await(opened);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Gave up waiting at a test gate");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
