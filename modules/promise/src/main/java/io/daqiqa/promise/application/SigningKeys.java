package io.daqiqa.promise.application;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * The HMAC-SHA256 keys that sign and verify promise tokens, by key id ({@code kid}).
 *
 * <p>One key is active and signs new tokens; the others only verify. Rotation without downtime: add the new key, make
 * it active on every instance, and remove the old one once the tokens it signed have expired.
 */
public final class SigningKeys {

    /** HMAC-SHA256 keys shorter than its 256-bit output weaken it (RFC 2104 §3). */
    public static final int MIN_KEY_BYTES = 32;

    static final String ALGORITHM = "HmacSHA256";

    /** Key ids appear in tokens between dots, so they are restricted to URL-safe characters. */
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final Map<String, SecretKey> keys;
    private final String activeKeyId;
    private final SecretKey activeKey;

    /**
     * @param keys raw key bytes by key id
     * @param activeKeyId the key that signs new tokens; must be one of {@code keys}
     */
    public SigningKeys(Map<String, byte[]> keys, String activeKeyId) {
        Objects.requireNonNull(activeKeyId, "activeKeyId");
        keys.forEach((id, key) -> {
            if (!KEY_ID.matcher(id).matches()) {
                throw new IllegalArgumentException(
                        "Invalid signing key id '" + id + "': use 1 to 32 letters, digits, '-' or '_'");
            }
            if (key.length < MIN_KEY_BYTES) {
                throw new IllegalArgumentException("Signing key '" + id + "' has " + key.length + " bytes; at least "
                        + MIN_KEY_BYTES + " are required");
            }
        });
        if (!keys.containsKey(activeKeyId)) {
            throw new IllegalArgumentException("The active signing key '" + activeKeyId + "' is not configured");
        }
        this.keys = keys.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> new SecretKeySpec(entry.getValue(), ALGORITHM)));
        this.activeKeyId = activeKeyId;
        this.activeKey = new SecretKeySpec(keys.get(activeKeyId), ALGORITHM);
    }

    String activeKeyId() {
        return activeKeyId;
    }

    SecretKey activeKey() {
        return activeKey;
    }

    Optional<SecretKey> find(String keyId) {
        return Optional.ofNullable(keys.get(keyId));
    }
}
