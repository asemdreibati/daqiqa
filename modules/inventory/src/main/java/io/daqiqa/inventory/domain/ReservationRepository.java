package io.daqiqa.inventory.domain;

import io.daqiqa.kernel.ReservationId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Outbound port: reservations and their lines. Implemented by a persistence adapter. */
public interface ReservationRepository {

    /** The held or confirmed reservation for {@code reference}, if there is one. */
    Optional<Reservation> findActiveByReference(String reference);

    /**
     * Inserts a new reservation with its lines, unless an active (held or confirmed) reservation already has its
     * reference. The database decides, atomically: if another transaction is inserting the same reference right now,
     * this waits until it commits (then returns false) or rolls back (then inserts). A conflict is an answer, not an
     * error, so the caller's transaction stays usable.
     *
     * @return whether the reservation was inserted
     */
    boolean insertIfReferenceFree(Reservation reservation, Instant now);

    /** The reservation with its lines, locked until the end of the current transaction. */
    Optional<Reservation> lockById(ReservationId id);

    /**
     * Up to {@code limit} held reservations whose hold ran out by {@code now}, earliest expiry first, locked until the
     * end of the current transaction. Reservations another transaction has locked are skipped, not waited for.
     */
    List<Reservation> lockElapsedHolds(Instant now, int limit);

    /**
     * Records transitions: each reservation's new status and end reason. A transition is applied only while its
     * reservation is still in the status the transition leaves, so a transition applied twice (a release returning its
     * stock twice, say) fails loudly instead of quietly corrupting the books.
     *
     * @throws IllegalStateException if some reservation is not in the status its transition leaves; the transaction
     *     must roll back
     */
    void apply(List<Transition> transitions, Instant now);
}
