package io.daqiqa.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.daqiqa.kernel.OrderId;
import io.daqiqa.ordering.application.IdempotencyRecord.ClaimDecision;
import io.daqiqa.ordering.application.IdempotencyRecord.State;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The decision table of ADR-0008: what a request carrying an existing key gets. */
class IdempotencyRecordTest {

    private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");
    private static final RequestFingerprint SAME = RequestFingerprint.of("the request");
    private static final RequestFingerprint OTHER = RequestFingerprint.of("another request");
    private static final StoredResponse CREATED = new StoredResponse(201, "{\"orderId\":\"...\"}");

    @Test
    void aCompletedKeyReplaysItsResponseToTheSameRequest() {
        assertThat(completed().decide(SAME, NOW)).isEqualTo(new ClaimDecision.Replay(CREATED));
    }

    @Test
    void aKeyUsedForAnotherRequestIsRefusedWhateverItsState() {
        assertThat(completed().decide(OTHER, NOW)).isEqualTo(new ClaimDecision.KeyReused());
        assertThat(inProgressUntil(NOW.plusSeconds(30)).decide(OTHER, NOW)).isEqualTo(new ClaimDecision.KeyReused());
        assertThat(inProgressUntil(NOW.minusSeconds(30)).decide(OTHER, NOW)).isEqualTo(new ClaimDecision.KeyReused());
    }

    @Test
    void aKeyInProgressMakesTheSameRequestWaitUntilItsLeaseRunsOut() {
        assertThat(inProgressUntil(NOW.plusSeconds(42)).decide(SAME, NOW))
                .isEqualTo(new ClaimDecision.InProgress(Duration.ofSeconds(42)));
    }

    @Test
    void aKeyWhoseLeaseRanOutIsTakenOverBecauseItsInstanceIsPresumedDead() {
        assertThat(inProgressUntil(NOW).decide(SAME, NOW)).isEqualTo(new ClaimDecision.TakeOver());
        assertThat(inProgressUntil(NOW.minusSeconds(1)).decide(SAME, NOW)).isEqualTo(new ClaimDecision.TakeOver());
    }

    @Test
    void anExpiredKeyIsForgottenAndMayServeAnyNewRequest() {
        IdempotencyRecord expired =
                new IdempotencyRecord(SAME, OrderId.newId(), new State.Completed(CREATED), NOW.minusMillis(1));

        assertThat(expired.decide(SAME, NOW)).isEqualTo(new ClaimDecision.Expired());
        assertThat(expired.decide(OTHER, NOW)).isEqualTo(new ClaimDecision.Expired());
        assertThat(new IdempotencyRecord(SAME, OrderId.newId(), new State.Completed(CREATED), NOW).decide(SAME, NOW))
                .as("expiry is inclusive")
                .isEqualTo(new ClaimDecision.Expired());
    }

    private static IdempotencyRecord completed() {
        return new IdempotencyRecord(
                SAME, OrderId.newId(), new State.Completed(CREATED), NOW.plus(Duration.ofHours(1)));
    }

    private static IdempotencyRecord inProgressUntil(Instant lockedUntil) {
        return new IdempotencyRecord(
                SAME, OrderId.newId(), new State.InProgress(lockedUntil), NOW.plus(Duration.ofHours(1)));
    }
}
