package io.daqiqa.ordering.adapter.out.payment;

import io.daqiqa.kernel.Money;
import io.daqiqa.kernel.OrderId;
import io.daqiqa.ordering.application.OrderingProperties;
import io.daqiqa.ordering.application.PaymentGateway;
import io.daqiqa.ordering.application.PaymentResult;
import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * v1's payment gateway: approves every amount up to {@code daqiqa.ordering.payment.decline-above} (default 1,000.00)
 * and declines the rest, after an optional {@code latency}. Deterministic, so tests and demos can choose their
 * outcome by the basket's value; and idempotent by order id as the port requires, since the same order always has the
 * same amount and gets the same answer.
 */
@Component
class SimulatedPaymentGateway implements PaymentGateway {

    private final BigDecimal declineAbove;
    private final Duration latency;

    SimulatedPaymentGateway(OrderingProperties properties) {
        this.declineAbove = properties.payment().declineAbove();
        this.latency = properties.payment().latency();
    }

    @Override
    public PaymentResult authorize(OrderId orderId, Money amount) {
        simulateLatency();
        if (amount.toMajorUnits().compareTo(declineAbove) > 0) {
            return new PaymentResult.Declined("The card issuer declined the payment.");
        }
        return new PaymentResult.Approved();
    }

    private void simulateLatency() {
        if (latency.isZero()) {
            return;
        }
        try {
            Thread.sleep(latency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the simulated payment provider", e);
        }
    }
}
