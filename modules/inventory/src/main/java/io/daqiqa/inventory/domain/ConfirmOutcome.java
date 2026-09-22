package io.daqiqa.inventory.domain;

/** What confirming a reservation means, given its status and the time. See {@link Reservation#confirm}. */
public sealed interface ConfirmOutcome {

    /** HELD → CONFIRMED. */
    record Confirmed(Transition confirmation) implements ConfirmOutcome {}

    /** Already confirmed: nothing to do (confirming is idempotent). */
    record AlreadyConfirmed() implements ConfirmOutcome {}

    /**
     * The hold ran out before the confirmation arrived, even if the sweeper has not noticed yet: HELD → EXPIRED. The
     * stock must go back on sale and the confirmation must fail, because those units may already be promised to
     * someone else by the time the caller acts on a success.
     */
    record HoldElapsed(Transition expiry) implements ConfirmOutcome {}

    /** Released or expired earlier: the confirmation must fail. */
    record NoLongerHeld(ReservationStatus status) implements ConfirmOutcome {}
}
