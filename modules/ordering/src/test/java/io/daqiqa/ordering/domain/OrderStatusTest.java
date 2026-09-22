package io.daqiqa.ordering.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class OrderStatusTest {

    @Test
    void onlyAnOrderAwaitingPaymentCanMoveAndOnlyForward() {
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                boolean allowed = from == OrderStatus.AWAITING_PAYMENT && to != OrderStatus.AWAITING_PAYMENT;
                assertThat(from.canBecome(to)).as("%s -> %s", from, to).isEqualTo(allowed);
            }
        }
    }

    @Test
    void cashOnDeliveryStartsConfirmedAndCardStartsAwaitingPayment() {
        assertThat(Arrays.stream(PaymentMethod.values()).map(PaymentMethod::initialStatus))
                .containsExactly(OrderStatus.AWAITING_PAYMENT, OrderStatus.CONFIRMED);
    }
}
