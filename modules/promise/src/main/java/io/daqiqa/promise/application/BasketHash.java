package io.daqiqa.promise.application;

import io.daqiqa.kernel.SkuQuantity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The basket a token was issued for, as a hash: SHA-256 over the canonical basket (duplicate SKUs merged, sorted by
 * SKU, formatted {@code SKU:qty} and joined with {@code ,}), base64url without padding.
 *
 * <p>Canonical, so that the same basket listed in another order or with split lines has the same hash at checkout.
 */
final class BasketHash {

    private BasketHash() {}

    static String of(Collection<SkuQuantity> lines) {
        Map<String, Integer> merged = new TreeMap<>();
        lines.forEach(line -> merged.merge(line.sku().value(), line.quantity(), Math::addExact));
        String canonical = merged.entrySet().stream()
                .map(line -> line.getKey() + ":" + line.getValue())
                .collect(Collectors.joining(","));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(canonical));
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime provides SHA-256", e);
        }
    }
}
