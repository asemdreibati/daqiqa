package io.daqiqa.ordering.application;

import static io.daqiqa.ordering.testsupport.FakeProductCatalog.BREAD;
import static io.daqiqa.ordering.testsupport.FakeProductCatalog.MILK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.daqiqa.contracts.events.OrderPlacedV1;
import io.daqiqa.inventory.api.ReleaseReason;
import io.daqiqa.kernel.OrderId;
import io.daqiqa.ordering.domain.PaymentMethod;
import io.daqiqa.ordering.testsupport.FakeInventory.Reservation;
import io.daqiqa.ordering.testsupport.FakeInventory.Status;
import io.daqiqa.ordering.testsupport.FakePromiseTokenVerifier;
import io.daqiqa.ordering.testsupport.OrderingIntegrationTest;
import io.daqiqa.ordering.testsupport.ScriptedPaymentGateway.SimulatedCrash;
import io.daqiqa.promise.api.DeliveryTier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.json.AbstractJsonContentAssert;
import org.springframework.test.json.JsonContent;

/** Every checkout outcome of docs/design/ordering.md, through the real wiring, with the other modules faked. */
class CheckoutServiceTest extends OrderingIntegrationTest {

    @Autowired
    CheckoutService checkout;

    @Autowired
    AbandonedOrderSweeper sweeper;

    @Autowired
    MeterRegistry meters;

    @Test
    void confirmsACardOrderOnceItsPaymentIsApproved() {
        PlaceOrderCommand command = checkoutOf(PaymentMethod.CARD, line(MILK, 2), line(BREAD, 1));

        CheckoutResponse response = checkout.placeOrder(KEY, command);

        assertThat(response.replayed()).isFalse();
        assertThat(response.response().status()).isEqualTo(201);
        json(response).isLenientlyEqualTo("""
                {"orderId": "%s", "status": "CONFIRMED", "storeId": "DXB-MARINA", "customerId": "%s",
                 "promiseId": "%s", "tier": "EXPRESS_15", "etaMinutes": 12,
                 "placedAt": "2026-09-22T12:00:00Z", "deliverBy": "2026-09-22T12:12:00Z", "paymentMethod": "CARD",
                 "items": [
                   {"sku": "MILK-FULL-1L", "name": {"en": "Full Cream Milk 1L", "ar": "حليب كامل الدسم 1 لتر"},
                    "quantity": 2, "unitPrice": {"amountMinor": 695, "currency": "AED"},
                    "lineTotal": {"amountMinor": 1390, "currency": "AED"}},
                   {"sku": "BREAD-ARABIC-6PK", "name": {"en": "Arabic Bread × 6", "ar": "خبز عربي × 6"},
                    "quantity": 1, "unitPrice": {"amountMinor": 300, "currency": "AED"},
                    "lineTotal": {"amountMinor": 300, "currency": "AED"}}],
                 "total": {"amountMinor": 1690, "currency": "AED"}}""".formatted(
                        response.orderId(),
                        CUSTOMER,
                        promises.promiseOf(command.promiseToken()).promiseId()));
        assertThat(orderStatus(response.orderId())).contains("CONFIRMED");
        assertThat(reservationOf(response.orderId()).status()).isEqualTo(Status.CONFIRMED);
        assertThat(inventory.available(STORE, MILK.sku())).isEqualTo(8);
        assertThat(payments.authorizations()).containsExactly(response.orderId());
        assertThat(outboxEventTypes()).containsExactly(OrderPlacedV1.TYPE);
        assertThat(new JsonContent(outboxPayload())).isLenientlyEqualTo("""
                {"orderId": "%s", "storeId": "DXB-MARINA", "customerId": "%s", "deliveryTier": "EXPRESS_15",
                 "promisedEtaMinutes": 12, "promisedBy": "2026-09-22T12:12:00Z", "totalMinor": 1690, "currency": "AED",
                 "lines": [{"sku": "MILK-FULL-1L", "quantity": 2}, {"sku": "BREAD-ARABIC-6PK", "quantity": 1}]}""".formatted(response.orderId(), CUSTOMER));
    }

