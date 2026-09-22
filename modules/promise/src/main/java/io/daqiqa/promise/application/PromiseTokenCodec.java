package io.daqiqa.promise.application;

import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.kernel.PromiseId;
import io.daqiqa.promise.api.InvalidPromiseException;
import io.daqiqa.promise.api.InvalidPromiseException.Reason;
import io.daqiqa.promise.domain.Basket;
import io.daqiqa.promise.domain.Quote;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

/**
 * The format and cryptography of promise tokens (ADR-0007): {@code v1.<kid>.<payload>.<signature>}.
 *
 * <ul>
 *   <li>{@code payload} is the base64url (no padding) of {@link TokenClaims}: promise id, store, tier, ETA, issue and
 *       expiry (epoch seconds), the {@link BasketHash basket hash} and the location (micro-degrees).
 *   <li>{@code signature} is the base64url (no padding) HMAC-SHA256 of {@code v1.<kid>.<payload>} under the key named
 *       {@code kid}, so the version and key id are authenticated too.
 * </ul>
 *
 * <p>Whether an authentic token still holds for an order is {@link CheckoutPromiseVerifier}'s business.
 */
public final class PromiseTokenCodec {

    static final String VERSION = "v1";

    /** Far above any token issued here (about 280 characters): longer input is rejected before any work is done. */
    static final int MAX_TOKEN_LENGTH = 1_024;

    /** Longest accepted validity: an ETA computed an hour ago says little about now. */
    static final Duration MAX_VALIDITY = Duration.ofHours(1);

    private static final Pattern BASE64URL = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SigningKeys keys;
    private final Duration validity;
    private final Clock clock;

    /** @param validity how long a promise can be turned into an order; whole seconds count */
    public PromiseTokenCodec(SigningKeys keys, Duration validity, Clock clock) {
        this.keys = Objects.requireNonNull(keys, "keys");
        this.validity = Objects.requireNonNull(validity, "validity");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (validity.toSeconds() < 1 || validity.compareTo(MAX_VALIDITY) > 0) {
            throw new IllegalArgumentException(
                    "Promise validity must be between 1 second and " + MAX_VALIDITY + ", was " + validity);
        }
    }

    /**
     * Signs {@code quote} for {@code basket} delivered to {@code location}, with the active key. The token is valid
     * from now, truncated to the second, for the configured validity; {@link IssuedPromise#expiresAt()} is exactly the
     * expiry inside the token.
     */
    public IssuedPromise issue(Quote quote, Basket basket, GeoPoint location) {
        long issuedAt = clock.instant().getEpochSecond();
        TokenClaims claims = new TokenClaims(
                PromiseId.newId(),
                quote.storeId(),
                quote.tier(),
                quote.etaMinutes(),
                issuedAt,
                issuedAt + validity.toSeconds(),
                BasketHash.of(basket.lines()),
                TokenClaims.toMicroDegrees(location.latitude()),
                TokenClaims.toMicroDegrees(location.longitude()));
        String signed = VERSION + "." + keys.activeKeyId() + "." + encode(claims.encode());
        String token = signed + "." + signature(keys.activeKey(), signed);
        return new IssuedPromise(claims.promiseId(), quote, claims.expiresAt(), token);
    }

    /**
     * What an authentic token asserts. Checks the format and key id ({@code MALFORMED}), then the signature
     * ({@code TAMPERED}); nothing is decoded or interpreted before the signature has been checked.
     *
     * @throws InvalidPromiseException with reason {@code MALFORMED} or {@code TAMPERED}, and no other exception
     */
    TokenClaims authenticate(String token) {
        String[] parts = token.length() <= MAX_TOKEN_LENGTH ? token.split("\\.", -1) : new String[0];
        if (parts.length != 4
                || !VERSION.equals(parts[0])
                || !BASE64URL.matcher(parts[2]).matches()
                || !BASE64URL.matcher(parts[3]).matches()) {
            throw new InvalidPromiseException(Reason.MALFORMED, "Not a promise token");
        }
        SecretKey key = keys.find(parts[1])
                .orElseThrow(() -> new InvalidPromiseException(Reason.MALFORMED, "Unknown signing key"));

        // Compare the expected signature with the one presented as text, in constant time: no timing side channel
        // reveals how many leading characters were right, and only the canonical encoding is accepted.
        String expected = signature(key, parts[0] + "." + parts[1] + "." + parts[2]);
        if (!MessageDigest.isEqual(ascii(expected), ascii(parts[3]))) {
            throw new InvalidPromiseException(Reason.TAMPERED, "The promise token was altered");
        }
        return claimsIn(parts[2])
                .orElseThrow(() ->
                        new InvalidPromiseException(Reason.MALFORMED, "The promise token has an unknown payload"));
    }

    private static String signature(SecretKey key, String signed) {
        try {
            Mac mac = Mac.getInstance(SigningKeys.ALGORITHM);
            mac.init(key);
            return ENCODER.encodeToString(mac.doFinal(ascii(signed)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Every Java runtime provides " + SigningKeys.ALGORITHM, e);
        }
    }

    private static String encode(String text) {
        return ENCODER.encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The claims in a payload whose signature is valid. Only a payload signed with one of our keys by something other
     * than this codec (another version, a bug) can fail to parse.
     */
    private static Optional<TokenClaims> claimsIn(String payload) {
        byte[] decoded;
        try {
            decoded = DECODER.decode(payload);
        } catch (IllegalArgumentException notBase64) {
            return Optional.empty();
        }
        return TokenClaims.decode(new String(decoded, StandardCharsets.UTF_8));
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
