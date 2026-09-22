package io.daqiqa.promise.application;

import static io.daqiqa.promise.application.PromiseTokenCodecTest.BREAKFAST;
import static io.daqiqa.promise.application.PromiseTokenCodecTest.CUSTOMER;
import static io.daqiqa.promise.application.PromiseTokenCodecTest.NOW;
import static io.daqiqa.promise.application.PromiseTokenCodecTest.QUOTE;
import static io.daqiqa.promise.application.Tokens.key;
import static org.assertj.core.api.Assertions.assertThat;

import io.daqiqa.kernel.StoreId;
import io.daqiqa.network.api.StoreStatus;
import io.daqiqa.promise.MutableClock;
import io.daqiqa.promise.api.InvalidPromiseException;
import io.daqiqa.promise.api.InvalidPromiseException.Reason;
import io.daqiqa.promise.domain.Basket;
import java.time.Duration;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.statistics.Statistics;

/**
 * Fuzzing: random text and mutated tokens are only ever rejected as MALFORMED or TAMPERED; no other exception, and no
 * other reason, can escape the verifier.
 */
class PromiseTokenFuzzTest {

    private static final StoreId MARINA = StoreId.of("DXB-MARINA");

    private final MutableClock clock = new MutableClock(NOW);
    private final PromiseTokenCodec codec =
            new PromiseTokenCodec(new SigningKeys(Map.of("k1", key(1)), "k1"), Duration.ofSeconds(90), clock);
    private final CheckoutPromiseVerifier verifier =
            new CheckoutPromiseVerifier(codec, new FakeStoreDirectory().with(MARINA, StoreStatus.OPEN), clock, 100);
    private final String token =
            codec.issue(QUOTE, Basket.of(BREAKFAST), CUSTOMER).token();

    /** Whatever arrives as a token, the only failure is a MALFORMED or TAMPERED rejection. */
    @Property
    void randomTextIsRejectedAsMalformedOrTampered(@ForAll("tokenLikeText") String text) {
        Reason reason = rejection(text);

        Statistics.collect(reason);
        assertThat(reason).isIn(Reason.MALFORMED, Reason.TAMPERED);
        // Enough lookalikes must get as far as the signature check for this property to mean something.
        Statistics.coverage(coverage -> coverage.check(Reason.TAMPERED).percentage(percent -> percent >= 5));
    }

    /** An issued token changed anywhere, in any way, is rejected as MALFORMED or TAMPERED: nothing else escapes. */
    @Property
    void aMutatedTokenIsRejectedAsMalformedOrTampered(@ForAll("mutations") Mutation mutation) {
        String mutated = mutation.applyTo(token);

        if (mutated.equals(token)) {
            assertThat(verifier.verify(mutated, BREAKFAST, CUSTOMER).storeId()).isEqualTo(MARINA);
            return;
        }
        Reason reason = rejection(mutated);
        Statistics.collect(reason);
        assertThat(reason).isIn(Reason.MALFORMED, Reason.TAMPERED);
        Statistics.coverage(coverage -> {
            coverage.check(Reason.TAMPERED).percentage(percent -> percent >= 20);
            coverage.check(Reason.MALFORMED).percentage(percent -> percent >= 5);
        });
    }

    private Reason rejection(String candidate) {
        try {
            verifier.verify(candidate, BREAKFAST, CUSTOMER);
        } catch (InvalidPromiseException rejected) {
            return rejected.reason();
        }
        throw new AssertionError("A forged token was accepted: " + candidate);
    }

    /** Anything, and things that look like a token so that signature checking is reached. */
    @Provide
    Arbitrary<String> tokenLikeText() {
        Arbitrary<String> base64url = Arbitraries.strings()
                .withChars("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")
                .ofMinLength(1)
                .ofMaxLength(300);
        Arbitrary<String> lookalikes = Combinators.combine(Arbitraries.of("k1", "k2", ""), base64url, base64url)
                .as((kid, payload, signature) -> "v1." + kid + "." + payload + "." + signature);
        return Arbitraries.oneOf(Arbitraries.strings().ofMaxLength(1_100), lookalikes);
    }

    enum Kind {
        REPLACE,
        INSERT,
        DELETE,
        TRUNCATE,
        SWAP_PARTS
    }

    record Mutation(Kind kind, int position, char character) {

        String applyTo(String token) {
            int at = position % (token.length() + 1);
            return switch (kind) {
                case REPLACE ->
                    at == token.length()
                            ? token + character
                            : token.substring(0, at) + character + token.substring(at + 1);
                case INSERT -> token.substring(0, at) + character + token.substring(at);
                case DELETE ->
                    at == token.length()
                            ? token.substring(0, at - 1)
                            : token.substring(0, at) + token.substring(at + 1);
                case TRUNCATE -> token.substring(0, at);
                case SWAP_PARTS -> {
                    String[] parts = token.split("\\.");
                    int first = at % parts.length;
                    int second = (first + 1 + character % (parts.length - 1)) % parts.length;
                    String swap = parts[first];
                    parts[first] = parts[second];
                    parts[second] = swap;
                    yield String.join(".", parts);
                }
            };
        }
    }

    @Provide
    Arbitrary<Mutation> mutations() {
        Arbitrary<Character> characters = Arbitraries.oneOf(
                Arbitraries.chars().with("ABCabc0189-_.=|+/ "),
                Arbitraries.chars().all());
        return Combinators.combine(
                        Arbitraries.of(Kind.class), Arbitraries.integers().between(0, 1_000), characters)
                .as(Mutation::new);
    }
}
