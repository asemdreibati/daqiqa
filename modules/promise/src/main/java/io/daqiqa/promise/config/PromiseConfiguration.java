package io.daqiqa.promise.config;

import io.daqiqa.network.api.StoreDirectory;
import io.daqiqa.promise.application.PromiseTokenCodec;
import io.daqiqa.promise.application.SigningKeys;
import io.daqiqa.promise.application.StatusBasedStoreLoad;
import io.daqiqa.promise.domain.PickTimeModel;
import io.daqiqa.promise.domain.PromiseCalculator;
import io.daqiqa.promise.domain.PromiseCalibration;
import io.daqiqa.promise.domain.StoreLoad;
import io.daqiqa.promise.domain.StoreLoadProvider;
import java.time.Clock;
import java.util.Objects;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The promise engine and its version 1 models, all from {@code daqiqa.promise.*} configuration. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PromiseProperties.class)
class PromiseConfiguration {

    @Bean
    PickTimeModel pickTimeModel(PromiseProperties properties) {
        PromiseProperties.Pick pick = properties.pick();
        return new PickTimeModel(pick.base(), pick.perUnit(), pick.max());
    }

    @Bean
    StoreLoadProvider storeLoadProvider(StoreDirectory stores, PromiseProperties properties) {
        PromiseProperties.Load load = properties.load();
        return new StatusBasedStoreLoad(
                stores,
                new StoreLoad(load.open().queue(), load.open().riderWait()),
                new StoreLoad(load.busy().queue(), load.busy().riderWait()));
    }

    @Bean
    PromiseCalibration promiseCalibration(PromiseProperties properties) {
        return PromiseCalibration.constant(properties.buffer());
    }

    @Bean
    PromiseCalculator promiseCalculator(
            PickTimeModel pickTime, StoreLoadProvider storeLoad, PromiseCalibration calibration) {
        return new PromiseCalculator(pickTime, storeLoad, calibration);
    }

    /** Also the {@link io.daqiqa.promise.api.PromiseTokenVerifier} that checkout uses. */
    @Bean
    PromiseTokenCodec promiseTokenCodec(PromiseProperties properties, Clock clock) {
        PromiseProperties.Token token = properties.token();
        return new PromiseTokenCodec(
                new SigningKeys(token.decodedKeys(), Objects.requireNonNull(token.activeKeyId())),
                token.validity(),
                clock);
    }
}