    @Test
    void confirmsACashOnDeliveryOrderWithoutAnyPayment() {
        CheckoutResponse response = checkout.placeOrder(KEY, checkoutOf(PaymentMethod.CASH_ON_DELIVERY, line(MILK, 1)));

        assertThat(response.response().status()).isEqualTo(201);
        json(response).extractingPath("$.paymentMethod").isEqualTo("CASH_ON_DELIVERY");
        assertThat(orderStatus(response.orderId())).contains("CONFIRMED");
        assertThat(reservationOf(response.orderId()).status()).isEqualTo(Status.CONFIRMED);
        assertThat(payments.authorizations()).isEmpty();
        assertThat(outboxEventTypes()).containsExactly(OrderPlacedV1.TYPE);
    }

    @Test
    void replaysTheStoredResponseToARetryOfTheSameRequest() {
        PlaceOrderCommand command = checkoutOf(PaymentMethod.CARD, line(MILK, 2), line(BREAD, 1));
        CheckoutResponse first = checkout.placeOrder(KEY, command);
        clock.advance(Duration.ofMinutes(10)); // the promise has long expired: the answer is stored, not recomputed

        CheckoutResponse retry = checkout.placeOrder(
                KEY,
                new PlaceOrderCommand(
                        CUSTOMER, command.promiseToken(), command.lines().reversed(), HOME, PaymentMethod.CARD));

        assertThat(retry).isEqualTo(new CheckoutResponse(first.orderId(), first.response(), true));
        assertExactlyOneOrderPlaced(first.orderId());
    }

    @Test
    void refusesToReuseAKeyForADifferentRequest() {
        checkout.placeOrder(KEY, checkoutOf(PaymentMethod.CARD, line(MILK, 1)));

        assertThatThrownBy(() -> checkout.placeOrder(KEY, checkoutOf(PaymentMethod.CARD, line(MILK, 2))))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(rows("ordering.customer_order")).isEqualTo(1);
    }

    @Test
    void makesARetryWaitWhileTheFirstRequestIsStillBeingProcessed() {
        PlaceOrderCommand command = checkoutOf(PaymentMethod.CARD, line(MILK, 1));
        payments.crash(); // the instance processing the first request stops answering, holding the claim
        assertThatThrownBy(() -> checkout.placeOrder(KEY, command)).isInstanceOf(SimulatedCrash.class);
        clock.advance(Duration.ofSeconds(15));

        assertThatThrownBy(() -> checkout.placeOrder(KEY, command))
                .isInstanceOfSatisfying(
                        CheckoutInProgressException.class,
                        inProgress -> assertThat(inProgress.retryAfter()).isEqualTo(Duration.ofSeconds(45)));
        assertThat(payments.authorizations()).hasSize(1);
    }

    @Test
    void aRetryTakesOverTheClaimOfACrashedInstanceAndFinishesTheSameOrder() {
        PlaceOrderCommand command = checkoutOf(PaymentMethod.CARD, line(MILK, 1));
        payments.crash();
        assertThatThrownBy(() -> checkout.placeOrder(KEY, command)).isInstanceOf(SimulatedCrash.class);
        OrderId orderId = payments.authorizations().getFirst();
        assertThat(orderStatus(orderId)).contains("AWAITING_PAYMENT");

        payments.approve();
        // Past the lease, and past the promise's validity: it was honoured when the order was created.
        clock.advance(Duration.ofMinutes(2));
        CheckoutResponse retry = checkout.placeOrder(KEY, command);

        assertThat(retry.orderId()).isEqualTo(orderId);
        assertThat(retry.replayed()).isFalse();
        assertThat(retry.response().status()).isEqualTo(201);
        assertThat(payments.authorizations())
                .as("the same order is authorized again: the provider deduplicates on the order id")
                .containsExactly(orderId, orderId);
        assertExactlyOneOrderPlaced(orderId);
    }

