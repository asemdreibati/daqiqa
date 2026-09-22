package io.daqiqa.inventory.domain;

/** What releasing a reservation means, given its status. See {@link Reservation#release()}. */
public sealed interface ReleaseOutcome {

    /** HELD or CONFIRMED → RELEASED: the held stock goes back on sale. */
    record Released(Reservation released) implements ReleaseOutcome {}

    /** Released or expired earlier: nothing to do (releasing is idempotent). */
    record AlreadyEnded(ReservationStatus status) implements ReleaseOutcome {}
}
