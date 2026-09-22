package io.daqiqa.ordering.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.function.UnaryOperator;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OrderTest {

    @Test
    void aCardOrderWaitsForItsPaymentAndACashOrderIsConfirmedAtOnce() {
        assertThat(Orders.placed(PaymentMethod.CARD).status()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        assertThat(Orders.placed(PaymentMethod.CASH_ON_DELIVERY).status()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void anOrderAwaitingPaymentEndsConfirmedFailedOrAbandoned() {
        Order order = Orders.placed(PaymentMethod.CARD);

        assertThat(order.confirmPayment().status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.failPayment().status()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(order.abandon().status()).isEqualTo(OrderStatus.ABANDONED);
        assertThat(order.status()).as("moves return a new order").isEqualTo(OrderStatus.AWAITING_PAYMENT);
    }

    @ParameterizedTest
    @EnumSource(
            value = Move.class,
            names = {"CONFIRM", "FAIL", "ABANDON"})
    void aSettledOrderNeverMovesAgain(Move settle) {
        Order settled = settle.apply(Orders.placed(PaymentMethod.CARD));

        for (Move move : Move.values()) {
            assertThatThrownBy(() -> move.apply(settled))
                    .isInstanceOf(IllegalOrderTransitionException.class)
                    .hasMessageContaining(settled.status().name());
        }
    }

    @Test
    void aCashOnDeliveryOrderCanNeverWaitForOrFailAPayment() {
        Order cash = Orders.placed(PaymentMethod.CASH_ON_DELIVERY);

        assertThatThrownBy(cash::failPayment).isInstanceOf(IllegalOrderTransitionException.class);
        assertThatIllegalArgumentException().isThrownBy(() -> withStatus(cash, OrderStatus.AWAITING_PAYMENT));
    }

    @Test
    void mustBeDeliveredByPlacementTimePlusThePromisedMinutes() {
        Order order = Orders.placed(PaymentMethod.CARD);

        assertThat(order.deliverBy()).isEqualTo(Orders.PLACED_AT.plus(Duration.ofMinutes(12)));
    }

    @Test
    void itsTotalIsTheSumOfItsLines() {
        Order order = Orders.placed(PaymentMethod.CARD);

        assertThat(order.total()).isEqualTo(Orders.aed(2 * 695 + 300));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Order(
                        order.id(),
                        order.customerId(),
                        order.promise(),
                        order.placedAt(),
                        order.deliveryLocation(),
                        order.paymentMethod(),
                        order.reservationId(),
                        order.lines(),
                        Orders.aed(1),
                        order.status()))
                .withMessageContaining("sum of its lines");
    }

    @Test
    void itsLinesAreNumberedInBasketOrderWithOneLinePerSku() {
        Order order = Orders.placed(PaymentMethod.CARD);
        OrderLine milk = order.lines().get(0);
        OrderLine bread = order.lines().get(1);

        assertThatIllegalArgumentException().isThrownBy(() -> withLines(order, List.of(bread, milk)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> withLines(
                        order,
                        List.of(milk, new OrderLine(2, milk.sku(), milk.name(), bread.quantity(), bread.unitPrice()))));
        assertThatIllegalArgumentException().isThrownBy(() -> withLines(order, List.of()));
    }

    /** Whatever an order is asked to do, and in whatever order, it changes status at most once. */
    @Property
    void changesStatusAtMostOnce(
            @ForAll("paymentMethods") PaymentMethod paymentMethod, @ForAll("moves") List<Move> moves) {
        Order order = Orders.placed(paymentMethod);
        int changes = 0;
        for (Move move : moves) {
            OrderStatus before = order.status();
            try {
                order = move.apply(order);
            } catch (IllegalOrderTransitionException refused) {
                assertThat(order.status()).isEqualTo(before);
                continue;
            }
            assertThat(before).isEqualTo(OrderStatus.AWAITING_PAYMENT);
            changes++;
        }
        assertThat(changes).isLessThanOrEqualTo(paymentMethod == PaymentMethod.CARD ? 1 : 0);
    }

    @Provide
    Arbitrary<PaymentMethod> paymentMethods() {
        return Arbitraries.of(PaymentMethod.class);
    }

    @Provide
    Arbitrary<List<Move>> moves() {
        return Arbitraries.of(Move.class).list().ofMaxSize(6);
    }

    private static Order withStatus(Order order, OrderStatus status) {
        return new Order(
                order.id(),
                order.customerId(),
                order.promise(),
                order.placedAt(),
                order.deliveryLocation(),
                order.paymentMethod(),
                order.reservationId(),
                order.lines(),
                order.total(),
                status);
    }

    private static Order withLines(Order order, List<OrderLine> lines) {
        return new Order(
                order.id(),
                order.customerId(),
                order.promise(),
                order.placedAt(),
                order.deliveryLocation(),
                order.paymentMethod(),
                order.reservationId(),
                lines,
                lines.isEmpty() ? order.total() : BasketPricing.totalOf(lines),
                order.status());
    }

    enum Move {
        CONFIRM(Order::confirmPayment),
        FAIL(Order::failPayment),
        ABANDON(Order::abandon);

        private final UnaryOperator<Order> move;

        Move(UnaryOperator<Order> move) {
            this.move = move;
        }

        Order apply(Order order) {
            return move.apply(order);
        }
    }
}
