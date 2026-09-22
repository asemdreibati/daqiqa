package io.daqiqa.ordering.application;

import static io.daqiqa.ordering.testsupport.FakeProductCatalog.BREAD;
import static io.daqiqa.ordering.testsupport.FakeProductCatalog.MILK;
import static org.assertj.core.api.Assertions.assertThat;

import io.daqiqa.contracts.events.OrderPlacedV1;
import io.daqiqa.kernel.OrderId;
import io.daqiqa.ordering.domain.PaymentMethod;
import io.daqiqa.ordering.testsupport.OrderingIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The headline guarantee of ADR-0008 under real concurrency: a client (or a flaky network) firing the same order many
 * times at once places exactly one order, holds its stock once and charges once, and every caller gets either that
 * order's response or a request to retry.
 */
class CheckoutConcurrencyTest extends OrderingIntegrationTest {

    private static final int REQUESTS = 20;

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
