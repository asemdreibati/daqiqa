package io.daqiqa.server;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
class ServerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ServerConfiguration.class);

    /**
     * The one clock every module reads time from (ADR-0011). Replaced by a controllable clock in tests and by an
     * accelerated clock in simulations.
     */
    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Runs scheduled jobs (outbox relay, reservation expiry, abandoned orders, purges) on a small pool of platform
     * threads (ADR-0014). With virtual threads enabled, Spring Boot's default scheduler runs every fixed-delay job on a
     * single thread, so a relay waiting on an unreachable broker would stop reservations from expiring.
     */
    @Bean(destroyMethod = "shutdown")
    ThreadPoolTaskScheduler taskScheduler(@Value("${daqiqa.scheduler.pool-size:4}") int poolSize) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix("daqiqa-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }

    /** Makes it impossible to miss that a deployment is signing promises with the published development key. */
    @Bean
    ApplicationRunner developmentKeyWarning(@Value("${daqiqa.promise.token.active-key-id:}") String activeKeyId) {
        return args -> {
            if ("dev".equals(activeKeyId)) {
                log.warn("Promise tokens are signed with the development key. Configure daqiqa.promise.token.* "
                        + "with a secret key before exposing this instance.");
            }
        };
    }
}
