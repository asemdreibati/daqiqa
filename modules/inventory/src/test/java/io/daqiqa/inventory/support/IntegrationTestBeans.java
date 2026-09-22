package io.daqiqa.inventory.support;

import io.daqiqa.testing.postgres.PostgresTestDatabase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * What the server application would provide around this module: a real PostgreSQL database, a clock, the network and
 * catalog contexts (faked) and the outbox (recording).
 */
@TestConfiguration(proxyBeanMethods = false)
public class IntegrationTestBeans {

    /** The JVM-wide test database (a pool of up to 64 connections). It outlives every Spring context: never closed. */
    @Bean(destroyMethod = "")
    DataSource dataSource() {
        return PostgresTestDatabase.shared()
                .migrate("classpath:db/migration/inventory")
                .dataSource();
    }

    @Bean
    InventoryTables inventoryTables(DataSource dataSource) {
        return new InventoryTables(dataSource);
    }

    @Bean
    MutableClock clock() {
        return new MutableClock();
    }

    @Bean
    FakeStoreDirectory storeDirectory() {
        return new FakeStoreDirectory();
    }

    @Bean
    FakeProductCatalog productCatalog() {
        return new FakeProductCatalog();
    }

    @Bean
    TransactionalRecordingOutbox outbox() {
        return new TransactionalRecordingOutbox();
    }

    /** Tests read the counters; without a registry of its own, a test context gets a no-op one. */
    @Bean
    SimpleMeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
