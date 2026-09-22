package io.daqiqa.ordering.adapter.out.payment;

import static org.assertj.core.api.Assertions.assertThat;

import io.daqiqa.kernel.Money;
import io.daqiqa.kernel.OrderId;
import io.daqiqa.ordering.application.OrderingProperties;
import io.daqiqa.ordering.application.PaymentResult;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SimulatedPaymentGatewayTest {

    private final SimulatedPaymentGateway gateway = new SimulatedPaymentGateway(new OrderingProperties(
            Duration.ofMinutes(5),
            new OrderingProperties.Idempotency(Duration.ofSeconds(60), Duration.ofHours(24), Duration.ofHours(1), 1000),
            new OrderingProperties.Sweeper(Duration.ofSeconds(30), 100),
            new OrderingProperties.Payment(new BigDecimal("1000.00"), Duration.ZERO)));

    @Test
    void approvesAmountsUpToTheLimit() {
        OrderId order = OrderId.newId();

        assertThat(gateway.authorize(order, Money.of(100_000, "AED"))).isEqualTo(new PaymentResult.Approved());
    }

    @Test
    void declinesAmountsAboveTheLimitEveryTimeForTheSameOrder() {
        OrderId order = OrderId.newId();

        assertThat(gateway.authorize(order, Money.of(100_001, "AED"))).isInstanceOf(PaymentResult.Declined.class);
        assertThat(gateway.authorize(order, Money.of(100_001, "AED")))
                .isEqualTo(gateway.authorize(order, Money.of(100_001, "AED")));
    }
}
