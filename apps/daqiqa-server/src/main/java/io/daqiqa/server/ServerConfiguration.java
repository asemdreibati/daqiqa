package io.daqiqa.server;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
class ServerConfiguration {

    /**
     * Refuses to start with the published development key unless the dev profile is active. Static, as a bean factory
     * post-processor should be: it runs before any other bean exists, without creating this configuration early.
     */
    @Bean
    static DevelopmentKeyGuard developmentKeyGuard(Environment environment) {
        return new DevelopmentKeyGuard(environment);
    }

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
}