    @Test
    void releasesTheClaimAfterAnUnexpectedFailureSoTheSameKeyCanBeRetriedAtOnce() {
        PlaceOrderCommand command = checkoutOf(PaymentMethod.CARD, line(MILK, 1));
        inventory.failNextReserve(new IllegalStateException("connection reset"));

        assertThatThrownBy(() -> checkout.placeOrder(KEY, command)).hasMessage("connection reset");
        assertThat(rows("ordering.customer_order")).as("T1 rolled back").isZero();

        CheckoutResponse retry = checkout.placeOrder(KEY, command); // no waiting for the lease: it was ended

        assertThat(retry.response().status()).isEqualTo(201);
        assertExactlyOneOrderPlaced(retry.orderId());
    }

    @Test
    void aRetryAfterThePaymentProviderFailedContinuesTheOrderWhoseStockIsHeld() {
        PlaceOrderCommand command = checkoutOf(PaymentMethod.CARD, line(MILK, 1));
        payments.fail(new IllegalStateException("provider timed out"));
        assertThatThrownBy(() -> checkout.placeOrder(KEY, command)).hasMessage("provider timed out");
        OrderId orderId = payments.authorizations().getFirst();
        assertThat(inventory.available(STORE, MILK.sku())).isEqualTo(9);

        payments.approve();
        CheckoutResponse retry = checkout.placeOrder(KEY, command);

        assertThat(retry.orderId()).isEqualTo(orderId);
        assertThat(retry.response().status()).isEqualTo(201);
        assertThat(inventory.available(STORE, MILK.sku())).isEqualTo(9);
        assertExactlyOneOrderPlaced(orderId);
    }

    @Test
    void rejectsAnExpiredPromiseHoldingNothingAndStoresTheAnswer() {
        PlaceOrderCommand command = checkoutOf(PaymentMethod.CARD, line(MILK, 1));
        clock.advance(FakePromiseTokenVerifier.VALIDITY);

        CheckoutResponse response = checkout.placeOrder(KEY, command);

        assertRejected(response, 422, "PROMISE_EXPIRED");
        assertNothingHeldOrOrdered(response.orderId());
        assertReplayed(command, response);
    }

    @Test
    void rejectsAPromiseThatWasTamperedWith() {
        PlaceOrderCommand command = checkoutOf(PaymentMethod.CARD, line(MILK, 1));
        promises.tamper(command.promiseToken());

        CheckoutResponse response = checkout.placeOrder(KEY, command);

        assertRejected(response, 422, "PROMISE_INVALID");
        assertNothingHeldOrOrdered(response.orderId());
    }

    @Test
    void rejectsATokenWeNeverIssued() {
        PlaceOrderCommand command =
                new PlaceOrderCommand(CUSTOMER, "v1.forged.token", List.of(line(MILK, 1)), HOME, PaymentMethod.CARD);

        assertRejected(checkout.placeOrder(KEY, command), 422, "PROMISE_INVALID");
    }

    @Test
    void rejectsAPromiseMadeForAnotherBasket() {
        String token = promises.issue(STORE, DeliveryTier.EXPRESS_15, 12, List.of(line(MILK, 1)));
        PlaceOrderCommand command =
                new PlaceOrderCommand(CUSTOMER, token, List.of(line(MILK, 2)), HOME, PaymentMethod.CARD);

        CheckoutResponse response = checkout.placeOrder(KEY, command);

        assertRejected(response, 422, "PROMISE_CART_MISMATCH");
        assertNothingHeldOrOrdered(response.orderId());
    }

    @Test
    void rejectsSkusTheCatalogDoesNotSell() {
        PlaceOrderCommand command = checkoutOf(PaymentMethod.CARD, line(MILK, 1), line(BREAD, 1));
        catalog.reset();
        catalog.add(MILK);

        CheckoutResponse response = checkout.placeOrder(KEY, command);

        assertRejected(response, 422, "UNKNOWN_SKU");
        json(response).extractingPath("$.detail").asString().contains("BREAD-ARABIC-6PK");
        assertNothingHeldOrOrdered(response.orderId());
        assertReplayed(command, response);
    }

