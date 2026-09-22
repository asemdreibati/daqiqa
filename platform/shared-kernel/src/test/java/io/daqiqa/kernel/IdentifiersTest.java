package io.daqiqa.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IdentifiersTest {

    @Test
    void generatesVersion7UuidsInCreationOrder() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            ids.add(Ids.newTimeOrderedId());
        }

        assertThat(ids).allSatisfy(id -> assertThat(id.version()).isEqualTo(7));
        assertThat(ids).doesNotHaveDuplicates();
        // UUIDv7 sorts by its leading 48-bit millisecond timestamp.
        assertThat(ids.getLast().getMostSignificantBits() >>> 16)
                .isGreaterThanOrEqualTo(ids.getFirst().getMostSignificantBits() >>> 16);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DXB-MARINA", "DXB-JLT-2", "RUH-OLAYA"})
    void acceptsWellFormedStoreIds(String value) {
        assertThat(StoreId.of(value).value()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "dxb-marina", "DXB", "DUBAI-MARINA", "DXB_MARINA", "DXB-"})
    void rejectsMalformedStoreIds(String value) {
        assertThatIllegalArgumentException().isThrownBy(() -> StoreId.of(value));
    }

    @Test
    void mergesDuplicateCartLinesPreservingOrder() {
        List<SkuQuantity> merged = SkuQuantity.merge(
                List.of(SkuQuantity.of("MILK-1L", 1), SkuQuantity.of("BREAD-WHITE", 2), SkuQuantity.of("MILK-1L", 3)));

        assertThat(merged).containsExactly(SkuQuantity.of("MILK-1L", 4), SkuQuantity.of("BREAD-WHITE", 2));
    }

    @Test
    void boundsLineQuantities() {
        assertThatIllegalArgumentException().isThrownBy(() -> SkuQuantity.of("MILK-1L", 0));
        assertThatIllegalArgumentException().isThrownBy(() -> SkuQuantity.of("MILK-1L", SkuQuantity.MAX_QUANTITY + 1));
    }
}
