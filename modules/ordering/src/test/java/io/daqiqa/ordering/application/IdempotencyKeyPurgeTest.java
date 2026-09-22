package io.daqiqa.ordering.application;

import static io.daqiqa.ordering.testsupport.FakeProductCatalog.BREAD;
import static io.daqiqa.ordering.testsupport.FakeProductCatalog.MILK;
import static org.assertj.core.api.Assertions.assertThat;

import io.daqiqa.ordering.domain.PaymentMethod;
import io.daqiqa.ordering.testsupport.OrderingIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class IdempotencyKeyPurgeTest extends OrderingIntegrationTest {

    @Autowired
    CheckoutService checkout;

    @Autowired
    IdempotencyKeyPurge purge;

    @Test
    void deletesKeysOnceTheirRetentionIsOver() {
        checkout.placeOrder("first-order-key", checkoutOf(PaymentMethod.CARD, line(MILK, 1)));
        clock.advance(Duration.ofHours(12));
        checkout.placeOrder("second-order-key", checkoutOf(PaymentMethod.CARD, line(BREAD, 1)));
        clock.advance(Duration.ofHours(12));

        assertThat(purge.purge()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT key FROM ordering.idempotency_key")
                        .query(String.class)
                        .list())
                .containsExactly("second-order-key");
        assertThat(rows("ordering.customer_order")).as("orders are kept").isEqualTo(2);
    }
}
