package io.daqiqa.promise.application;

import static io.daqiqa.promise.application.PromiseTokenCodecTest.BREAKFAST;
import static io.daqiqa.promise.application.PromiseTokenCodecTest.NOW;
import static io.daqiqa.promise.application.PromiseTokenCodecTest.QUOTE;
import static io.daqiqa.promise.application.Tokens.key;
import static org.assertj.core.api.Assertions.assertThat;

import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.kernel.StoreId;
import io.daqiqa.network.api.StoreStatus;
import io.daqiqa.promise.MutableClock;
import io.daqiqa.promise.domain.Basket;
import java.time.Duration;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;

/** The location signed into a token, anywhere on Earth. */
class PromiseTokenLocationTest {

    private static final StoreId MARINA = StoreId.of("DXB-MARINA");

    private final MutableClock clock = new MutableClock(NOW);
    private final PromiseTokenCodec codec =
            new PromiseTokenCodec(new SigningKeys(Map.of("k1", key(1)), "k1"), Duration.ofSeconds(90), clock);
    private final CheckoutPromiseVerifier verifier =
            new CheckoutPromiseVerifier(codec, new FakeStoreDirectory().with(MARINA, StoreStatus.OPEN), clock, 100);
    /** Anywhere on Earth, the signed micro-degrees bring the location back to within 11 cm, and it verifies. */
    @Property
    void theLocationSurvivesTheRoundTrip(
            @ForAll @DoubleRange(min = -90, max = 90) double latitude,
            @ForAll @DoubleRange(min = -180, max = 180) double longitude) {
        GeoPoint location = GeoPoint.of(latitude, longitude);

        String issued = codec.issue(QUOTE, Basket.of(BREAKFAST), location).token();

        assertThat(codec.authenticate(issued).location().distanceMetersTo(location))
                .isLessThan(0.11);
        assertThat(verifier.verify(issued, BREAKFAST, location).storeId()).isEqualTo(MARINA);
    }
}
