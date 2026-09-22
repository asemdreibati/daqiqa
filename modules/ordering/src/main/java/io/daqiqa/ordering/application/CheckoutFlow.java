package io.daqiqa.ordering.application;

import io.daqiqa.catalog.api.Product;
import io.daqiqa.catalog.api.ProductCatalog;
import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.ordering.application.CheckoutTransactions.Admission;
import io.daqiqa.ordering.application.CheckoutTransactions.Opening;
import io.daqiqa.ordering.domain.BasketPricing;
import io.daqiqa.ordering.domain.Order;
import io.daqiqa.ordering.domain.OrderStatus;
import io.daqiqa.ordering.domain.PricingResult;
import io.daqiqa.ordering.domain.ProductPrice;
import io.daqiqa.promise.api.InvalidPromiseException;
import io.daqiqa.promise.api.PromiseTokenVerifier;
import io.daqiqa.promise.api.VerifiedPromise;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * One checkout attempt for a claimed key: verify the promise and price the basket (no locks needed), T1 holds the
 * stock and creates the order, the card is authorized with no transaction open, T2 records the outcome.
 *
 * <p>An attempt whose order already exists (a retry that took over the claim of a dead instance) learns so in T1 and
 * resumes from the order's state. The promise's verdict is ignored then: it may well have expired by now, and it was
 * honoured when the order was created.
 */
@Component
class CheckoutFlow {

    private final PromiseTokenVerifier promises;
    private final ProductCatalog catalog;
    private final PaymentGateway payments;
    private final CheckoutClaims claims;
    private final CheckoutTransactions transactions;

    CheckoutFlow(
            PromiseTokenVerifier promises,
            ProductCatalog catalog,
            PaymentGateway payments,
            CheckoutClaims claims,
            CheckoutTransactions transactions) {
        this.promises = promises;
        this.catalog = catalog;
        this.payments = payments;
        this.claims = claims;
        this.transactions = transactions;
    }

    Settlement run(Claim claim, PlaceOrderCommand command) {
        try {
            return switch (transactions.open(claim, command, admit(command))) {
                case Opening.Settled(Settlement settlement) -> settlement;
                case Opening.AwaitingPayment(Order order) -> pay(claim, order);
                case Opening.Resume(Order order) -> resume(claim, order);
            };
        } catch (ClaimSettledException e) {
            return new Settlement(claims.storedResponse(claim), CheckoutOutcome.REPLAYED);
        }
    }

    private Admission admit(PlaceOrderCommand command) {
        VerifiedPromise promise;
        try {
            promise = promises.verify(command.promiseToken(), command.lines(), command.deliveryLocation());
        } catch (InvalidPromiseException e) {
            return new Admission.Refused(new CheckoutRejection.PromiseRejected(e.reason()));
        }
        return switch (BasketPricing.price(command.lines(), prices(command.lines()))) {
            case PricingResult.Priced basket -> new Admission.Admitted(promise, basket);
            case PricingResult.UnknownSkus(List<Sku> skus) ->
                new Admission.Refused(new CheckoutRejection.UnknownSkus(skus));
        };
    }

    /** Today's catalog names and prices for the basket, in one round trip; they become the order's snapshots. */
    private Map<Sku, ProductPrice> prices(List<SkuQuantity> lines) {
        List<Sku> skus = lines.stream().map(SkuQuantity::sku).toList();
        return catalog.findAll(skus).values().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Product::sku, product -> new ProductPrice(product.sku(), product.name(), product.price())));
    }

    private Settlement resume(Claim claim, Order order) {
        return order.status() == OrderStatus.AWAITING_PAYMENT
                ? pay(claim, order)
                : transactions.conclude(claim, order.id());
    }

    /** Outside any transaction on purpose: see {@link CheckoutTransactions}. */
    private Settlement pay(Claim claim, Order order) {
        PaymentResult payment = payments.authorize(order.id(), order.total());
        return transactions.settle(claim, order.id(), payment);
    }
}
