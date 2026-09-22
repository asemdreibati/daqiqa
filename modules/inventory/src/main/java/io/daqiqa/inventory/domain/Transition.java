package io.daqiqa.inventory.domain;

import java.util.Objects;

/**
 * A reservation moving from one status to the next. It is persisted only while the reservation is still in the status
 * it leaves, so a transition applied twice (a release returning its stock twice, say) fails loudly instead of quietly
 * corrupting the books.
 */
public record Transition(Reservation from, Reservation to) {

    public Transition {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!from.id().equals(to.id())) {
            throw new IllegalArgumentException(
                    "A transition moves one reservation, got " + from.id() + " and " + to.id());
        }
        if (from.status() == to.status()) {
            throw new IllegalArgumentException(
                    "A transition changes the status, " + from.id() + " stays " + from.status());
        }
    }
}
