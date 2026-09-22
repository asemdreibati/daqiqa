package io.daqiqa.ordering.application;

import io.daqiqa.inventory.api.Shortage;
import io.daqiqa.kernel.Sku;
import io.daqiqa.promise.api.InvalidPromiseException;
import java.util.List;
import java.util.Objects;

/**
 * Why a checkout ended without an order. Each is a deterministic business outcome: stored on the idempotency key and
 * replayed to every retry.
 */
public sealed interface CheckoutRejection {

    /** The promise token cannot be honoured: expired, altered, not ours, or issued for another basket. */
    record PromiseRejected(InvalidPromiseException.Reason reason) implements CheckoutRejection {

        public PromiseRejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** Another order, placed with another idempotency key, already uses this promise. */
    record PromiseAlreadyUsed() implements CheckoutRejection {}

    /** The basket contains SKUs the catalog does not sell. */
    record UnknownSkus(List<Sku> skus) implements CheckoutRejection {

        public UnknownSkus {
            skus = List.copyOf(skus);
        }
    }

    /** Some items sold out after the promise was made; nothing was held. */
    record OutOfStock(List<Shortage> shortages) implements CheckoutRejection {

        public OutOfStock {
            shortages = List.copyOf(shortages);
        }
    }

    /** The card was declined; the stock went back on sale. */
    record PaymentDeclined(String reason) implements CheckoutRejection {

        public PaymentDeclined {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * The stock hold expired before the payment completed, so the items may be sold to someone else: the order was
     * abandoned and its stock released.
     *
     * @param payment what became of the card payment, which decides what the customer is told
     */
    record HoldExpired(Payment payment) implements CheckoutRejection {

        public HoldExpired {
            Objects.requireNonNull(payment, "payment");
        }

        public enum Payment {
            /** It did not complete within the hold: never attempted again after a restart, or its outcome was lost. */
            NOT_COMPLETED,
            /** The card was declined. */
            DECLINED,
            /** The card was authorized, but only after the hold had expired. */
            AUTHORIZED
        }
    }
}
