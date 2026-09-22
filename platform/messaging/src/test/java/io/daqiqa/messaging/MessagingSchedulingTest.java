package io.daqiqa.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import io.daqiqa.testing.postgres.PostgresTestDatabase;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** With scheduling on, as in the server, the relay and the purge run on their configured intervals. */
@Tag("integration")
@DirtiesContext // stop the scheduled jobs with this class, so they never run into other tests
@SpringBootTest(classes = {MessagingTestApplication.class, MessagingSchedulingTest.SchedulingOn.class})
class MessagingSchedulingTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.shared()
                .migrate("classpath:db/migration/messaging")
                .registerWith(registry);
    }

    @Autowired
    ScheduledTaskHolder scheduler;

    @Test
    void relaysEveryQuarterSecondAndPurgesHourlyByDefault() {
        Map<String, Duration> intervals = scheduler.getScheduledTasks().stream()
                .map(ScheduledTask::getTask)
                .filter(FixedDelayTask.class::isInstance)
                .map(FixedDelayTask.class::cast)
                .collect(Collectors.toMap(Object::toString, FixedDelayTask::getIntervalDuration));

        assertThat(intervals)
                .containsEntry("io.daqiqa.messaging.outbox.OutboxRelay.relay", Duration.ofMillis(250))
                .containsEntry("io.daqiqa.messaging.outbox.OutboxPurge.purge", Duration.ofHours(1));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class SchedulingOn {}
}
