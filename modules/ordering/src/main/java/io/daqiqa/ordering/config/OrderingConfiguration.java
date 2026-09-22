package io.daqiqa.ordering.config;

import io.daqiqa.ordering.application.OrderingProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@code daqiqa.ordering.*}. Everything else is found by component scanning. The application provides the
 * {@link java.time.Clock} and enables scheduling (the sweeper and the key purge are {@code @Scheduled}); the other
 * modules provide the {@code PromiseTokenVerifier}, {@code InventoryReservations} and {@code ProductCatalog} that
 * checkout calls.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrderingProperties.class)
class OrderingConfiguration {}
