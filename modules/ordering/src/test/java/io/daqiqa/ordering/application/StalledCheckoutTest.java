package io.daqiqa.ordering.application;

import static io.daqiqa.ordering.testsupport.FakeProductCatalog.MILK;
import static org.assertj.core.api.Assertions.assertThat;

import io.daqiqa.contracts.events.OrderAbandonedV1;
import io.daqiqa.contracts.events.OrderPlacedV1;
import io.daqiqa.inventory.api.ReleaseReason;
import io.daqiqa.ordering.domain.PaymentMethod;
import io.daqiqa.ordering.testsupport.FakeInventory.Reservation;
import io.daqiqa.ordering.testsupport.FakeInventory.Status;
import io.daqiqa.ordering.testsupport.Gate;
import io.daqiqa.ordering.testsupport.OrderingIntegrationTest;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.json.JsonContent;

/**
 * An attempt that stalls past its lease (a long GC pause, a slow provider, a partition) while a retry takes its claim
 * over. Every transaction of a checkout first locks the claim, so whoever comes second sees what the first decided:
 * the stalled attempt never overrules the retry, and answers with the retry's stored response.
 */
class StalledCheckoutTest extends OrderingIntegrationTest {

    private static final Duration PAST_THE_LEASE = Duration.ofSeconds(61);

    @Autowired
    CheckoutService checkout;

    @Autowired
    AbandonedOrderSweeper sweeper;

    @Test
    void anAttemptStalledInThePaymentRepliesWithTheOrderTheRetryConfirmed() throws Exception {
        PlaceOrderCommand command = checkoutOf(PaymentMethod.CARD, line(MILK, 1));
        Gate payment = payments.holdNextAuthorization();

        try (ExecutorService attemptA = Executors.newSingleThreadExecutor()) {
            Future<CheckoutResponse> stalled = attemptA.submit(() -> checkout.placeOrder(KEY, command));
            payment.awaitArrival(); // A created the order and waits for the provider
            clock.advance(PAST_THE_LEASE); // A is presumed dead

            CheckoutResponse retry = checkout.placeOrder(KEY, command); // B continues the order and confirms it
            payment.open();
            CheckoutResponse late = stalled.get(30, TimeUnit.SECONDS);

            assertThat(retry.response().status()).isEqualTo(201);
            assertThat(retry.replayed()).isFalse();
            assertThat(late)
                    .as("A's own confirmation lost the claim: it answers with B's bytes")
                    .isEqualTo(new CheckoutResponse(retry.orderId(), retry.response(), true));
            assertThat(payments.authorizations()).containsExactly(retry.orderId(), retry.orderId());
            assertThat(rows("ordering.customer_order")).isEqualTo(1);
            assertThat(orderStatus(retry.orderId())).contains("CONFIRMED");
            assertThat(inventory.reservationsFor(retry.orderId().toString())).hasSize(1);
            assertThat(outboxEventTypes()).containsExactly(OrderPlacedV1.TYPE);
        }
    }

    @Test
    void anAttemptStalledBeforeT1RepliesWithTheRejectionTheRetryStored() throws Exception {
        PlaceOrderCommand command = checkoutOf(PaymentMethod.CARD, line(MILK, 1));
        Gate pricing = catalog.holdNextLookup();

        try (ExecutorService attemptA = Executors.newSingleThreadExecutor()) {
            Future<CheckoutResponse> stalled = attemptA.submit(() -> checkout.placeOrder(KEY, command));
            pricing.awaitArrival(); // A verified the promise and is pricing the basket
            clock.advance(Duration.ofMinutes(2)); // A's lease and the promise have both run out

            CheckoutResponse retry = checkout.placeOrder(KEY, command); // B: PROMISE_EXPIRED, stored
            pricing.open();
            CheckoutResponse late = stalled.get(30, TimeUnit.SECONDS);

            assertThat(retry.response().status()).isEqualTo(422);
            assertThat(new JsonContent(retry.response().body()))
                    .extractingPath("$.code")
                    .isEqualTo("PROMISE_EXPIRED");
            assertThat(late)
                    .as("A may not create the order after B answered: it answers with B's bytes")
                    .isEqualTo(new CheckoutResponse(retry.orderId(), retry.response(), true));
            assertThat(rows("ordering.customer_order")).isZero();
            assertThat(inventory.reservationsFor(retry.orderId().toString())).isEmpty();
            assertThat(payments.authorizations()).isEmpty();
        }
    }

    @Test
    void theFallbackAfterARolledBackConfirmationRespectsTheSweeperThatAbandonedTheOrderMeanwhile() {
        AtomicInteger sweptBetween = new AtomicInteger(-1);
        // T2 is rolled back; before T3 starts, the sweeper abandons the order (from another thread, as it would).
        inventory.refuseLateConfirmationsByRollingBackTheCaller(
                () -> sweptBetween.set(onAnotherThread(sweeper::sweep)));
        payments.whileInFlight(() -> clock.advance(Duration.ofMinutes(6)));

        CheckoutResponse response = checkout.placeOrder(KEY, checkoutOf(PaymentMethod.CARD, line(MILK, 2)));

        assertThat(sweptBetween).hasValue(1);
        assertThat(response.response().status()).isEqualTo(409);
        assertThat(new JsonContent(response.response().body()))
                .extractingPath("$.code")
                .isEqualTo("HOLD_EXPIRED");
        assertThat(orderStatus(response.orderId())).contains("ABANDONED");
        Reservation reservation =
                inventory.reservationsFor(response.orderId().toString()).getFirst();
        assertThat(reservation.status()).isEqualTo(Status.RELEASED);
        assertThat(reservation.releaseReason()).isEqualTo(ReleaseReason.ORDER_ABANDONED);
        assertThat(outboxEventTypes())
                .as("abandoned and announced once, by the sweeper")
                .containsExactly(OrderAbandonedV1.TYPE);
        assertThat(new JsonContent(jdbc.sql("SELECT payload FROM messaging.outbox_event")
                        .query(String.class)
                        .single()))
                .extractingPath("$.reason")
                .isEqualTo("HOLD_EXPIRED");
    }

    /** Runs {@code work} on a thread of its own, so it cannot join the transaction that is completing on this one. */
    private static int onAnotherThread(IntSupplier work) {
        AtomicInteger result = new AtomicInteger(-1);
        Thread thread = Thread.ofPlatform().start(() -> result.set(work.getAsInt()));
        try {
            thread.join(Duration.ofSeconds(30));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return result.get();
    }
}
