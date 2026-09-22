package io.daqiqa.ordering;

import io.daqiqa.ordering.testsupport.MutableClock;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Test-only Spring Boot configuration so this module can be bootstrapped on its own, together with the outbox it
 * writes to. In production the server application provides the clock (ADR-0011) and enables scheduling; here tests
 * move time by hand and call scheduled work directly.
 */
@SpringBootApplication(scanBasePackages = {"io.daqiqa.ordering", "io.daqiqa.messaging"})
public class OrderingTestApplication {

    @Bean
    MutableClock clock() {
        return new MutableClock();
    }
}
