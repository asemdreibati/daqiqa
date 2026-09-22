package io.daqiqa.messaging.outbox;

/**
 * What one relay round did.
 *
 * @param leader whether this instance held the relay lock; {@code false} means another instance was relaying and this
 *     round did nothing
 * @param published events published and marked in this round
 * @param stoppedByFailure whether the round ended early because an event could not be published
 */
record RelayRound(boolean leader, int published, boolean stoppedByFailure) {

    static final RelayRound SKIPPED = new RelayRound(false, 0, false);
}