    @Test
    void answersOutOfStockWithTheShortagesAndCreatesNoOrder() {
        inventory.stock(STORE, MILK.sku(), 1);
        PlaceOrderCommand command = checkoutOf(PaymentMethod.CARD, line(MILK, 3), line(BREAD, 1));

        CheckoutResponse response = checkout.placeOrder(KEY, command);

        assertRejected(response, 409, "OUT_OF_STOCK");
        json(response)
                .extractingPath("$.shortages")
                .isEqualTo(List.of(java.util.Map.of("sku", "MILK-FULL-1L", "requested", 3, "available", 1)));
        assertNothingHeldOrOrdered(response.orderId());
        assertThat(payments.authorizations()).isEmpty();
        assertReplayed(command, response);
    }

    @Test
    void putsTheStockBackAndStoresTheDeclineWhenTheCardIsDeclined() {
        payments.decline("Insufficient funds");
        PlaceOrderCommand command = checkoutOf(PaymentMethod.CARD, line(MILK, 2));

        CheckoutResponse response = checkout.placeOrder(KEY, command);

        assertRejected(response, 402, "PAYMENT_DECLINED");
        json(response).extractingPath("$.detail").isEqualTo("Insufficient funds");
        assertThat(orderStatus(response.orderId())).contains("PAYMENT_FAILED");
        assertReleased(response.orderId(), ReleaseReason.PAYMENT_FAILED);
        assertThat(outboxEventTypes()).isEmpty();
        assertReplayed(command, response);
    }

    @Test
    void abandonsTheOrderWhenThePaymentCompletesAfterTheHoldExpired() {
        payments.whileInFlight(() -> clock.advance(Duration.ofMinutes(6)));
        PlaceOrderCommand command = checkoutOf(PaymentMethod.CARD, line(MILK, 2));

        CheckoutResponse response = checkout.placeOrder(KEY, command);

        assertRejected(response, 409, "HOLD_EXPIRED");
        assertThat(orderStatus(response.orderId())).contains("ABANDONED");
        assertReleased(response.orderId(), ReleaseReason.ORDER_ABANDONED);
        assertThat(outboxEventTypes()).isEmpty();
        assertReplayed(command, response);
    }

    @Test
    void answersHoldExpiredWhenTheSweeperAbandonedTheOrderWhileItsPaymentWasInFlight() {
        payments.whileInFlight(() -> {
            clock.advance(Duration.ofMinutes(6));
            assertThat(sweeper.sweep()).isEqualTo(1);
        });

        CheckoutResponse response = checkout.placeOrder(KEY, checkoutOf(PaymentMethod.CARD, line(MILK, 2)));

        assertRejected(response, 409, "HOLD_EXPIRED");
        assertThat(orderStatus(response.orderId())).contains("ABANDONED");
        assertReleased(response.orderId(), ReleaseReason.ORDER_ABANDONED);
        assertThat(outboxEventTypes()).isEmpty();
    }

    @Test
    void aRetryAfterTheSweeperAbandonedTheOrderIsToldTheHoldExpiredAndIsNotChargedAgain() {
        PlaceOrderCommand command = checkoutOf(PaymentMethod.CARD, line(MILK, 2));
        payments.crash();
        assertThatThrownBy(() -> checkout.placeOrder(KEY, command)).isInstanceOf(SimulatedCrash.class);
        clock.advance(Duration.ofMinutes(6));
        assertThat(sweeper.sweep()).isEqualTo(1);
        payments.approve();

        CheckoutResponse retry = checkout.placeOrder(KEY, command);

        assertRejected(retry, 409, "HOLD_EXPIRED");
        assertThat(orderStatus(retry.orderId())).contains("ABANDONED");
        assertThat(payments.authorizations())
                .as("no payment for an abandoned order")
                .hasSize(1);
        assertReleased(retry.orderId(), ReleaseReason.ORDER_ABANDONED);
        assertReplayed(command, retry);
    }

