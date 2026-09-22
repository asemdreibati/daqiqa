package io.daqiqa.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import io.daqiqa.ordering.testsupport.CheckoutTestPorts;
import io.daqiqa.testing.postgres.PostgresTestDatabase;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** With scheduling on, as in the server, the sweeper and the key purge run on their configured intervals. */
@Tag("integration")
@DirtiesContext // stop the scheduled jobs with this class, so they never run into other tests
@SpringBootTest(
        classes = {OrderingTestApplication.class, OrderingSchedulingTest.SchedulingOn.class},
        properties = "daqiqa.ordering.sweeper.interval=10s")
@Import(CheckoutTestPorts.class)
class OrderingSchedulingTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.shared()
                .migrate("classpath:db/migration/messaging", "classpath:db/migration/ordering")
                .registerWith(registry);
    }

    @Autowired
    ScheduledTaskHolder scheduler;

    @Test
    void sweepsAndPurgesOnTheirConfiguredIntervals() {
        Map<String, Duration> intervals = scheduler.getScheduledTasks().stream()
                .map(ScheduledTask::getTask)
                .filter(FixedDelayTask.class::isInstance)
                .map(FixedDelayTask.class::cast)
                .collect(Collectors.toMap(Object::toString, FixedDelayTask::getIntervalDuration));

        assertThat(intervals)
                .containsEntry("io.daqiqa.ordering.application.AbandonedOrderSweeper.sweep", Duration.ofSeconds(10))
                .containsEntry("io.daqiqa.ordering.application.IdempotencyKeyPurge.purge", Duration.ofHours(1));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class SchedulingOn {}
}
