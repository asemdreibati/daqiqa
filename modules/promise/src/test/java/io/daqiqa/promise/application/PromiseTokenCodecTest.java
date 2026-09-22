package io.daqiqa.promise.application;

import static io.daqiqa.promise.application.Tokens.key;
import static io.daqiqa.promise.application.Tokens.payloadOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.kernel.StoreId;
import io.daqiqa.promise.MutableClock;
import io.daqiqa.promise.api.DeliveryTier;
import io.daqiqa.promise.api.InvalidPromiseException;
import io.daqiqa.promise.api.InvalidPromiseException.Reason;
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

/** The token format and its cryptography; whether a token holds for an order is {@link CheckoutPromiseVerifierTest}. */
class PromiseTokenCodecTest {

    /** Deliberately not on a whole second: tokens carry whole seconds. */
    static final Instant NOW = Instant.parse("2026-09-22T12:00:00.750Z");

    static final Instant ISSUED_AT = Instant.parse("2026-09-22T12:00:00Z");
    static final Instant EXPIRES_AT = Instant.parse("2026-09-22T12:01:30Z");
    static final GeoPoint CUSTOMER = GeoPoint.of(25.0772, 55.139);
    static final List<SkuQuantity> BREAKFAST =
            List.of(SkuQuantity.of("MILK-FULL-1L", 2), SkuQuantity.of("BREAD-ARABIC-6PK", 1));
    static final Quote QUOTE =
            new Quote(StoreId.of("DXB-MARINA"), DeliveryTier.EXPRESS_15, new EtaBreakdown(60, 69, 60, 330, 90));

    private final MutableClock clock = new MutableClock(NOW);
    private final PromiseTokenCodec codec = codec(Map.of("k1", key(1)), "k1");

    @Test
    void issuesATokenInTheDocumentedFormat() throws Exception {
        IssuedPromise issued = issue(codec);
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
                        Tokens.sha256("BREAD-ARABIC-6PK:1,MILK-FULL-1L:2"),
                        "25077200",
                        "55139000"));
        assertThat(issued.token()).isEqualTo(Tokens.sign("k1", key(1), payloadOf(issued.token())));
        assertThat(issued.expiresAt()).isEqualTo(EXPIRES_AT);
    }

    @Test
    void authenticatesATokenItIssued() {
        IssuedPromise issued = issue(codec);

        TokenClaims claims = codec.authenticate(issued.token());

        assertThat(claims.promiseId()).isEqualTo(issued.promiseId());
        assertThat(claims.storeId()).isEqualTo(StoreId.of("DXB-MARINA"));
        assertThat(claims.tier()).isEqualTo(DeliveryTier.EXPRESS_15);
        assertThat(claims.etaMinutes()).isEqualTo(11);
        assertThat(claims.expiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(claims.location()).isEqualTo(CUSTOMER);
    }

    @Test
    void aTokenWhosePayloadWasAlteredIsTampered() {
        String token = issue(codec).token();
        String[] parts = token.split("\\.");

        String fasterPromise = payloadOf(token).replace("|11|", "|5|");
        String elsewhere = payloadOf(token).replace("|55139000", "|55239000");

        assertRejected(parts[0] + "." + parts[1] + "." + Tokens.base64Url(fasterPromise) + "." + parts[3])
                .isEqualTo(Reason.TAMPERED);
        assertRejected(parts[0] + "." + parts[1] + "." + Tokens.base64Url(elsewhere) + "." + parts[3])
                .isEqualTo(Reason.TAMPERED);
    }

    @Test
    void aTokenWhoseSignatureWasAlteredIsTampered() {
        String token = issue(codec).token();
        char last = token.charAt(token.length() - 1);

        assertRejected(token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A'))
                .isEqualTo(Reason.TAMPERED);
    }

    @Test
    void aTokenSignedWithAnotherSecretUnderTheSameKeyIdIsTampered() {
        assertRejected(issue(codec(Map.of("k1", key(9)), "k1")).token()).isEqualTo(Reason.TAMPERED);
    }

    @Test
    void aTokenSignedWithAnUnknownKeyIsMalformed() {
        assertRejected(issue(codec).token().replaceFirst("^v1\\.k1\\.", "v1.k9."))
                .isEqualTo(Reason.MALFORMED);
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
        assertRejected(token).isEqualTo(Reason.MALFORMED);
    }

    @Test
    void anOversizedTokenIsMalformedWithoutFurtherWork() {
        assertRejected(issue(codec).token() + "A".repeat(PromiseTokenCodec.MAX_TOKEN_LENGTH))
                .isEqualTo(Reason.MALFORMED);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "not|a|promise",
                // the seven fields of an earlier draft, without the location
                "0192f7c4-7c1e-7cc2-9d3a-2f1a6c0e9b11|DXB-MARINA|EXPRESS_15|11|1790078400|1790078490|hash",
                // a latitude off the globe
                "0192f7c4-7c1e-7cc2-9d3a-2f1a6c0e9b11|DXB-MARINA|EXPRESS_15|11|1790078400|1790078490|hash|95000000|0"
            })
    void aCorrectlySignedPayloadThatIsNotAPromiseIsMalformed(String payload) throws Exception {
        assertRejected(Tokens.sign("k1", key(1), payload)).isEqualTo(Reason.MALFORMED);
    }

    @Test
    void keysRotateWithoutInvalidatingTokensInFlight() {
        String signedWithOldKey = issue(codec).token();

        // Step 1: every instance gets the new key and makes it active; the old key still verifies.
        PromiseTokenCodec rotated = codec(Map.of("k1", key(1), "k2", key(2)), "k2");
        String signedWithNewKey = issue(rotated).token();

        assertThat(signedWithNewKey).startsWith("v1.k2.");
        assertThat(rotated.authenticate(signedWithOldKey).storeId()).isEqualTo(QUOTE.storeId());
        assertThat(rotated.authenticate(signedWithNewKey).storeId()).isEqualTo(QUOTE.storeId());

        // Step 2, once old tokens have expired anyway: the old key is removed.
        PromiseTokenCodec retired = codec(Map.of("k2", key(2)), "k2");

        assertThat(retired.authenticate(signedWithNewKey).storeId()).isEqualTo(QUOTE.storeId());
        assertThatExceptionOfType(InvalidPromiseException.class)
                .isThrownBy(() -> retired.authenticate(signedWithOldKey))
                .extracting(InvalidPromiseException::reason)
                .isEqualTo(Reason.MALFORMED);
    }

    @Test
    void everyPromiseHasItsOwnId() {
        IssuedPromise first = issue(codec);
        IssuedPromise second = issue(codec);

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

    private AbstractObjectAssert<?, Reason> assertRejected(String token) {
        return assertThatExceptionOfType(InvalidPromiseException.class)
                .isThrownBy(() -> codec.authenticate(token))
                .extracting(InvalidPromiseException::reason);
    }

    private static IssuedPromise issue(PromiseTokenCodec codec) {
        return codec.issue(QUOTE, Basket.of(BREAKFAST), CUSTOMER);
    }

    private PromiseTokenCodec codec(Map<String, byte[]> keys, String activeKeyId) {
        return new PromiseTokenCodec(new SigningKeys(keys, activeKeyId), Duration.ofSeconds(90), clock);
    }
}
