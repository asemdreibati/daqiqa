package io.daqiqa.promise.config;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.kernel.LocalizedText;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.kernel.StoreId;
import io.daqiqa.network.api.DarkStore;
import io.daqiqa.network.api.StoreDirectory;
import io.daqiqa.network.api.StoreStatus;
import io.daqiqa.promise.api.DeliveryTier;
import io.daqiqa.promise.api.PromiseTokenVerifier;
import io.daqiqa.promise.application.PromiseTokenCodec;
import io.daqiqa.promise.domain.Basket;
import io.daqiqa.promise.domain.EtaBreakdown;
import io.daqiqa.promise.domain.PickTimeModel;
import io.daqiqa.promise.domain.PromiseCalibration;
import io.daqiqa.promise.domain.Quote;
import io.daqiqa.promise.domain.StoreLoad;
import io.daqiqa.promise.domain.StoreLoadProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PromiseConfigurationTest {

    private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");
    private static final String KEY_32_BYTES = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String KEY_16_BYTES = Base64.getEncoder().encodeToString(new byte[16]);
    private static final StoreId OPEN_STORE = StoreId.of("DXB-OPEN");
    private static final StoreId BUSY_STORE = StoreId.of("DXB-BUSY");

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(PromiseConfiguration.class)
            .withBean(Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC))
            .withBean(StoreDirectory.class, PromiseConfigurationTest::directory);

    private final ApplicationContextRunner withKey = context.withPropertyValues(
            "daqiqa.promise.token.active-key-id=k1", "daqiqa.promise.token.keys.k1=" + KEY_32_BYTES);

    @Test
    void refusesToStartWithoutASigningKeyAndSaysWhatToConfigure() {
        context.run(app -> assertThat(app)
                .getFailure()
                .hasStackTraceContaining("daqiqa.promise.token.active-key-id")
                .hasStackTraceContaining("daqiqa.promise.token.keys.<id>"));
    }

    @Test
    void refusesToStartWhenTheActiveKeyIsMissing() {
        context.withPropertyValues(
                        "daqiqa.promise.token.active-key-id=k2", "daqiqa.promise.token.keys.k1=" + KEY_32_BYTES)
                .run(app -> assertThat(app)
                        .getFailure()
                        .hasStackTraceContaining("daqiqa.promise.token.keys.k2 is not set"));
    }

    @Test
    void refusesToStartWithAKeyShorterThan32Bytes() {
        context.withPropertyValues(
                        "daqiqa.promise.token.active-key-id=k1", "daqiqa.promise.token.keys.k1=" + KEY_16_BYTES)
                .run(app -> assertThat(app)
                        .getFailure()
                        .hasStackTraceContaining("daqiqa.promise.token.keys.k1 is 16 bytes long"));
    }

    @Test
    void refusesToStartWithAShortRetiredKeyToo() {
        // A weak verify-only key would still let anyone forge tokens under its id.
        withKey.withPropertyValues("daqiqa.promise.token.keys.k0=" + KEY_16_BYTES)
                .run(app -> assertThat(app)
                        .getFailure()
                        .hasStackTraceContaining("daqiqa.promise.token.keys.k0 is 16 bytes long"));
    }

    @Test
    void refusesToStartWithAKeyThatIsNotBase64() {
        context.withPropertyValues("daqiqa.promise.token.active-key-id=k1", "daqiqa.promise.token.keys.k1=not*base64")
                .run(app -> assertThat(app)
                        .getFailure()
                        .hasStackTraceContaining("daqiqa.promise.token.keys.k1 is not valid base64"));
    }

    @Test
    void startsWithASigningKeyAndTheDocumentedDefaults() {
        withKey.run(app -> {
            assertThat(app).hasNotFailed().hasSingleBean(PromiseTokenVerifier.class);
            assertThat(app.getBean(PickTimeModel.class))
                    .isEqualTo(new PickTimeModel(ofSeconds(45), ofSeconds(8), ofSeconds(600)));
            assertThat(app.getBean(StoreLoadProvider.class).loadAt(OPEN_STORE))
                    .isEqualTo(new StoreLoad(ofSeconds(60), ofSeconds(60)));
            assertThat(app.getBean(StoreLoadProvider.class).loadAt(BUSY_STORE))
                    .isEqualTo(new StoreLoad(ofSeconds(300), ofSeconds(180)));
            assertThat(app.getBean(PromiseCalibration.class).bufferAt(OPEN_STORE))
                    .isEqualTo(ofSeconds(90));
            assertThat(issueToken(app.getBean(PromiseTokenCodec.class))).isEqualTo(NOW.plusSeconds(90));
        });
    }

    @Test
    void everyModelIsConfigurable() {
        withKey.withPropertyValues(
                        "daqiqa.promise.pick.base=30s",
                        "daqiqa.promise.pick.per-unit=5s",
                        "daqiqa.promise.pick.max=300s",
                        "daqiqa.promise.load.open.queue=30s",
                        "daqiqa.promise.load.open.rider-wait=45s",
                        "daqiqa.promise.load.busy.queue=240s",
                        "daqiqa.promise.load.busy.rider-wait=120s",
                        "daqiqa.promise.buffer=2m",
                        "daqiqa.promise.token.validity=60s")
                .run(app -> {
                    assertThat(app.getBean(PickTimeModel.class))
                            .isEqualTo(new PickTimeModel(ofSeconds(30), ofSeconds(5), ofSeconds(300)));
                    assertThat(app.getBean(StoreLoadProvider.class).loadAt(OPEN_STORE))
                            .isEqualTo(new StoreLoad(ofSeconds(30), ofSeconds(45)));
                    assertThat(app.getBean(StoreLoadProvider.class).loadAt(BUSY_STORE))
                            .isEqualTo(new StoreLoad(ofSeconds(240), ofSeconds(120)));
                    assertThat(app.getBean(PromiseCalibration.class).bufferAt(OPEN_STORE))
                            .isEqualTo(ofSeconds(120));
                    assertThat(issueToken(app.getBean(PromiseTokenCodec.class))).isEqualTo(NOW.plusSeconds(60));
                });
    }

    private static Instant issueToken(PromiseTokenCodec codec) {
        Quote quote = new Quote(OPEN_STORE, DeliveryTier.EXPRESS_15, new EtaBreakdown(60, 69, 60, 330, 90));
        return codec.issue(quote, Basket.of(List.of(SkuQuantity.of("MILK-FULL-1L", 1))))
                .expiresAt();
    }

    private static StoreDirectory directory() {
        StoreDirectory directory = mock(StoreDirectory.class);
        given(directory.find(OPEN_STORE)).willReturn(Optional.of(store(OPEN_STORE, StoreStatus.OPEN)));
        given(directory.find(BUSY_STORE)).willReturn(Optional.of(store(BUSY_STORE, StoreStatus.BUSY)));
        return directory;
    }

    private static DarkStore store(StoreId id, StoreStatus status) {
        return new DarkStore(id, LocalizedText.of("Store", "متجر"), GeoPoint.of(25.08, 55.14), status, 5, 3_000);
    }
}
