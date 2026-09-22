package io.daqiqa.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ReservationExpirySweeperBatchingTest {

    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00.123456789Z");

    private final ReservationService reservations = mock(ReservationService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void takesBatchesUntilOneIsNotFullAllAsOfTheSameMomentAtTheStoredPrecision() {
        Instant storable = Instant.parse("2026-09-22T10:00:00.123456Z");
        given(reservations.expireElapsedHolds(storable, 3)).willReturn(3, 3, 1);

        assertThat(new ReservationExpirySweeper(reservations, clock, 3).sweep()).isEqualTo(7);
        verify(reservations, times(3)).expireElapsedHolds(storable, 3);
    }

    @Test
    void needsAPositiveBatchSize() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ReservationExpirySweeper(reservations, clock, 0));
    }
}
