package io.daqiqa.promise;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** Test-only Spring Boot configuration so slice and integration tests can bootstrap this module on its own. */
@SpringBootApplication
public class PromiseTestApplication {

    public static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");

    /** In production the server application provides the one clock (ADR-0011). */
    @Bean
    Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
