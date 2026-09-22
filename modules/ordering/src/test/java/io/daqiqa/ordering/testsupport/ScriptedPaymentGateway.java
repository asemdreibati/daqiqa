package io.daqiqa.ordering.testsupport;

import io.daqiqa.kernel.Money;
import io.daqiqa.kernel.OrderId;
import io.daqiqa.ordering.application.PaymentGateway;
import io.daqiqa.ordering.application.PaymentResult;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * A payment provider the test scripts: what it answers, and what happens while the payment is "in flight" (the clock
 * moving on, the sweeper running, a crash). Records every authorization in order.
 */
public final class ScriptedPaymentGateway implements PaymentGateway {

    private final List<OrderId> authorizations = new CopyOnWriteArrayList<>();
    private volatile Runnable whileInFlight = () -> {};
    private volatile Supplier<PaymentResult> answer = ScriptedPaymentGateway::approved;

    public void approve() {
        answer = ScriptedPaymentGateway::approved;
    }

    public void decline(String reason) {
        answer = () -> new PaymentResult.Declined(reason);
    }

    /** The provider fails with an exception, as a timeout or a 5xx would. */
    public void fail(RuntimeException failure) {
        answer = () -> {
            throw failure;
        };
    }

    /** The instance "dies" mid-payment: an {@link Error} no checkout code handles, like a killed process. */
    public void crash() {
        answer = () -> {
            throw new SimulatedCrash();
        };
    }

    public void whileInFlight(Runnable action) {
        whileInFlight = action;
    }

    public List<OrderId> authorizations() {
        return List.copyOf(authorizations);
    }

    public void reset() {
        authorizations.clear();
        whileInFlight = () -> {};
        approve();
    }

    @Override
    public PaymentResult authorize(OrderId orderId, Money amount) {
        authorizations.add(orderId);
        whileInFlight.run();
        return answer.get();
    }

    private static PaymentResult approved() {
        return new PaymentResult.Approved();
    }

    /** Stands for the process dying: checkout neither handles nor survives it. */
    public static final class SimulatedCrash extends Error {

        SimulatedCrash() {
            super("simulated crash while the payment was in flight");
        }
    }
}
