package io.daqiqa.promise.application;

import static io.daqiqa.promise.application.Tokens.key;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SigningKeysTest {

    @Test
    void signsWithTheActiveKeyAndVerifiesWithAnyConfiguredKey() {
        SigningKeys keys = new SigningKeys(Map.of("k1", key(1), "k2", key(2)), "k2");

        assertThat(keys.activeKeyId()).isEqualTo("k2");
        assertThat(keys.find("k1")).isPresent();
        assertThat(keys.find("k3")).isEmpty();
    }

    @Test
    void refusesAKeyShorterThan256Bits() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SigningKeys(Map.of("k1", new byte[SigningKeys.MIN_KEY_BYTES - 1]), "k1"))
                .withMessageContaining("at least 32");
    }

    @Test
    void refusesAnActiveKeyThatIsNotConfigured() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SigningKeys(Map.of("k1", key(1)), "k2"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "k.1", "k|1", "a-key-id-that-is-much-too-long-to-be-useful"})
    void refusesKeyIdsThatCannotAppearInATokenUnambiguously(String keyId) {
        assertThatIllegalArgumentException().isThrownBy(() -> new SigningKeys(Map.of(keyId, key(1)), keyId));
    }
}
