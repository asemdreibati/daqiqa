package io.daqiqa.inventory.config;

import io.daqiqa.inventory.application.ReservationExpirySweeper;
import io.daqiqa.inventory.application.ReservationService;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InventoryProperties.class)
class InventoryConfiguration {

    @Bean
    ReservationExpirySweeper reservationExpirySweeper(
            ReservationService reservations, Clock clock, InventoryProperties properties) {
        return new ReservationExpirySweeper(
                reservations, clock, properties.expiry().batchSize());
    }

    /**
     * Sweeps on a fixed delay. Equivalent to {@code @Scheduled(fixedDelayString =
     * "${daqiqa.inventory.expiry.interval:5s}")}, but reads the bound properties, so the default lives in one place.
     * Takes effect only where scheduling is enabled (the server application): tests sweep by calling the sweeper.
     */
    @Bean
    SchedulingConfigurer reservationExpirySchedule(ReservationExpirySweeper sweeper, InventoryProperties properties) {
        return registrar ->
                registrar.addFixedDelayTask(sweeper::sweep, properties.expiry().interval());
    }
}
