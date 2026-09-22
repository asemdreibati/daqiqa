package io.daqiqa.promise.application;

import static io.daqiqa.promise.application.Tokens.key;
import static io.daqiqa.promise.application.Tokens.payloadOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.kernel.StoreId;
import io.daqiqa.promise.MutableClock;
import io.daqiqa.promise.api.DeliveryTier;
import io.daqiqa.promise.api.InvalidPromiseException;
import io.daqiqa.promise.api.InvalidPromiseException.Reason;
import io.daqiqa.promise.api.VerifiedPromise;
import io.daqiqa.promise.domain.Basket;
import io.daqiqa.promise.domain.EtaBreakdown;
import io.daqiqa.promise.domain.Quote;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.AbstractObjectAssert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PromiseTokenCodecTest {

    /** Deliberately not on a whole second: tokens carry whole seconds. */
    private static final Instant NOW = Instant.parse("2026-09-22T12:00:00.750Z");

    private static final Instant ISSUED_AT = Instant.parse("2026-09-22T12:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-22T12:01:30Z");

    private static final List<SkuQuantity> BREAKFAST =
            List.of(SkuQuantity.of("MILK-FULL-1L", 2), SkuQuantity.of("BREAD-ARABIC-6PK", 1));
    private static final Quote QUOTE =
            new Quote(StoreId.of("DXB-MARINA"), DeliveryTier.EXPRESS_15, new EtaBreakdown(60, 69, 60, 330, 90));

    private final MutableClock clock = new MutableClock(NOW);
    private final PromiseTokenCodec codec = codec(Map.of("k1", key(1)), "k1");

    @Test
    void issuesATokenInTheDocumentedFormat() throws Exception {
        IssuedPromise issued = codec.issue(QUOTE, Basket.of(BREAKFAST));
        String[] parts = issued.token().split("\\.", -1);

        assertThat(parts).hasSize(4);
        assertThat(parts[0]).isEqualTo("v1");
        assertThat(parts[1]).isEqualTo("k1");
        assertThat(issued.token()).matches("[A-Za-z0-9._-]+"); // base64url, no padding
        assertThat(payloadOf(issued.token()))
                .isEqualTo(String.join(
                        "|",
                        issued.promiseId().toString(),
                        "DXB-MARINA",
                        "EXPRESS_15",
                        "11",
                        Long.toString(ISSUED_AT.getEpochSecond()),
                        Long.toString(EXPIRES_AT.getEpochSecond()),
                        Tokens.sha256("BREAD-ARABIC-6PK:1,MILK-FULL-1L:2")));
        assertThat(issued.token()).isEqualTo(Tokens.sign("k1", key(1), payloadOf(issued.token())));
        assertThat(issued.expiresAt()).isEqualTo(EXPIRES_AT);
    }

    @Test
    void aTokenVerifiesForTheBasketItWasIssuedFor() {
        IssuedPromise issued = codec.issue(QUOTE, Basket.of(BREAKFAST));

        VerifiedPromise verified = codec.verify(issued.token(), BREAKFAST);

        assertThat(verified)
                .isEqualTo(new VerifiedPromise(
                        issued.promiseId(),
                        StoreId.of("DXB-MARINA"),
                        DeliveryTier.EXPRESS_15,
                        11,
                        ISSUED_AT,
                        EXPIRES_AT));
    }

    @Test
    void theSameBasketListedInAnotherOrderOrWithSplitLinesStillMatches() {
        String token = codec.issue(QUOTE, Basket.of(BREAKFAST)).token();

        List<SkuQuantity> reordered = List.of(
                SkuQuantity.of("BREAD-ARABIC-6PK", 1),
                SkuQuantity.of("MILK-FULL-1L", 1),
                SkuQuantity.of("MILK-FULL-1L", 1));

        assertThat(codec.verify(token, reordered).storeId()).isEqualTo(StoreId.of("DXB-MARINA"));
    }

    @Test
    void anotherBasketIsACartMismatch() {
        String token = codec.issue(QUOTE, Basket.of(BREAKFAST)).token();

        assertRejected(token, List.of(SkuQuantity.of("MILK-FULL-1L", 3), SkuQuantity.of("BREAD-ARABIC-6PK", 1)))
                .isEqualTo(Reason.CART_MISMATCH);
        assertRejected(token, List.of(SkuQuantity.of("MILK-FULL-1L", 2))).isEqualTo(Reason.CART_MISMATCH);
        assertRejected(
                        token,
                        List.of(
                                SkuQuantity.of("MILK-FULL-1L", 2),
                                SkuQuantity.of("BREAD-ARABIC-6PK", 1),
                                SkuQuantity.of("EGGS-12", 1)))
                .isEqualTo(Reason.CART_MISMATCH);
        assertRejected(token, List.of()).isEqualTo(Reason.CART_MISMATCH);
    }

    @Test
    void aTokenWhosePayloadWasAlteredIsTampered() {
        String token = codec.issue(QUOTE, Basket.of(BREAKFAST)).token();
        String[] parts = token.split("\\.");
        String fasterPromise = payloadOf(token).replace("|11|", "|5|");

        String forged = parts[0] + "." + parts[1] + "." + Tokens.base64Url(fasterPromise) + "." + parts[3];

        assertRejected(forged, BREAKFAST).isEqualTo(Reason.TAMPERED);
    }

    @Test
    void aTokenWhoseSignatureWasAlteredIsTampered() {
        String token = codec.issue(QUOTE, Basket.of(BREAKFAST)).token();
        char last = token.charAt(token.length() - 1);

        String forged = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');

        assertRejected(forged, BREAKFAST).isEqualTo(Reason.TAMPERED);
    }

    @Test
    void aTokenSignedWithAnotherSecretUnderTheSameKeyIdIsTampered() {
        String forged = codec(Map.of("k1", key(9)), "k1")
                .issue(QUOTE, Basket.of(BREAKFAST))
                .token();

        assertRejected(forged, BREAKFAST).isEqualTo(Reason.TAMPERED);
    }

    @Test
    void aTokenIsValidUntilTheSecondItExpires() {
        String token = codec.issue(QUOTE, Basket.of(BREAKFAST)).token();

        clock.set(EXPIRES_AT.minusMillis(1));
        assertThat(codec.verify(token, BREAKFAST).expiresAt()).isEqualTo(EXPIRES_AT);

        clock.set(EXPIRES_AT);
        assertRejected(token, BREAKFAST).isEqualTo(Reason.EXPIRED);

        clock.advance(Duration.ofHours(1));
        assertRejected(token, BREAKFAST).isEqualTo(Reason.EXPIRED);
    }

    @Test
    void aTokenSignedWithAnUnknownKeyIsMalformed() {
        String token = codec.issue(QUOTE, Basket.of(BREAKFAST)).token();

        assertRejected(token.replaceFirst("^v1\\.k1\\.", "v1.k9."), BREAKFAST).isEqualTo(Reason.MALFORMED);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "garbage",
                "v1.k1.cGF5bG9hZA",
                "v1.k1.cGF5bG9hZA.c2ln.extra",
                "v2.k1.cGF5bG9hZA.c2ln",
                "v1.k1..c2ln",
                "v1.k1.cGF5bG9hZA==.c2ln",
                "v1.k1.cGF5+bG9hZA.c2ln"
            })
    void somethingThatIsNotAPromiseTokenIsMalformed(String token) {
        assertRejected(token, BREAKFAST).isEqualTo(Reason.MALFORMED);
    }

    @Test
    void anOversizedTokenIsMalformedWithoutFurtherWork() {
        String token = codec.issue(QUOTE, Basket.of(BREAKFAST)).token();

        assertRejected(token + "A".repeat(PromiseTokenCodec.MAX_TOKEN_LENGTH), BREAKFAST)
                .isEqualTo(Reason.MALFORMED);
    }

    @Test
    void aCorrectlySignedPayloadThatIsNotAPromiseIsMalformed() throws Exception {
        assertRejected(Tokens.sign("k1", key(1), "not|a|promise"), BREAKFAST).isEqualTo(Reason.MALFORMED);
    }

    @Test
    void theSignatureIsCheckedBeforeTheExpiryAndTheExpiryBeforeTheBasket() {
        String token = codec.issue(QUOTE, Basket.of(BREAKFAST)).token();
        clock.set(EXPIRES_AT.plusSeconds(1));

        assertRejected(token + "x", BREAKFAST).isEqualTo(Reason.TAMPERED);
        assertRejected(token, List.of(SkuQuantity.of("EGGS-12", 1))).isEqualTo(Reason.EXPIRED);
    }

    @Test
    void keysRotateWithoutInvalidatingTokensInFlight() {
        String signedWithOldKey = codec.issue(QUOTE, Basket.of(BREAKFAST)).token();

        // Step 1: every instance gets the new key and makes it active; the old key still verifies.
        PromiseTokenCodec rotated = codec(Map.of("k1", key(1), "k2", key(2)), "k2");
        String signedWithNewKey = rotated.issue(QUOTE, Basket.of(BREAKFAST)).token();

        assertThat(signedWithNewKey).startsWith("v1.k2.");
        assertThat(rotated.verify(signedWithOldKey, BREAKFAST)).isNotNull();
        assertThat(rotated.verify(signedWithNewKey, BREAKFAST)).isNotNull();

        // Step 2, once old tokens have expired anyway: the old key is removed.
        PromiseTokenCodec retired = codec(Map.of("k2", key(2)), "k2");

        assertThat(retired.verify(signedWithNewKey, BREAKFAST)).isNotNull();
        assertThatExceptionOfType(InvalidPromiseException.class)
                .isThrownBy(() -> retired.verify(signedWithOldKey, BREAKFAST))
                .extracting(InvalidPromiseException::reason)
                .isEqualTo(Reason.MALFORMED);
    }

    @Test
    void everyPromiseHasItsOwnId() {
        IssuedPromise first = codec.issue(QUOTE, Basket.of(BREAKFAST));
        IssuedPromise second = codec.issue(QUOTE, Basket.of(BREAKFAST));

        assertThat(first.promiseId()).isNotEqualTo(second.promiseId());
        assertThat(first.token()).isNotEqualTo(second.token());
    }

    @Test
    void refusesAValidityThatIsNotShortLived() {
        SigningKeys keys = new SigningKeys(Map.of("k1", key(1)), "k1");

        assertThatIllegalArgumentException().isThrownBy(() -> new PromiseTokenCodec(keys, Duration.ZERO, clock));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PromiseTokenCodec(keys, Duration.ofMinutes(61), clock));
    }

    private AbstractObjectAssert<?, Reason> assertRejected(String token, List<SkuQuantity> lines) {
        return assertThatExceptionOfType(InvalidPromiseException.class)
                .isThrownBy(() -> codec.verify(token, lines))
                .extracting(InvalidPromiseException::reason);
    }

    private PromiseTokenCodec codec(Map<String, byte[]> keys, String activeKeyId) {
        return new PromiseTokenCodec(new SigningKeys(keys, activeKeyId), Duration.ofSeconds(90), clock);
    }
}
