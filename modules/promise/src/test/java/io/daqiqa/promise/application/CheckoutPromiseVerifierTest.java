package io.daqiqa.promise.application;

import static io.daqiqa.promise.application.PromiseTokenCodecTest.BREAKFAST;
import static io.daqiqa.promise.application.PromiseTokenCodecTest.CUSTOMER;
import static io.daqiqa.promise.application.PromiseTokenCodecTest.EXPIRES_AT;
import static io.daqiqa.promise.application.PromiseTokenCodecTest.ISSUED_AT;
import static io.daqiqa.promise.application.PromiseTokenCodecTest.NOW;
import static io.daqiqa.promise.application.PromiseTokenCodecTest.QUOTE;
import static io.daqiqa.promise.application.Tokens.key;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.kernel.PromiseId;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.kernel.StoreId;
import io.daqiqa.network.api.StoreStatus;
import io.daqiqa.promise.MutableClock;
import io.daqiqa.promise.api.DeliveryTier;
import io.daqiqa.promise.api.InvalidPromiseException;
import io.daqiqa.promise.api.InvalidPromiseException.Reason;
import io.daqiqa.promise.api.VerifiedPromise;
import io.daqiqa.promise.domain.Basket;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.AbstractObjectAssert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class CheckoutPromiseVerifierTest {

    private static final StoreId MARINA = StoreId.of("DXB-MARINA");
    private static final double TOLERANCE_METERS = 100;

    /** Mean Earth radius, as used by {@link GeoPoint#distanceMetersTo}. */
    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private final MutableClock clock = new MutableClock(NOW);
    private final FakeStoreDirectory stores = new FakeStoreDirectory().with(MARINA, StoreStatus.OPEN);
    private final PromiseTokenCodec codec =
            new PromiseTokenCodec(new SigningKeys(Map.of("k1", key(1)), "k1"), Duration.ofSeconds(90), clock);
    private final CheckoutPromiseVerifier verifier =
            new CheckoutPromiseVerifier(codec, stores, clock, TOLERANCE_METERS);
    private final String token =
            codec.issue(QUOTE, Basket.of(BREAKFAST), CUSTOMER).token();

    @Test
    void honoursThePromiseForTheSameBasketAndPlaceFromAnOpenStore() {
        VerifiedPromise verified = verifier.verify(token, BREAKFAST, CUSTOMER);

        assertThat(verified.storeId()).isEqualTo(MARINA);
        assertThat(verified.tier()).isEqualTo(DeliveryTier.EXPRESS_15);
        assertThat(verified.etaMinutes()).isEqualTo(11);
        assertThat(verified.issuedAt()).isEqualTo(ISSUED_AT);
        assertThat(verified.expiresAt()).isEqualTo(EXPIRES_AT);
    }

    @Test
    void aTokenIsValidUntilTheSecondItExpires() {
        clock.set(EXPIRES_AT.minusMillis(1));
        assertThat(verifier.verify(token, BREAKFAST, CUSTOMER).expiresAt()).isEqualTo(EXPIRES_AT);

        clock.set(EXPIRES_AT);
        assertRejected(token, BREAKFAST, CUSTOMER).isEqualTo(Reason.EXPIRED);

        clock.advance(Duration.ofHours(1));
        assertRejected(token, BREAKFAST, CUSTOMER).isEqualTo(Reason.EXPIRED);
    }

    /** The review's forgery, signed with a key the verifier trusts: a one-minute ETA, valid for a day. */
    @Test
    void aCorrectlySignedTokenValidForLongerThanWeIssueTokensForIsNotOurs() {
        assertRejected(forge(ISSUED_AT, ISSUED_AT.plus(Duration.ofDays(1))), BREAKFAST, CUSTOMER)
                .isEqualTo(Reason.MALFORMED);
        assertRejected(forge(ISSUED_AT, ISSUED_AT.plusSeconds(91)), BREAKFAST, CUSTOMER)
                .as("one second longer than the validity")
                .isEqualTo(Reason.MALFORMED);
        assertThat(verifier.verify(forge(ISSUED_AT, ISSUED_AT.plusSeconds(90)), BREAKFAST, CUSTOMER)
                        .etaMinutes())
                .as("exactly the validity, as we issue them")
                .isEqualTo(1);
    }

    @Test
    void aTokenThatExpiresNoLaterThanItIsIssuedIsNotOurs() {
        assertRejected(forge(ISSUED_AT, ISSUED_AT), BREAKFAST, CUSTOMER).isEqualTo(Reason.MALFORMED);
        // Not yet expired on our clock, which alone would have let it through.
        assertRejected(forge(ISSUED_AT.plusSeconds(20), ISSUED_AT.plusSeconds(10)), BREAKFAST, CUSTOMER)
                .isEqualTo(Reason.MALFORMED);
    }

    @Test
    void aTokenIssuedMoreThanThirtySecondsAheadOfOurClockIsNotOurs() {
        clock.set(ISSUED_AT);
        Instant latest = ISSUED_AT.plus(CheckoutPromiseVerifier.MAX_CLOCK_SKEW);

        assertThat(verifier.verify(forge(latest, latest.plusSeconds(90)), BREAKFAST, CUSTOMER))
                .as("the issuing instance's clock may run up to 30 s ahead of ours")
                .isNotNull();
        assertRejected(forge(latest.plusSeconds(1), latest.plusSeconds(91)), BREAKFAST, CUSTOMER)
                .isEqualTo(Reason.MALFORMED);
    }

    @Test
    void theSameBasketListedInAnotherOrderOrWithSplitLinesStillMatches() {
        List<SkuQuantity> reordered = List.of(
                SkuQuantity.of("BREAD-ARABIC-6PK", 1),
                SkuQuantity.of("MILK-FULL-1L", 1),
                SkuQuantity.of("MILK-FULL-1L", 1));

        assertThat(verifier.verify(token, reordered, CUSTOMER).storeId()).isEqualTo(MARINA);
    }

    @Test
    void anotherBasketIsACartMismatch() {
        assertRejected(
                        token,
                        List.of(SkuQuantity.of("MILK-FULL-1L", 3), SkuQuantity.of("BREAD-ARABIC-6PK", 1)),
                        CUSTOMER)
                .isEqualTo(Reason.CART_MISMATCH);
        assertRejected(token, List.of(SkuQuantity.of("MILK-FULL-1L", 2)), CUSTOMER)
                .isEqualTo(Reason.CART_MISMATCH);
        assertRejected(
                        token,
                        List.of(
                                SkuQuantity.of("MILK-FULL-1L", 2),
                                SkuQuantity.of("BREAD-ARABIC-6PK", 1),
                                SkuQuantity.of("EGGS-12", 1)),
                        CUSTOMER)
                .isEqualTo(Reason.CART_MISMATCH);
        assertRejected(token, List.of(), CUSTOMER).isEqualTo(Reason.CART_MISMATCH);
    }

    @Test
    void aDeliveryLocationWithinTheToleranceIsTheSamePlace() {
        assertThat(verifier.verify(token, BREAKFAST, north(CUSTOMER, TOLERANCE_METERS - 0.01)))
                .isNotNull();
        assertThat(verifier.verify(token, BREAKFAST, north(CUSTOMER, -(TOLERANCE_METERS - 0.01))))
                .isNotNull();
    }

    @Test
    void aDeliveryLocationBeyondTheToleranceIsALocationMismatch() {
        assertRejected(token, BREAKFAST, north(CUSTOMER, TOLERANCE_METERS + 0.01))
                .isEqualTo(Reason.LOCATION_MISMATCH);
        assertRejected(token, BREAKFAST, GeoPoint.of(25.1972, 55.2744)).isEqualTo(Reason.LOCATION_MISMATCH);
    }

    @Test
    void theToleranceIsConfigurable() {
        CheckoutPromiseVerifier lenient = new CheckoutPromiseVerifier(codec, stores, clock, 500);

        assertThat(lenient.verify(token, BREAKFAST, north(CUSTOMER, 450))).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(
            value = StoreStatus.class,
            names = {"PAUSED", "CLOSED"})
    void aPromiseFromAStoreThatStoppedAcceptingOrdersIsNoLongerHonoured(StoreStatus status) {
        stores.with(MARINA, status);

        assertRejected(token, BREAKFAST, CUSTOMER).isEqualTo(Reason.STORE_UNAVAILABLE);
    }

    @Test
    void aBusyStoreStillAcceptsOrders() {
        stores.with(MARINA, StoreStatus.BUSY);

        assertThat(verifier.verify(token, BREAKFAST, CUSTOMER).storeId()).isEqualTo(MARINA);
    }

    @Test
    void aPromiseFromAStoreTheNetworkNoLongerKnowsIsNoLongerHonoured() {
        CheckoutPromiseVerifier withoutMarina =
                new CheckoutPromiseVerifier(codec, new FakeStoreDirectory(), clock, TOLERANCE_METERS);

        assertThatExceptionOfType(InvalidPromiseException.class)
                .isThrownBy(() -> withoutMarina.verify(token, BREAKFAST, CUSTOMER))
                .extracting(InvalidPromiseException::reason)
                .isEqualTo(Reason.STORE_UNAVAILABLE);
    }

    /** Format and signature, then expiry, then basket, then location, then store status. */
    @Test
    void checksInTheDocumentedOrder() {
        List<SkuQuantity> otherBasket = List.of(SkuQuantity.of("EGGS-12", 1));
        GeoPoint elsewhere = north(CUSTOMER, 5_000);
        stores.with(MARINA, StoreStatus.PAUSED);

        assertRejected(token, BREAKFAST, elsewhere).as("location before store").isEqualTo(Reason.LOCATION_MISMATCH);
        assertRejected(token, otherBasket, elsewhere)
                .as("basket before location")
                .isEqualTo(Reason.CART_MISMATCH);
        clock.set(EXPIRES_AT);
        assertRejected(token, otherBasket, elsewhere).as("expiry before basket").isEqualTo(Reason.EXPIRED);
        assertRejected(token + "x", otherBasket, elsewhere)
                .as("signature first")
                .isEqualTo(Reason.TAMPERED);
        clock.set(ISSUED_AT.plus(Duration.ofDays(2)));
        assertRejected(forge(ISSUED_AT, ISSUED_AT.plus(Duration.ofDays(1))), otherBasket, elsewhere)
                .as("claims we never issue before expiry")
                .isEqualTo(Reason.MALFORMED);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, -1, Double.NaN, Double.POSITIVE_INFINITY})
    void refusesAToleranceThatIsNotAPositiveDistance(double tolerance) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CheckoutPromiseVerifier(codec, stores, clock, tolerance));
    }

    private AbstractObjectAssert<?, Reason> assertRejected(String token, List<SkuQuantity> lines, GeoPoint location) {
        return assertThatExceptionOfType(InvalidPromiseException.class)
                .isThrownBy(() -> verifier.verify(token, lines, location))
                .extracting(InvalidPromiseException::reason);
    }

    /**
     * A correctly signed token, as anyone holding the key could sign it: one minute from Marina for {@code BREAKFAST}
     * at {@code CUSTOMER}, with the given times.
     */
    private static String forge(Instant issuedAt, Instant expiresAt) {
        TokenClaims claims = new TokenClaims(
                PromiseId.newId(),
                MARINA,
                DeliveryTier.EXPRESS_15,
                1,
                issuedAt.getEpochSecond(),
                expiresAt.getEpochSecond(),
                BasketHash.of(BREAKFAST),
                TokenClaims.toMicroDegrees(CUSTOMER.latitude()),
                TokenClaims.toMicroDegrees(CUSTOMER.longitude()));
        try {
            return Tokens.sign("k1", key(1), claims.encode());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code meters} due north (south when negative): along a meridian the great-circle distance is exact. */
    private static GeoPoint north(GeoPoint point, double meters) {
        return GeoPoint.of(point.latitude() + Math.toDegrees(meters / EARTH_RADIUS_METERS), point.longitude());
    }
}
