package io.daqiqa.network.domain;

import static io.daqiqa.network.StoreFixtures.DUBAI_MARINA;
import static io.daqiqa.network.StoreFixtures.store;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import io.daqiqa.kernel.LocalizedText;
import io.daqiqa.kernel.StoreId;
import io.daqiqa.network.api.StoreStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class StoreTest {

    @ParameterizedTest
    @ValueSource(ints = {Store.MIN_SERVICE_RADIUS_METERS, 3_000, Store.MAX_SERVICE_RADIUS_METERS})
    void acceptsAServiceRadiusWithinTheLimits(int radius) {
        assertThatNoException().isThrownBy(() -> store("DXB-MARINA", DUBAI_MARINA, radius, StoreStatus.OPEN));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, Store.MIN_SERVICE_RADIUS_METERS - 1, Store.MAX_SERVICE_RADIUS_METERS + 1})
    void rejectsAServiceRadiusOutsideTheLimits(int radius) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> store("DXB-MARINA", DUBAI_MARINA, radius, StoreStatus.OPEN))
                .withMessageContaining("Service radius of DXB-MARINA");
    }

    @Test
    void rejectsANegativeNumberOfPickers() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Store(
                        StoreId.of("DXB-MARINA"),
                        LocalizedText.of("Marina", "المارينا"),
                        DUBAI_MARINA,
                        StoreStatus.OPEN,
                        -1,
                        3_000));
    }

    @ParameterizedTest
    @EnumSource(StoreStatus.class)
    void acceptsOrdersOnlyWhenOpenOrBusy(StoreStatus status) {
        Store store = store("DXB-MARINA", DUBAI_MARINA, 3_000, status);

        assertThat(store.acceptsOrders()).isEqualTo(status == StoreStatus.OPEN || status == StoreStatus.BUSY);
    }
}
