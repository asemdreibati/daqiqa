package io.daqiqa.inventory.adapter.out.persistence;

import static io.daqiqa.inventory.adapter.out.persistence.SqlValues.timestamp;

import io.daqiqa.inventory.domain.EndReason;
import io.daqiqa.inventory.domain.Reservation;
import io.daqiqa.inventory.domain.ReservationRepository;
import io.daqiqa.inventory.domain.ReservationStatus;
import io.daqiqa.inventory.domain.Transition;
import io.daqiqa.kernel.ReservationId;
import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.kernel.StoreId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcReservationRepository implements ReservationRepository {

    private static final String SELECT_WITH_LINES = """
            SELECT r.id, r.store_id, r.reference, r.status, r.expires_at, r.release_reason, l.sku, l.quantity
              FROM inventory.reservation AS r
              JOIN inventory.reservation_line AS l ON l.reservation_id = r.id""";

    private final JdbcClient jdbc;

    JdbcReservationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Reservation> findActiveByReference(String reference) {
        // Same predicate as the partial unique index, so this is an index lookup.
        return atMostOne(jdbc.sql(SELECT_WITH_LINES + " " + """
                         WHERE r.reference = :reference AND r.status IN ('HELD', 'CONFIRMED')
                         ORDER BY l.sku""")
                .param("reference", reference)
                .query(JdbcReservationRepository::reservations));
    }

    @Override
    public boolean insertIfReferenceFree(Reservation reservation, Instant now) {
        // ON CONFLICT DO NOTHING, not a caught unique violation: in PostgreSQL an error aborts the whole transaction,
        // and this one belongs to the caller. The conflict target names the partial index by its predicate.
        int inserted = jdbc.sql("""
                        INSERT INTO inventory.reservation
                               (id, store_id, reference, status, expires_at, release_reason, created_at, updated_at)
                        VALUES (:id, :storeId, :reference, :status, :expiresAt, :releaseReason, :now, :now)
                        ON CONFLICT (reference) WHERE status IN ('HELD', 'CONFIRMED') DO NOTHING""")
                .param("id", reservation.id().value())
                .param("storeId", reservation.storeId().value())
                .param("reference", reservation.reference())
                .param("status", reservation.status().name())
                .param("expiresAt", timestamp(reservation.expiresAt()))
                .param("releaseReason", name(reservation.endReason()), Types.VARCHAR)
                .param("now", timestamp(now))
                .update();
        if (inserted == 0) {
            return false;
        }
        jdbc.sql("""
                        INSERT INTO inventory.reservation_line (reservation_id, sku, quantity)
                        SELECT :id, l.sku, l.quantity
                          FROM unnest(:skus, :quantities) AS l(sku, quantity)""")
                .param("id", reservation.id().value())
                .param(
                        "skus",
                        reservation.lines().stream()
                                .map(line -> line.sku().value())
                                .toArray(String[]::new))
                .param(
                        "quantities",
                        reservation.lines().stream()
                                .mapToInt(SkuQuantity::quantity)
                                .toArray())
                .update();
        return true;
    }

    @Override
    public Optional<Reservation> lockById(ReservationId id) {
        // Locks the reservation row only; its lines never change.
        return atMostOne(jdbc.sql(SELECT_WITH_LINES + " " + """
                         WHERE r.id = :id
                         ORDER BY l.sku
                           FOR UPDATE OF r""")
                .param("id", id.value())
                .query(JdbcReservationRepository::reservations));
    }

    @Override
    public List<Reservation> lockElapsedHolds(Instant now, int limit) {
        // SKIP LOCKED: a hold another transaction is confirming, releasing or sweeping is left to it, so concurrent
        // sweepers take disjoint batches and never wait for each other. The CTE applies the LIMIT to reservations,
        // not to their lines. The scan is the partial index on expires_at WHERE status = 'HELD'.
        return jdbc.sql("""
                        WITH elapsed AS MATERIALIZED (
                            SELECT id, store_id, reference, status, expires_at, release_reason
                              FROM inventory.reservation
                             WHERE status = 'HELD' AND expires_at <= :now
                             ORDER BY expires_at
                             LIMIT :limit
                               FOR UPDATE SKIP LOCKED)
                        SELECT r.id, r.store_id, r.reference, r.status, r.expires_at, r.release_reason, l.sku, l.quantity
                          FROM elapsed AS r
                          JOIN inventory.reservation_line AS l ON l.reservation_id = r.id
                         ORDER BY r.expires_at, r.id, l.sku""")
                .param("now", timestamp(now))
                .param("limit", limit)
                .query(JdbcReservationRepository::reservations);
    }

    @Override
    public void apply(List<Transition> transitions, Instant now) {
        if (transitions.isEmpty()) {
            return;
        }
        // Guarded by the status each transition leaves: a transition applied twice (or racing a change nobody locked
        // against) matches no row, and the count check below fails the transaction before stock is returned twice.
        int updated = jdbc.sql("""
                        UPDATE inventory.reservation AS r
                           SET status = t.to_status, release_reason = t.end_reason, updated_at = :now
                          FROM unnest(CAST(:ids AS uuid[]), :fromStatuses, :toStatuses, :endReasons)
                               AS t(id, from_status, to_status, end_reason)
                         WHERE r.id = t.id
                           AND r.status = t.from_status""")
                .param(
                        "ids",
                        transitions.stream().map(t -> t.from().id().toString()).toArray(String[]::new))
                .param("fromStatuses", statuses(transitions.stream().map(Transition::from)))
                .param("toStatuses", statuses(transitions.stream().map(Transition::to)))
                .param(
                        "endReasons",
                        transitions.stream().map(t -> name(t.to().endReason())).toArray(String[]::new))
                .param("now", timestamp(now))
                .update();
        if (updated != transitions.size()) {
            throw new IllegalStateException("Applied " + updated + " of " + transitions.size()
                    + " reservation transitions: the others are not in the status they leave (applied twice?)");
        }
    }

    private static String[] statuses(Stream<Reservation> reservations) {
        return reservations.map(reservation -> reservation.status().name()).toArray(String[]::new);
    }

    private static @Nullable String name(@Nullable EndReason reason) {
        return reason == null ? null : reason.name();
    }

    private static Optional<Reservation> atMostOne(List<Reservation> reservations) {
        if (reservations.size() > 1) {
            throw new IllegalStateException("Expected at most one reservation, found " + reservations.size());
        }
        return reservations.stream().findFirst();
    }

    /** Folds one row per line back into reservations, in the order they first appear. */
    private static List<Reservation> reservations(ResultSet rs) throws SQLException {
        Map<UUID, ReservationRows> byId = new LinkedHashMap<>();
        while (rs.next()) {
            UUID id = rs.getObject("id", UUID.class);
            ReservationRows rows = byId.get(id);
            if (rows == null) {
                String releaseReason = rs.getString("release_reason");
                rows = new ReservationRows(
                        ReservationId.of(id),
                        StoreId.of(rs.getString("store_id")),
                        rs.getString("reference"),
                        ReservationStatus.valueOf(rs.getString("status")),
                        SqlValues.instant(rs, "expires_at"),
                        releaseReason == null ? null : EndReason.valueOf(releaseReason),
                        new ArrayList<>());
                byId.put(id, rows);
            }
            rows.lines().add(new SkuQuantity(Sku.of(rs.getString("sku")), rs.getInt("quantity")));
        }
        return byId.values().stream().map(ReservationRows::toReservation).toList();
    }

    private record ReservationRows(
            ReservationId id,
            StoreId storeId,
            String reference,
            ReservationStatus status,
            Instant expiresAt,
            @Nullable EndReason endReason,
            List<SkuQuantity> lines) {

        Reservation toReservation() {
            return new Reservation(id, storeId, reference, lines, status, expiresAt, endReason);
        }
    }
}
