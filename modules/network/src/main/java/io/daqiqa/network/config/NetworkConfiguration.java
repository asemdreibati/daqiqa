package io.daqiqa.network.config;

import io.daqiqa.network.adapter.out.h3.H3CellGrid;
import io.daqiqa.network.application.NetworkView;
import io.daqiqa.network.domain.CellGrid;
import io.daqiqa.network.domain.TravelTimeModel;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NetworkProperties.class)
class NetworkConfiguration {

    /** H3 loads a native library, so there is exactly one instance; it is thread-safe. */
    @Bean
    CellGrid cellGrid() {
        return H3CellGrid.create();
    }

    @Bean
    TravelTimeModel travelTimeModel(NetworkProperties properties) {
        NetworkProperties.Travel travel = properties.travel();
        return new TravelTimeModel(travel.roadFactor(), travel.speedMetersPerSecond(), travel.handoff());
    }

    /**
     * Reloads the store network periodically, a safety net that bounds how stale this instance can be about changes
     * made through another instance until status changes are propagated as events (later wave). Takes effect when the
     * application enables scheduling; modules never enable it themselves.
     */
    @Bean
    SchedulingConfigurer networkRefreshSchedule(NetworkView network, NetworkProperties properties) {
        Duration interval = properties.coverage().refreshInterval();
        return registrar -> registrar.addFixedDelayTask(new FixedDelayTask(network::refresh, interval, interval));
    }
}
