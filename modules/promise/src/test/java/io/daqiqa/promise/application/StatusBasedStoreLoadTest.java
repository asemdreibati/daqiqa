package io.daqiqa.promise.application;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;

import io.daqiqa.kernel.StoreId;
import io.daqiqa.network.api.StoreStatus;
import io.daqiqa.promise.domain.StoreLoad;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class StatusBasedStoreLoadTest {

    private static final StoreId STORE = StoreId.of("DXB-MARINA");
    private static final StoreLoad OPEN = new StoreLoad(ofSeconds(60), ofSeconds(60));
    private static final StoreLoad BUSY = new StoreLoad(ofSeconds(300), ofSeconds(180));

    private final FakeStoreDirectory directory = new FakeStoreDirectory();
    private final StatusBasedStoreLoad load = new StatusBasedStoreLoad(directory, OPEN, BUSY);

    @Test
    void anOpenStoreHasTheOpenWaits() {
        directory.with(STORE, StoreStatus.OPEN);

        assertThat(load.loadAt(STORE)).isEqualTo(OPEN);
    }

    /** Only a store that stopped accepting orders since the coverage lookup can be anything but open or busy. */
    @ParameterizedTest
    @EnumSource(
            value = StoreStatus.class,
            names = {"BUSY", "PAUSED", "CLOSED"})
    void anyOtherStoreHasTheBusyWaits(StoreStatus status) {
        directory.with(STORE, status);

        assertThat(load.loadAt(STORE)).isEqualTo(BUSY);
    }

    @Test
    void aStoreTheDirectoryDoesNotKnowIsAssumedBusy() {
        assertThat(load.loadAt(STORE)).isEqualTo(BUSY);
    }
}
