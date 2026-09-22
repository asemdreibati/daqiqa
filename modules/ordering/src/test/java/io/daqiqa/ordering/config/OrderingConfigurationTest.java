package io.daqiqa.ordering.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.daqiqa.ordering.application.OrderingProperties;
import io.daqiqa.ordering.application.OrderingProperties.Idempotency;
import io.daqiqa.ordering.application.OrderingProperties.Payment;
import io.daqiqa.ordering.application.OrderingProperties.Sweeper;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** {@code daqiqa.ordering.*}: the module starts with no configuration at all and refuses settings that cannot work. */
class OrderingConfigurationTest {

    private final ApplicationContextRunner contexts =
            new ApplicationContextRunner().withUserConfiguration(OrderingConfiguration.class);

    @Test
    void everySettingHasADefault() {
        contexts.run(context -> assertThat(context.getBean(OrderingProperties.class))
                .isEqualTo(new OrderingProperties(
                        Duration.ofMinutes(5),
                        new Idempotency(Duration.ofSeconds(60), Duration.ofHours(24), Duration.ofHours(1), 1000),
                        new Sweeper(Duration.ofSeconds(30), 100, Duration.ofMillis(500)),
                        new Payment(new BigDecimal("1000.00"), Duration.ZERO))));
    }

    @Test
    void bindsHumanReadableValues() {
        contexts.withPropertyValues(
                        "daqiqa.ordering.hold=2m",
                        "daqiqa.ordering.payment.decline-above=250.50",
                        "daqiqa.ordering.payment.latency=300ms")
                .run(context -> {
                    OrderingProperties properties = context.getBean(OrderingProperties.class);
                    assertThat(properties.hold()).isEqualTo(Duration.ofMinutes(2));
                    assertThat(properties.payment())
                            .isEqualTo(new Payment(new BigDecimal("250.50"), Duration.ofMillis(300)));
                });
    }

    @Test
    void refusesAHoldTheInventoryWouldRefuse() {
        contexts.withPropertyValues("daqiqa.ordering.hold=45m")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("daqiqa.ordering.hold"));
    }

    @Test
    void refusesKeysForgottenBeforeTheirLeaseEnds() {
        contexts.withPropertyValues("daqiqa.ordering.idempotency.retention=30s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void refusesALeaseThatOutlastsTheHold() {
        contexts.withPropertyValues("daqiqa.ordering.hold=45s")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("daqiqa.ordering.idempotency.lease (PT1M)")
                        .hasMessageContaining("shorter than daqiqa.ordering.hold (PT45S)"));
        contexts.withPropertyValues("daqiqa.ordering.hold=60s")
                .run(context -> assertThat(context).hasFailed());
        contexts.withPropertyValues("daqiqa.ordering.hold=45s", "daqiqa.ordering.idempotency.lease=30s")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
