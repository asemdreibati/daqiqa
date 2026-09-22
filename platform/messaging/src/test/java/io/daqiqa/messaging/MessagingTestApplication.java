package io.daqiqa.messaging;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Test-only Spring Boot configuration so this component can be bootstrapped on its own. In production the server
 * application provides the clock (ADR-0011) and enables scheduling.
 */
@SpringBootApplication
public class MessagingTestApplication {

    @Bean
    Clock clock() {
        return Clock.fixed(Instant.parse("2026-09-22T12:00:00Z"), ZoneOffset.UTC);
    }
}
