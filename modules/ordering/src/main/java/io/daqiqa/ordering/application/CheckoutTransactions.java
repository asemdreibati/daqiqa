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
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The database transactions of a checkout. Each one first {@linkplain CheckoutClaims#hold holds the claim}, then
 * changes the order, its reservation, its event and its idempotency key together: whatever crashes when, they never
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
    private final CheckoutClaims claims;
    private final CheckoutResponseRenderer responses;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Duration hold;

    CheckoutTransactions(
            InventoryReservations inventory,
            OrderRepository orders,
            OutboxWriter outbox,
            CheckoutClaims claims,
            CheckoutResponseRenderer responses,
            PlatformTransactionManager transactionManager,
            Clock clock,
            OrderingProperties properties) {
        this.inventory = inventory;
        this.orders = orders;
        this.outbox = outbox;
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

        /** The order exists, its stock is held, and it waits for the card payment. */
        record AwaitingPayment(Order order) implements Opening {}

        /** An earlier attempt with this key already created the order: carry on from its state. */
        record Resume(Order order) implements Opening {}
    }

    /**
     * T1: holds the basket at the promised store and creates the order. The reservation joins this transaction (see
     * {@link InventoryReservations#reserve}), so stock is never held for an order that does not exist, or the reverse.
     * A refusal decided before T1 is stored here as well, under the claim, so that it cannot race an attempt that is
     * creating the order.
     */
    Opening open(Claim claim, PlaceOrderCommand command, Admission admission) {
        return inTransaction(claim, () -> {
            Optional<Order> existing = orders.find(claim.orderId());
            if (existing.isPresent()) {
                return new Opening.Resume(existing.get());
            }
            return switch (admission) {
                case Admission.Refused(CheckoutRejection rejection) -> new Opening.Settled(reject(claim, rejection));
                case Admission.Admitted(VerifiedPromise promise, PricingResult.Priced basket) ->
                    holdAndCreate(claim, command, promise, basket);
            };
        });
    }

    /**
     * T2: records the card payment's outcome. The order is locked after the claim, which serialises this with the
     * abandoned-order sweeper: whichever comes second sees what the first did.
     */
    Settlement settle(Claim claim, OrderId orderId, PaymentResult payment) {
        try {
            return inTransaction(claim, () -> {
                Order order = lock(orderId);
                if (order.status() != OrderStatus.AWAITING_PAYMENT) {
                    return concluded(claim, order);
                }
                return switch (payment) {
                    case PaymentResult.Approved approved -> {
                        // Throws once the hold has expired: the stock may be sold again, so a late payment must not
                        // take it. See the catch below.
                        inventory.confirm(order.reservationId());
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
            });
        } catch (ReservationNoLongerHeldException e) {
            // The payment outlived the hold. T2 rolled back, including anything the inventory wrote while refusing the
            // confirmation, so the outcome is decided afresh in its own transaction.
            return abandonAfterExpiredHold(claim, orderId);
        }
    }

    /** States the outcome for an order that an earlier attempt, or the sweeper, took past the payment step. */
    Settlement conclude(Claim claim, OrderId orderId) {
        return inTransaction(claim, () -> concluded(claim, lock(orderId)));
    }

    private Opening holdAndCreate(
            Claim claim, PlaceOrderCommand command, VerifiedPromise promise, PricingResult.Priced basket) {
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
        orders.insert(order);
        if (order.status() == OrderStatus.CONFIRMED) {
            // Cash on delivery: there is no payment to wait for, so the order is confirmed in T1.
            inventory.confirm(order.reservationId());
            return new Opening.Settled(confirmed(claim, order));
        }
        return new Opening.AwaitingPayment(order);
    }

    private Settlement abandonAfterExpiredHold(Claim claim, OrderId orderId) {
        return inTransaction(claim, () -> {
            Order order = lock(orderId);
            if (order.status() != OrderStatus.AWAITING_PAYMENT) {
                return concluded(claim, order);
            }
            orders.updateStatus(order.abandon(), OrderStatus.AWAITING_PAYMENT, now());
            // A no-op if the inventory already expired the hold; puts the stock back if it did not.
            inventory.release(order.reservationId(), ReleaseReason.ORDER_ABANDONED);
            return reject(claim, new CheckoutRejection.HoldExpired());
        });
    }

    private Settlement concluded(Claim claim, Order order) {
        if (order.status() == OrderStatus.ABANDONED) {
            // The sweeper abandoned the order while its payment was in flight.
            return reject(claim, new CheckoutRejection.HoldExpired());
        }
        // Confirming or failing an order completes its claim in the same transaction, and this one holds the claim.
        throw new IllegalStateException(
                "Order " + order.id() + " is " + order.status() + " while its checkout is still in progress");
    }

    private Settlement confirmed(Claim claim, Order order) {
        outbox.append(OrderPlacedEvents.orderPlaced(order, now()));
        return finish(claim, responses.orderPlaced(order), CheckoutOutcome.CONFIRMED);
    }

    private Settlement reject(Claim claim, CheckoutRejection rejection) {
        return finish(claim, responses.rejected(rejection), CheckoutOutcome.of(rejection));
    }

    private Settlement finish(Claim claim, StoredResponse response, CheckoutOutcome outcome) {
        claims.complete(claim, response);
        return new Settlement(response, outcome);
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
}