    @Test
    void forgetsAKeyOnceItExpiresSoItCanServeANewOrder() {
        CheckoutResponse first = checkout.placeOrder(KEY, checkoutOf(PaymentMethod.CARD, line(MILK, 1)));
        clock.advance(Duration.ofHours(24));

        CheckoutResponse second = checkout.placeOrder(KEY, checkoutOf(PaymentMethod.CARD, line(BREAD, 1)));

        assertThat(second.replayed()).isFalse();
        assertThat(second.response().status()).isEqualTo(201);
        assertThat(second.orderId()).isNotEqualTo(first.orderId());
        assertThat(rows("ordering.customer_order")).isEqualTo(2);
    }

    @Test
    void countsAndTimesCheckoutsByOutcome() {
        double confirmed = checkouts("confirmed");
        double replayed = checkouts("replayed");
        double reused = checkouts("idempotency_key_reused");
        PlaceOrderCommand command = checkoutOf(PaymentMethod.CARD, line(MILK, 1));

        checkout.placeOrder(KEY, command);
        checkout.placeOrder(KEY, command);
        assertThatThrownBy(() -> checkout.placeOrder(KEY, checkoutOf(PaymentMethod.CARD, line(MILK, 2))))
                .isInstanceOf(IdempotencyKeyReusedException.class);

        assertThat(checkouts("confirmed")).isEqualTo(confirmed + 1);
        assertThat(checkouts("replayed")).isEqualTo(replayed + 1);
        assertThat(checkouts("idempotency_key_reused")).isEqualTo(reused + 1);
        assertThat(meters.get("daqiqa.ordering.checkout")
                        .tag("outcome", "confirmed")
                        .timer()
                        .count())
                .isPositive();
    }

    private double checkouts(String outcome) {
        Counter counter =
                meters.find("daqiqa.ordering.checkouts").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    private void assertExactlyOneOrderPlaced(OrderId orderId) {
        assertThat(rows("ordering.customer_order")).isEqualTo(1);
        assertThat(orderStatus(orderId)).contains("CONFIRMED");
        assertThat(inventory.reservationsFor(orderId.toString())).hasSize(1);
        assertThat(outboxEventTypes()).containsExactly(OrderPlacedV1.TYPE);
    }

    private void assertNothingHeldOrOrdered(OrderId orderId) {
        assertThat(rows("ordering.customer_order")).isZero();
        assertThat(inventory.reservationsFor(orderId.toString())).isEmpty();
        assertThat(outboxEventTypes()).isEmpty();
    }

    private void assertReleased(OrderId orderId, ReleaseReason reason) {
        Reservation reservation = reservationOf(orderId);
        assertThat(reservation.status()).isEqualTo(Status.RELEASED);
        assertThat(reservation.releaseReason()).isEqualTo(reason);
        assertThat(inventory.available(STORE, MILK.sku()))
                .as("the stock is back on sale")
                .isEqualTo(10);
    }

    private void assertReplayed(PlaceOrderCommand command, CheckoutResponse original) {
        assertThat(checkout.placeOrder(KEY, command))
                .isEqualTo(new CheckoutResponse(original.orderId(), original.response(), true));
    }

    private static void assertRejected(CheckoutResponse response, int status, String code) {
        assertThat(response.response().status()).isEqualTo(status);
        json(response).isLenientlyEqualTo("""
                {"type": "urn:daqiqa:problem:%s", "status": %d, "code": "%s", "instance": "/v1/orders"}""".formatted(
                        code.toLowerCase(java.util.Locale.ROOT).replace('_', '-'), status, code));
    }

    private Reservation reservationOf(OrderId orderId) {
        return inventory.reservationsFor(orderId.toString()).getFirst();
    }

    private String outboxPayload() {
        return jdbc.sql("SELECT payload FROM messaging.outbox_event")
                .query(String.class)
                .single();
    }

    private static AbstractJsonContentAssert<?> json(CheckoutResponse response) {
        return assertThat(new JsonContent(response.response().body()));
    }
}
