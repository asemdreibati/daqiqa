package io.daqiqa.ordering.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * SHA-256 of a request's canonical form, stored with its idempotency key: a retry is "the same request" exactly when
 * its fingerprint matches.
 *
 * @param value 64 lower-case hex digits
 */
public record RequestFingerprint(String value) {

    private static final Pattern FORMAT = Pattern.compile("^[0-9a-f]{64}$");

    public RequestFingerprint {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("A fingerprint is a hex SHA-256, was " + value);
        }
    }

    static RequestFingerprint of(String canonicalForm) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalForm.getBytes(StandardCharsets.UTF_8));
            return new RequestFingerprint(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java platform must support SHA-256", e);
        }
    }
}
