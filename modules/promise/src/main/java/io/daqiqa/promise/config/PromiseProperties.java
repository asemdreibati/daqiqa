package io.daqiqa.promise.config;

import io.daqiqa.promise.application.SigningKeys;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration of the promise context. Everything has a default except the token signing key, which must be supplied
 * (the application refuses to start without one: an unsigned or weakly signed promise could be forged).
 *
 * @param pick picking and packing time, {@code base + perUnit × units} capped at {@code max}
 * @param load waits at a store by status (v1 load model)
 * @param buffer safety margin added to every ETA (v1 calibration)
 * @param token promise token signing
 */
@ConfigurationProperties("daqiqa.promise")
record PromiseProperties(
        @DefaultValue Pick pick,
        @DefaultValue Load load,
        @DefaultValue("90s") Duration buffer,
        @DefaultValue Token token) {

    record Pick(
            @DefaultValue("45s") Duration base,
            @DefaultValue("8s") Duration perUnit,
            @DefaultValue("600s") Duration max) {}

    /** Waits for a picker ({@code queue}) and then for a rider ({@code rider-wait}), by store status. */
    record Load(@DefaultValue Open open, @DefaultValue Busy busy) {

        record Open(
                @DefaultValue("60s") Duration queue,
                @DefaultValue("60s") Duration riderWait) {}

        record Busy(
                @DefaultValue("300s") Duration queue,
                @DefaultValue("180s") Duration riderWait) {}
    }

    /**
     * @param activeKeyId id of the key that signs new tokens
     * @param keys signing keys by id, base64-encoded, at least 32 bytes each; keys other than the active one only
     *     verify (rotation)
     * @param validity how long a promise can be turned into an order
     */
    record Token(
            @Nullable String activeKeyId,
            @DefaultValue Map<String, String> keys,
            @DefaultValue("90s") Duration validity) {

        private static final String PREFIX = "daqiqa.promise.token.";

        /** Fails fast, naming the property to fix, when signing is not configured safely. */
        Token {
            if (activeKeyId == null || activeKeyId.isBlank()) {
                throw new IllegalStateException("No promise token signing key is configured: set " + PREFIX
                        + "active-key-id=<id> and " + PREFIX + "keys.<id>=<base64 key of at least "
                        + SigningKeys.MIN_KEY_BYTES + " bytes> (for example from 'openssl rand -base64 32')");
            }
            if (!keys.containsKey(activeKeyId)) {
                throw new IllegalStateException(PREFIX + "active-key-id is '" + activeKeyId + "' but " + PREFIX
                        + "keys." + activeKeyId + " is not set");
            }
            keys = Map.copyOf(keys);
            keys.forEach(Token::decode);
        }

        /** The keys as bytes. */
        Map<String, byte[]> decodedKeys() {
            return keys.entrySet().stream()
                    .collect(Collectors.toUnmodifiableMap(
                            Map.Entry::getKey, key -> decode(key.getKey(), key.getValue())));
        }

        private static byte[] decode(String id, String base64) {
            byte[] key;
            try {
                key = Base64.getDecoder().decode(base64.strip());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(PREFIX + "keys." + id + " is not valid base64", e);
            }
            if (key.length < SigningKeys.MIN_KEY_BYTES) {
                throw new IllegalStateException(PREFIX + "keys." + id + " is " + key.length
                        + " bytes long; a signing key must have at least " + SigningKeys.MIN_KEY_BYTES + " bytes");
            }
            return key;
        }
    }
}
