package io.daqiqa.ordering.testsupport;

import java.time.Clock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The ports checkout calls, faked: the promise, inventory and catalog modules' APIs (implemented elsewhere), and a
 * scripted payment provider that replaces the simulated one.
 */
@TestConfiguration(proxyBeanMethods = false)
public class CheckoutTestPorts {

    @Bean
    FakePromiseTokenVerifier promiseTokenVerifier(Clock clock) {
        return new FakePromiseTokenVerifier(clock);
    }

    @Bean
    FakeInventory inventoryReservations(Clock clock, PlatformTransactionManager transactionManager) {
        return new FakeInventory(clock, transactionManager);
    }

    @Bean
    FakeProductCatalog productCatalog() {
        return new FakeProductCatalog();
    }

    @Bean
    @Primary
    ScriptedPaymentGateway scriptedPaymentGateway() {
        return new ScriptedPaymentGateway();
    }
}
