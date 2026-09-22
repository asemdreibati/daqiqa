package io.daqiqa.ordering.adapter.out.persistence;

import io.daqiqa.kernel.CustomerId;
import io.daqiqa.kernel.GeoPoint;
import io.daqiqa.kernel.LocalizedText;
import io.daqiqa.kernel.Money;
import io.daqiqa.kernel.OrderId;
import io.daqiqa.kernel.PromiseId;
import io.daqiqa.kernel.ReservationId;
import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.StoreId;
import io.daqiqa.ordering.domain.Order;
import io.daqiqa.ordering.domain.OrderLine;
import io.daqiqa.ordering.domain.OrderRepository;
import io.daqiqa.ordering.domain.OrderStatus;
import io.daqiqa.ordering.domain.PaymentMethod;
import io.daqiqa.ordering.domain.PromiseTerms;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Orders in {@code ordering.customer_order} and their lines in {@code ordering.order_line}. */
@Repository
class JdbcOrderRepository implements OrderRepository {

    private static final String SELECT_ORDER = """
            SELECT id, customer_id, promise_id, store_id, tier, eta_minutes, placed_at, delivery_latitude,
                   delivery_longitude, payment_method, reservation_id, total_minor, currency, status
            FROM ordering.customer_order
            WHERE id = :id""";

    private final JdbcClient jdbc;

    JdbcOrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertUnlessPromiseInUse(Order order) {
        // The conflict target is the partial unique index on live orders' promise ids: a concurrent checkout under the
        // same promise makes this insert wait for it, then do nothing if it committed.
        int inserted = jdbc.sql("""
                        INSERT INTO ordering.customer_order
                            (id, customer_id, promise_id, store_id, tier, eta_minutes, placed_at, deliver_by,
                             delivery_latitude, delivery_longitude, payment_method, reservation_id, total_minor,
                             currency, status, updated_at)
                        VALUES (:id, :customerId, :promiseId, :storeId, :tier, :etaMinutes, :placedAt, :deliverBy,
                                :latitude, :longitude, :paymentMethod, :reservationId, :totalMinor,
                                :currency, :status, :placedAt)
                        ON CONFLICT (promise_id) WHERE status IN ('AWAITING_PAYMENT', 'CONFIRMED') DO NOTHING""")
                .param("id", order.id().value())
                .param("customerId", order.customerId().value())
                .param("promiseId", order.promise().promiseId().value())
                .param("storeId", order.promise().storeId().value())
                .param("tier", order.promise().tier())
                .param("etaMinutes", order.promise().etaMinutes())
                .param("placedAt", utc(order.placedAt()))
                .param("deliverBy", utc(order.deliverBy()))
                .param("latitude", order.deliveryLocation().latitude())
                .param("longitude", order.deliveryLocation().longitude())
                .param("paymentMethod", order.paymentMethod().name())
                .param("reservationId", order.reservationId().value())
                .param("totalMinor", order.total().amountMinor())
                .param("currency", order.total().currency().getCurrencyCode())
                .param("status", order.status().name())
                .update();
        if (inserted == 0) {
            return false;
        }
        insertLines(order);
        return true;
    }

