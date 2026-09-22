package io.daqiqa.network.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration of the network context. Every property has a default, so the application starts without any.
 *
 * @param travel the rider travel time model ({@code daqiqa.network.travel.*})
 * @param coverage the coverage index ({@code daqiqa.network.coverage.*})
 */
@ConfigurationProperties("daqiqa.network")
record NetworkProperties(
        @DefaultValue Travel travel, @DefaultValue Coverage coverage) {

    /**
     * @param roadFactor road distance per metre of straight line
     * @param speedMetersPerSecond average riding speed (7 m/s ≈ 25 km/h)
     * @param handoff fixed time at the destination: parking, finding the door, handing over
     */
    record Travel(
            @DefaultValue("1.3") double roadFactor,
            @DefaultValue("7") double speedMetersPerSecond,
            @DefaultValue("60s") Duration handoff) {}

    /**
     * @param refreshInterval how often the store network is reloaded from the database when the application enables
     *     scheduling: the longest this instance can miss a change made through another instance
     */
    record Coverage(@DefaultValue("30s") Duration refreshInterval) {}
}
