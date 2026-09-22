package io.daqiqa.promise.application;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** An independent implementation of the token format in ADR-0007, to check the codec against. */
final class Tokens {

    private Tokens() {}

    static byte[] key(int fill) {
        byte[] key = new byte[SigningKeys.MIN_KEY_BYTES];
        Arrays.fill(key, (byte) fill);
        return key;
    }

    static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String base64Url(String text) {
        return base64Url(text.getBytes(StandardCharsets.UTF_8));
    }

    static String payloadOf(String token) {
        return new String(Base64.getUrlDecoder().decode(token.split("\\.")[2]), StandardCharsets.UTF_8);
    }

    static String sha256(String text) throws GeneralSecurityException {
        return base64Url(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    /** {@code v1.<kid>.<base64url payload>.<base64url HMAC-SHA256>}, signed with {@code key}. */
    static String sign(String keyId, byte[] key, String payload) throws GeneralSecurityException {
        String signed = "v1." + keyId + "." + base64Url(payload);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return signed + "." + base64Url(mac.doFinal(signed.getBytes(StandardCharsets.US_ASCII)));
    }
}
