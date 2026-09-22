package io.daqiqa.ordering.application;

import static io.daqiqa.ordering.testsupport.FakeProductCatalog.BREAD;
import static io.daqiqa.ordering.testsupport.FakeProductCatalog.MILK;
import static org.assertj.core.api.Assertions.assertThat;

import io.daqiqa.contracts.events.OrderPlacedV1;
import io.daqiqa.kernel.OrderId;
import io.daqiqa.ordering.domain.PaymentMethod;
import io.daqiqa.ordering.testsupport.Gate;
import io.daqiqa.ordering.testsupport.OrderingIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The headline guarantee of ADR-0008 under real concurrency: a client (or a flaky network) firing the same order many
 * times at once places exactly one order, holds its stock once and charges once, and every caller gets either that
 * order's response or a request to retry.
 */
class CheckoutConcurrencyTest extends OrderingIntegrationTest {

    private static final int REQUESTS = 20;
    private static final int KEYS_UNDER_ONE_PROMISE = 8;

    @Autowired
    CheckoutService checkout;

    @RepeatedTest(3)
    void twentyIdenticalRequestsInParallelPlaceExactlyOneOrder() throws Exception {
        PlaceOrderCommand command = checkoutOf(PaymentMethod.CARD, line(MILK, 2), line(BREAD, 1));
        // Keeps the winning request inside its checkout for a moment, so that the others arrive while it holds the
        // key. The assertions hold for any interleaving.
        payments.whileInFlight(() -> sleep(150));

        List<Object> answers = placeAllAtOnce(command);

        assertThat(answers)
                .allSatisfy(answer ->
                        assertThat(answer).isInstanceOfAny(CheckoutResponse.class, CheckoutInProgressException.class));
        List<CheckoutResponse> responses = responses(answers);
        List<CheckoutResponse> original =
                responses.stream().filter(response -> !response.replayed()).toList();
        assertThat(original).as("exactly one request did the work").hasSize(1);
        CheckoutResponse placed = original.getFirst();
        assertThat(placed.response().status()).isEqualTo(201);
        assertThat(responses)
                .as("any other answer is that response, replayed")
                .allSatisfy(response -> assertThat(response.orderId()).isEqualTo(placed.orderId()))
                .allSatisfy(response -> assertThat(response.response()).isEqualTo(placed.response()));

        List<Object> retries = placeAllAtOnce(command);

        assertThat(retries)
                .as("once it is settled, every concurrent retry replays it")
                .hasSize(REQUESTS)
                .allSatisfy(retry ->
                        assertThat(retry).isEqualTo(new CheckoutResponse(placed.orderId(), placed.response(), true)));
        assertExactlyOneOrder(placed.orderId());
    }

    @RepeatedTest(3)
    void ofManyKeysRacingUnderOnePromiseExactlyOnePlacesAnOrder() throws Exception {
        PlaceOrderCommand command = checkoutOf(PaymentMethod.CARD, line(MILK, 2), line(BREAD, 1));
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CheckoutResponse>> calls = new ArrayList<>();

        try (ExecutorService callers = Executors.newFixedThreadPool(KEYS_UNDER_ONE_PROMISE)) {
            for (int i = 0; i < KEYS_UNDER_ONE_PROMISE; i++) {
                String key = "promise-race-key-" + i;
                calls.add(callers.submit(() -> {
                    start.await();
                    return checkout.placeOrder(key, command);
                }));
            }
            start.countDown();
        }

        List<CheckoutResponse> responses = new ArrayList<>();
        for (Future<CheckoutResponse> call : calls) {
            responses.add(call.get(30, TimeUnit.SECONDS));
        }
        List<CheckoutResponse> placed = responses.stream()
                .filter(response -> response.response().status() == 201)
                .toList();
        assertThat(placed).hasSize(1);
        assertThat(responses)
                .filteredOn(response -> response.response().status() != 201)
                .hasSize(KEYS_UNDER_ONE_PROMISE - 1)
                .allSatisfy(refused -> {
                    assertThat(refused.response().status()).isEqualTo(409);
                    assertThat(refused.response().body()).contains("PROMISE_ALREADY_USED");
                    assertThat(inventory.reservationsFor(refused.orderId().toString()))
                            .as("a losing checkout's hold was rolled back with it")
                            .isEmpty();
                });
        assertExactlyOneOrder(placed.getFirst().orderId());
    }

    @Test
    void aCheckoutUnderAPromiseThatIsBeingOrderedWaitsAndIsToldThePromiseIsUsed() throws Exception {
        PlaceOrderCommand command = checkoutOf(PaymentMethod.CARD, line(MILK, 2), line(BREAD, 1));
        Gate reserving = inventory.holdNextReservation();

        try (ExecutorService attempts = Executors.newFixedThreadPool(2)) {
            Future<CheckoutResponse> first = attempts.submit(() -> checkout.placeOrder("promise-race-key-a", command));
            reserving.awaitArrival(); // A holds the promise lock and is about to hold the stock
            Future<CheckoutResponse> second = attempts.submit(() -> checkout.placeOrder("promise-race-key-b", command));
            awaitACheckoutWaitingForThePromiseLock(); // B queues behind A instead of racing it for the stock
            reserving.open();

            CheckoutResponse placed = first.get(30, TimeUnit.SECONDS);
            CheckoutResponse refused = second.get(30, TimeUnit.SECONDS);

            assertThat(placed.response().status()).isEqualTo(201);
            assertThat(refused.response().status()).isEqualTo(409);
            assertThat(refused.response().body()).contains("PROMISE_ALREADY_USED");
            assertThat(inventory.reservationsFor(refused.orderId().toString()))
                    .as("B never held stock")
                    .isEmpty();
            assertExactlyOneOrder(placed.orderId());
        }
    }

    /** Polls the lock manager (a live view, unlike pg_stat_activity) until a session waits on an advisory lock. */
    private void awaitACheckoutWaitingForThePromiseLock() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (jdbc.sql("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted")
                        .query(Long.class)
                        .single()
                == 0) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("no checkout started waiting for the promise lock");
            }
            sleep(10);
        }
    }

    private void assertExactlyOneOrder(OrderId orderId) {
        assertThat(rows("ordering.customer_order")).isEqualTo(1);
        assertThat(inventory.reservationsFor(orderId.toString())).hasSize(1);
        assertThat(inventory.available(STORE, MILK.sku())).isEqualTo(8);
        assertThat(payments.authorizations()).containsExactly(orderId);
        assertThat(outboxEventTypes()).containsExactly(OrderPlacedV1.TYPE);
    }

    /** Sends the same request from {@link #REQUESTS} threads released at the same instant. */
    private List<Object> placeAllAtOnce(PlaceOrderCommand command) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> calls = new ArrayList<>();
        try (ExecutorService callers = Executors.newFixedThreadPool(REQUESTS)) {
            for (int i = 0; i < REQUESTS; i++) {
                calls.add(callers.submit(() -> {
                    start.await();
                    try {
                        return checkout.placeOrder(KEY, command);
                    } catch (CheckoutInProgressException inProgress) {
                        return inProgress;
                    }
                }));
            }
            start.countDown();
        }
        List<Object> answers = new ArrayList<>();
        for (Future<Object> call : calls) {
            answers.add(call.get(30, TimeUnit.SECONDS));
        }
        return answers;
    }

    private static List<CheckoutResponse> responses(List<Object> answers) {
        return answers.stream()
                .filter(CheckoutResponse.class::isInstance)
                .map(CheckoutResponse.class::cast)
                .toList();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
