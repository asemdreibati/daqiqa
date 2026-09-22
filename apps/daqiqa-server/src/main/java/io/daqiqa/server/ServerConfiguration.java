package io.daqiqa.server;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ServerConfiguration {

    /**
     * The one clock every module reads time from (ADR-0011). Replaced by a controllable clock in tests and by an
     * accelerated clock in simulations.
     */
    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }
}
