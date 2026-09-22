package io.daqiqa.ordering.application;

import io.daqiqa.inventory.api.InventoryReservations;
import io.daqiqa.inventory.api.ReleaseReason;
import io.daqiqa.inventory.api.ReservationNoLongerHeldException;
import io.daqiqa.inventory.api.ReservationRequest;
import io.daqiqa.inventory.api.ReservationResult;
import io.daqiqa.inventory.api.Shortage;
import io.daqiqa.kernel.OrderId;
import io.daqiqa.kernel.ReservationId;
import io.daqiqa.messaging.OutboxWriter;
import io.daqiqa.ordering.application.CheckoutRejection.HoldExpired;
import io.daqiqa.ordering.domain.Order;
import io.daqiqa.ordering.domain.OrderRepository;
import io.daqiqa.ordering.domain.OrderStatus;
import io.daqiqa.ordering.domain.PricingResult;
import io.daqiqa.ordering.domain.PromiseTerms;
import io.daqiqa.promise.api.VerifiedPromise;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The database transactions of a checkout. Each one first {@linkplain CheckoutClaims#hold holds the claim}, then
 * changes the order, its reservation, its events and its idempotency key together: whatever crashes when, they never
 * disagree, and an attempt that stalled past its lease cannot overrule what a newer attempt with the same key decided.
 *
 * <p><b>Why two transactions around the payment rather than one.</b> A card authorization is a remote call that can
 * take seconds. Holding row locks and a pooled connection across it would let one slow provider exhaust the pool and
 * stall every checkout touching the same items. So T1 holds the stock and creates the order, the payment runs with no
 * transaction open, and T2 records its outcome. If the process dies in between, the order waits with its stock held
 * until a retry resumes it or the {@link AbandonedOrderSweeper} gives up on it.
 */
@Component
class CheckoutTransactions {

    private final InventoryReservations inventory;
    private final OrderRepository orders;
    private final OutboxWriter outbox;
    private final OrderAbandonment abandonment;
    private final CheckoutClaims claims;
    private final CheckoutResponseRenderer responses;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Duration hold;

    CheckoutTransactions(
            InventoryReservations inventory,
            OrderRepository orders,
            OutboxWriter outbox,
            OrderAbandonment abandonment,
            CheckoutClaims claims,
            CheckoutResponseRenderer responses,
            PlatformTransactionManager transactionManager,
            Clock clock,
            OrderingProperties properties) {
        this.inventory = inventory;
        this.orders = orders;
        this.outbox = outbox;
        this.abandonment = abandonment;
        this.claims = claims;
        this.responses = responses;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.hold = properties.hold();
    }

    /** What the checks before T1 concluded; they need no locks, so they run outside any transaction. */
    sealed interface Admission {

        record Admitted(VerifiedPromise promise, PricingResult.Priced basket) implements Admission {}

        record Refused(CheckoutRejection rejection) implements Admission {}
    }

    /** What T1 decided. */
    sealed interface Opening {

        /** The checkout is over: confirmed (cash on delivery) or rejected. */
        record Settled(Settlement settlement) implements Opening {}

        /** The order exists, its stock is still held, and it waits for the card payment. */
        record AwaitingPayment(Order order) implements Opening {}
    }

    /**
     * T1: holds the basket at the promised store and creates the order. The reservation joins this transaction (see
     * {@link InventoryReservations#reserve}), so stock is never held for an order that does not exist, or the reverse.
     * A refusal decided before T1 is stored here as well, under the claim, so that it cannot race an attempt that is
     * creating the order.
     */
    Opening open(Claim claim, PlaceOrderCommand command, Admission admission) {
        try {
            return inTransaction(claim, () -> openHeld(claim, command, admission));
        } catch (PromiseAlreadyUsedException e) {
            // T1 rolled back, the stock it held included. Store the answer under the claim, unless another attempt
            // with this key has created the order meanwhile.
            Admission used = new Admission.Refused(new CheckoutRejection.PromiseAlreadyUsed());
            return inTransaction(claim, () -> openHeld(claim, command, used));
        }
    }

    /**
     * T2: records the card payment's outcome. The order is locked after the claim, which serialises this with the
     * abandoned-order sweeper: whichever comes second sees what the first did.
     */
    Settlement settle(Claim claim, OrderId orderId, PaymentResult payment) {
        // Set when the inventory refuses the confirmation; read only if T2 then fails to commit.
        AtomicBoolean confirmationRefused = new AtomicBoolean();
        try {
            return inTransaction(claim, () -> {
                Order order = lock(orderId);
                return switch (order.status()) {
                    case AWAITING_PAYMENT -> recordPayment(claim, order, payment, confirmationRefused);
                    // The sweeper abandoned it while the payment was in flight, and announced it.
                    case ABANDONED -> reject(claim, new HoldExpired(paymentOutcome(payment)));
                    case CONFIRMED, PAYMENT_FAILED -> throw settledWhileInProgress(order);
                };
            });
        } catch (UnexpectedRollbackException e) {
            if (!confirmationRefused.get()) {
                throw e;
            }
            // The inventory marked T2 rollback-only while refusing the late confirmation, which its contract says it
            // does not do; T2's writes, the abandonment included, are lost. Decide again in a transaction of its own.
            return abandonAfterRefusedConfirmation(claim, orderId);
        }
    }

    private Opening openHeld(Claim claim, PlaceOrderCommand command, Admission admission) {
        Optional<Order> existing = orders.lock(claim.orderId());
        if (existing.isPresent()) {
            return continueExisting(claim, existing.get());
        }
        return switch (admission) {
            case Admission.Refused(CheckoutRejection rejection) -> new Opening.Settled(reject(claim, rejection));
            case Admission.Admitted(VerifiedPromise promise, PricingResult.Priced basket) ->
                holdAndCreate(claim, command, promise, basket);
        };
    }

    /**
     * An earlier attempt with this key created the order and then stopped (this attempt took its claim over): carry on
     * from the order's status. A hold that has expired meanwhile ends the checkout here rather than after the payment,
     * which would authorize the card for an order that cannot be confirmed (the sweeper may simply not have run yet).
     */
    private Opening continueExisting(Claim claim, Order order) {
        return switch (order.status()) {
            case AWAITING_PAYMENT -> {
                if (holdExpired(order)) {
                    abandonment.abandon(order, AbandonReason.HOLD_EXPIRED);
                    yield new Opening.Settled(reject(claim, new HoldExpired(HoldExpired.Payment.NOT_COMPLETED)));
                }
                yield new Opening.AwaitingPayment(order);
            }
            // The sweeper got there first.
            case ABANDONED -> new Opening.Settled(reject(claim, new HoldExpired(HoldExpired.Payment.NOT_COMPLETED)));
            case CONFIRMED, PAYMENT_FAILED -> throw settledWhileInProgress(order);
        };
    }

    private Opening holdAndCreate(
            Claim claim, PlaceOrderCommand command, VerifiedPromise promise, PricingResult.Priced basket) {
        // Checked before holding any stock, so a second order under a used promise is told so, not that the first
        // order's items are out of stock. The promise lock makes that answer deterministic when checkouts race: a
        // latecomer waits for the first checkout's transaction and then sees its order. The insert below stays as a
        // second line of defence (the partial unique index).
        orders.lockPromise(promise.promiseId());
        if (orders.promiseInUse(promise.promiseId())) {
            return new Opening.Settled(reject(claim, new CheckoutRejection.PromiseAlreadyUsed()));
        }
        ReservationResult reservation = inventory.reserve(
                new ReservationRequest(promise.storeId(), claim.orderId().toString(), command.lines(), hold));
        return switch (reservation) {
            case ReservationResult.Rejected(List<Shortage> shortages) ->
                new Opening.Settled(reject(claim, new CheckoutRejection.OutOfStock(shortages)));
            case ReservationResult.Reserved reserved ->
                create(claim, command, promise, basket, reserved.reservationId());
        };
    }

    private Opening create(
            Claim claim,
            PlaceOrderCommand command,
            VerifiedPromise promise,
            PricingResult.Priced basket,
            ReservationId reservationId) {
        Order order = Order.place(
                claim.orderId(),
                command.customerId(),
                new PromiseTerms(
                        promise.promiseId(), promise.storeId(), promise.tier().name(), promise.etaMinutes()),
                now(),
                command.deliveryLocation(),
                command.paymentMethod(),
                reservationId,
                basket);
        if (!orders.insertUnlessPromiseInUse(order)) {
            throw new PromiseAlreadyUsedException();
        }
        if (order.status() == OrderStatus.CONFIRMED) {
            // Cash on delivery: there is no payment to wait for, so the order is confirmed in T1.
            inventory.confirm(order.reservationId());
            return new Opening.Settled(confirmed(claim, order));
        }
        return new Opening.AwaitingPayment(order);
    }

    private Settlement recordPayment(
            Claim claim, Order order, PaymentResult payment, AtomicBoolean confirmationRefused) {
        return switch (payment) {
            case PaymentResult.Approved approved -> {
                try {
                    inventory.confirm(order.reservationId());
                } catch (ReservationNoLongerHeldException e) {
                    // Approved only after the hold expired: the stock may be sold again, so the late payment must not
                    // take it. The inventory has expired the hold for good (it leaves this transaction intact).
                    confirmationRefused.set(true);
                    abandonment.abandon(order, AbandonReason.PAID_AFTER_HOLD_EXPIRED);
                    yield reject(claim, new HoldExpired(HoldExpired.Payment.AUTHORIZED));
                }
                Order confirmed = order.confirmPayment();
                orders.updateStatus(confirmed, OrderStatus.AWAITING_PAYMENT, now());
                yield confirmed(claim, confirmed);
            }
            case PaymentResult.Declined(String reason) -> {
                inventory.release(order.reservationId(), ReleaseReason.PAYMENT_FAILED);
                orders.updateStatus(order.failPayment(), OrderStatus.AWAITING_PAYMENT, now());
                yield reject(claim, new CheckoutRejection.PaymentDeclined(reason));
            }
        };
    }

    /** T3, the fallback of a T2 that could not commit after the inventory refused a late confirmation. */
    private Settlement abandonAfterRefusedConfirmation(Claim claim, OrderId orderId) {
        return inTransaction(claim, () -> {
            Order order = lock(orderId);
            switch (order.status()) {
                case AWAITING_PAYMENT -> abandonment.abandon(order, AbandonReason.PAID_AFTER_HOLD_EXPIRED);
                case ABANDONED -> {
                    // The sweeper abandoned it between T2's rollback and now, and announced it.
                }
                case CONFIRMED, PAYMENT_FAILED -> throw settledWhileInProgress(order);
            }
            return reject(claim, new HoldExpired(HoldExpired.Payment.AUTHORIZED));
        });
    }

    private Settlement confirmed(Claim claim, Order order) {
        outbox.append(OrderEvents.placed(order, now()));
        return finish(claim, responses.orderPlaced(order), CheckoutOutcome.CONFIRMED);
    }

    private Settlement reject(Claim claim, CheckoutRejection rejection) {
        return finish(claim, responses.rejected(rejection), CheckoutOutcome.of(rejection));
    }

    private Settlement finish(Claim claim, StoredResponse response, CheckoutOutcome outcome) {
        claims.complete(claim, response);
        return new Settlement(response, outcome);
    }

    /** Whether the order's stock hold has run out: {@code daqiqa.ordering.hold} after it was placed. */
    private boolean holdExpired(Order order) {
        return !clock.instant().isBefore(order.placedAt().plus(hold));
    }

    private Order lock(OrderId id) {
        return orders.lock(id).orElseThrow(() -> new IllegalStateException("Order " + id + " does not exist"));
    }

    /** Runs {@code body} in a new transaction that starts by holding the claim. */
    private <T> T inTransaction(Claim claim, Supplier<T> body) {
        return Objects.requireNonNull(transaction.execute(status -> {
            claims.hold(claim);
            return body.get();
        }));
    }

    /**
     * Milliseconds: PostgreSQL (microseconds) and JSON both keep them exactly, so the order in the checkout response
     * is identical to the one a later {@code GET} returns.
     */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static HoldExpired.Payment paymentOutcome(PaymentResult payment) {
        return switch (payment) {
            case PaymentResult.Approved approved -> HoldExpired.Payment.AUTHORIZED;
            case PaymentResult.Declined declined -> HoldExpired.Payment.DECLINED;
        };
    }

    /** Confirming or failing an order completes its claim in the same transaction, and the caller holds the claim. */
    private static IllegalStateException settledWhileInProgress(Order order) {
        return new IllegalStateException(
                "Order " + order.id() + " is " + order.status() + " while its checkout is still in progress");
    }
}
