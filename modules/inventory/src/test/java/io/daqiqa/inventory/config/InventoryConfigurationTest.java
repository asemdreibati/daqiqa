package io.daqiqa.inventory.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.daqiqa.inventory.application.InventoryMetrics;
import io.daqiqa.inventory.application.ReservationExpirySweeper;
import io.daqiqa.inventory.application.ReservationService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/** The module's wiring on its own: properties, the sweeper bean and its schedule, without starting a scheduler. */
class InventoryConfigurationTest {

    private final ReservationService reservations = mock(ReservationService.class);

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(InventoryConfiguration.class)
            .withBean(ReservationService.class, () -> reservations)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(InventoryMetrics.class)
            .withBean(Clock.class, () -> Clock.fixed(Instant.parse("2026-09-22T10:00:00Z"), ZoneOffset.UTC));

    @Test
    void startsWithoutAnyConfigurationSweepingEveryFiveSecondsInBatchesOfOneHundred() {
        context.run(started -> {
            assertThat(started.getBean(InventoryProperties.class))
                    .isEqualTo(new InventoryProperties(
                            new InventoryProperties.Expiry(Duration.ofSeconds(5), 100, Duration.ofMillis(500))));
            assertThat(scheduledSweep(started.getBean(SchedulingConfigurer.class))
                            .getIntervalDuration())
                    .isEqualTo(Duration.ofSeconds(5));
        });
    }

    @Test
    void theScheduledTaskSweepsWithTheConfiguredBatchSizeLockTimeoutAndInterval() {
        given(reservations.expireElapsedHolds(any(), eq(25), any())).willReturn(3);

        context.withPropertyValues(
                        "daqiqa.inventory.expiry.interval=250ms",
                        "daqiqa.inventory.expiry.batch-size=25",
                        "daqiqa.inventory.expiry.lock-timeout=750ms")
                .run(started -> {
                    IntervalTask sweep = scheduledSweep(started.getBean(SchedulingConfigurer.class));
                    assertThat(sweep.getIntervalDuration()).isEqualTo(Duration.ofMillis(250));

                    sweep.getRunnable().run();

                    verify(reservations)
                            .expireElapsedHolds(Instant.parse("2026-09-22T10:00:00Z"), 25, Duration.ofMillis(750));
                    assertThat(started.getBean(ReservationExpirySweeper.class)).isNotNull();
                });
    }

    @Test
    void refusesToStartWithANonPositiveIntervalOrBatchSize() {
        context.withPropertyValues("daqiqa.inventory.expiry.batch-size=0")
                .run(failed -> assertThat(failed)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("batch-size must be positive"));
        context.withPropertyValues("daqiqa.inventory.expiry.interval=0s")
                .run(failed -> assertThat(failed)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("interval must be positive"));
        context.withPropertyValues("daqiqa.inventory.expiry.lock-timeout=0s")
                .run(failed -> assertThat(failed)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("lock-timeout must be positive"));
    }

    /** Registers the schedule the way {@code @EnableScheduling} would, without starting any thread. */
    private static IntervalTask scheduledSweep(SchedulingConfigurer schedule) {
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        schedule.configureTasks(registrar);
        assertThat(registrar.getFixedDelayTaskList()).hasSize(1);
        return registrar.getFixedDelayTaskList().getFirst();
    }
}
