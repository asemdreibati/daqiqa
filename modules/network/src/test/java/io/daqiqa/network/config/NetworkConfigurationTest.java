package io.daqiqa.network.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.daqiqa.network.application.NetworkView;
import io.daqiqa.network.domain.TravelTimeModel;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

class NetworkConfigurationTest {

    private final NetworkView network = mock(NetworkView.class);

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(NetworkConfiguration.class)
            .withBean(NetworkView.class, () -> network);

    @Test
    void startsWithoutAnyConfigurationUsingTheDocumentedDefaults() {
        context.run(app -> {
            assertThat(app).hasNotFailed();
            assertThat(app.getBean(TravelTimeModel.class))
                    .isEqualTo(new TravelTimeModel(1.3, 7.0, Duration.ofSeconds(60)));
            assertThat(refreshTask(app.getBean(SchedulingConfigurer.class)).getIntervalDuration())
                    .isEqualTo(Duration.ofSeconds(30));
        });
    }

    @Test
    void takesTheTravelModelAndRefreshIntervalFromConfiguration() {
        context.withPropertyValues(
                        "daqiqa.network.travel.road-factor=1.5",
                        "daqiqa.network.travel.speed-meters-per-second=5.5",
                        "daqiqa.network.travel.handoff=90s",
                        "daqiqa.network.coverage.refresh-interval=10s")
                .run(app -> {
                    assertThat(app.getBean(TravelTimeModel.class))
                            .isEqualTo(new TravelTimeModel(1.5, 5.5, Duration.ofSeconds(90)));
                    assertThat(refreshTask(app.getBean(SchedulingConfigurer.class))
                                    .getIntervalDuration())
                            .isEqualTo(Duration.ofSeconds(10));
                });
    }

    @Test
    void refusesToStartWithATravelModelThatCannotDescribeARide() {
        context.withPropertyValues("daqiqa.network.travel.speed-meters-per-second=0")
                .run(app -> assertThat(app)
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("Speed must be a finite positive number"));
    }

    @Test
    void theScheduledTaskRefreshesTheNetwork() {
        context.run(app -> {
            refreshTask(app.getBean(SchedulingConfigurer.class)).getRunnable().run();

            verify(network).refresh();
        });
    }

    private static IntervalTask refreshTask(SchedulingConfigurer schedule) {
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        schedule.configureTasks(registrar);
        assertThat(registrar.getFixedDelayTaskList()).hasSize(1);
        return registrar.getFixedDelayTaskList().getFirst();
    }
}