    @Override
    public boolean promiseInUse(PromiseId promiseId) {
        // Answered from the partial unique index on live orders' promise ids.
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM ordering.customer_order
                                       WHERE promise_id = :promiseId AND status IN ('AWAITING_PAYMENT', 'CONFIRMED'))""")
                .param("promiseId", promiseId.value())
                .query(Boolean.class)
                .single());
    }

    /** One round trip for the whole basket: the lines travel as parallel arrays and unnest into rows. */
    private void insertLines(Order order) {
        List<OrderLine> lines = order.lines();
        jdbc.sql("""
                        INSERT INTO ordering.order_line
                            (order_id, line_no, sku, name_en, name_ar, quantity, unit_price_minor)
                        SELECT :orderId, line.line_no, line.sku, line.name_en, line.name_ar, line.quantity,
                               line.unit_price_minor
                        FROM unnest(:lineNos, :skus, :namesEn, :namesAr, :quantities, :unitPrices)
                             AS line (line_no, sku, name_en, name_ar, quantity, unit_price_minor)""")
                .param("orderId", order.id().value())
                .param("lineNos", lines.stream().mapToInt(OrderLine::lineNo).toArray())
                .param("skus", lines.stream().map(line -> line.sku().value()).toArray(String[]::new))
                .param("namesEn", lines.stream().map(line -> line.name().en()).toArray(String[]::new))
                .param("namesAr", lines.stream().map(line -> line.name().ar()).toArray(String[]::new))
                .param(
                        "quantities",
                        lines.stream().mapToInt(OrderLine::quantity).toArray())
                .param(
                        "unitPrices",
                        lines.stream()
                                .mapToLong(line -> line.unitPrice().amountMinor())
                                .toArray())
                .update();
    }

    @Override
    public Optional<Order> find(OrderId id) {
        return load(SELECT_ORDER, id);
    }

    @Override
    public Optional<Order> lock(OrderId id) {
        return load(SELECT_ORDER + " FOR UPDATE", id);
    }

    @Override
    public Optional<Order> lockIfAwaitingPayment(OrderId id) {
        // SKIP LOCKED: a locked order is being settled by a checkout right now; the sweeper leaves it to that checkout.
        return load(SELECT_ORDER + " AND status = 'AWAITING_PAYMENT' FOR UPDATE SKIP LOCKED", id);
    }

    @Override
    public List<OrderId> findAwaitingPaymentPlacedBy(Instant cutoff, int limit) {
        // Served by the partial index on placed_at over orders awaiting payment.
        return jdbc.sql("""
                        SELECT id FROM ordering.customer_order
                        WHERE status = 'AWAITING_PAYMENT' AND placed_at <= :cutoff
                        ORDER BY placed_at
                        LIMIT :limit""")
                .param("cutoff", utc(cutoff))
                .param("limit", limit)
                .query((rs, row) -> OrderId.of(rs.getObject("id", UUID.class)))
                .list();
    }

    @Override
    public void updateStatus(Order order, OrderStatus from, Instant at) {
        int updated = jdbc.sql("""
                        UPDATE ordering.customer_order SET status = :to, updated_at = :at
                        WHERE id = :id AND status = :from""")
                .param("id", order.id().value())
                .param("from", from.name())
                .param("to", order.status().name())
                .param("at", utc(at))
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Order " + order.id() + " was not " + from + " any more");
        }
    }

    private Optional<Order> load(String sql, OrderId id) {
        return jdbc.sql(sql)
                .param("id", id.value())
                .query(JdbcOrderRepository::header)
                .optional()
                .map(header -> header.withLines(lines(id, header.currency())));
    }

    private List<OrderLine> lines(OrderId id, Currency currency) {
        return jdbc.sql("""
                        SELECT line_no, sku, name_en, name_ar, quantity, unit_price_minor
                        FROM ordering.order_line
                        WHERE order_id = :id
                        ORDER BY line_no""")
                .param("id", id.value())
                .query((rs, row) -> new OrderLine(
                        rs.getInt("line_no"),
                        Sku.of(rs.getString("sku")),
                        LocalizedText.of(rs.getString("name_en"), rs.getString("name_ar")),
                        rs.getInt("quantity"),
                        new Money(rs.getLong("unit_price_minor"), currency)))
                .list();
    }

    private static OrderHeader header(ResultSet rs, int row) throws SQLException {
        return new OrderHeader(
                OrderId.of(rs.getObject("id", UUID.class)),
                CustomerId.of(rs.getObject("customer_id", UUID.class)),
                new PromiseTerms(
                        PromiseId.of(rs.getObject("promise_id", UUID.class)),
                        StoreId.of(rs.getString("store_id")),
                        rs.getString("tier"),
                        rs.getInt("eta_minutes")),
                rs.getObject("placed_at", OffsetDateTime.class).toInstant(),
                GeoPoint.of(rs.getDouble("delivery_latitude"), rs.getDouble("delivery_longitude")),
                PaymentMethod.valueOf(rs.getString("payment_method")),
                ReservationId.of(rs.getObject("reservation_id", UUID.class)),
                Money.of(rs.getLong("total_minor"), rs.getString("currency")),
                OrderStatus.valueOf(rs.getString("status")));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /** An order row before its lines are read; the order itself is only built complete, so it can check itself. */
    private record OrderHeader(
            OrderId id,
            CustomerId customerId,
            PromiseTerms promise,
            Instant placedAt,
            GeoPoint deliveryLocation,
            PaymentMethod paymentMethod,
            ReservationId reservationId,
            Money total,
            OrderStatus status) {

        Currency currency() {
            return total.currency();
        }

        Order withLines(List<OrderLine> lines) {
            return new Order(
                    id,
                    customerId,
                    promise,
                    placedAt,
                    deliveryLocation,
                    paymentMethod,
                    reservationId,
                    lines,
                    total,
                    status);
        }
    }
}
