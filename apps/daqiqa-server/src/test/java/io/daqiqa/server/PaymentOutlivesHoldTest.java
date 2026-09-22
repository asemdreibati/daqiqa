package io.daqiqa.server;

import static io.daqiqa.server.ServerDriver.CARD;
import static io.daqiqa.server.ServerDriver.MARINA;
import static io.daqiqa.server.ServerDriver.await;
import static io.daqiqa.server.ServerDriver.line;
import static org.assertj.core.api.Assertions.assertThat;

import io.daqiqa.contracts.events.OrderAbandonedV1;
import io.daqiqa.contracts.events.OrderPlacedV1;
import io.daqiqa.contracts.events.ReservationExpiredV1;
import io.daqiqa.server.ServerDriver.Checkout;
import io.daqiqa.server.ServerDriver.Event;
import io.daqiqa.server.ServerDriver.Reply;
import io.daqiqa.server.ServerDriver.Reservation;
import io.daqiqa.server.ServerDriver.StockLevel;
import io.daqiqa.testing.postgres.PostgresTestDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The race the design worries about most: a card payment that comes back after its stock hold has run out, against the
 * sweepers that give that stock back. Both orders of the race are played through the assembled server, with the
 * sweepers and the relay really running and a clock the test moves forward:
 *
 * <ul>
 *   <li>the sweepers first, the payment returning into an order they already abandoned;
 *   <li>the payment first, its confirmation finding the hold run out and abandoning the order itself. The checkout is
 *       held at the reservation's row lock while the clock passes the hold, so the sweepers (which skip locked rows)
 *       cannot get in front of it.
 * </ul>
 *
 * <p>Whoever wins, the customer is told {@code 409 HOLD_EXPIRED}, the order ends {@code ABANDONED}, the stock goes
 * back on sale exactly once, and payments are told exactly once that an authorization may need voiding.
 */
@Tag("integration")
@ActiveProfiles("dev")
@DirtiesContext // this context's clock runs ahead of the others; its scheduled jobs must stop with the class
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "daqiqa.messaging.publisher=logging",
            // A payment slow enough to lose the race, and sweeps often enough that several fit inside one payment.
            "daqiqa.ordering.payment.latency=2s",
            "daqiqa.inventory.expiry.interval=200ms",
            "daqiqa.ordering.sweeper.interval=300ms"
        })
class PaymentOutlivesHoldTest {

    private static final Logger log = LoggerFactory.getLogger(PaymentOutlivesHoldTest.class);

    private static final PostgresTestDatabase DB = PostgresTestDatabase.shared();

    /** 55.00 AED, which the simulated gateway approves. */
    private static final String SKU = "SUNSCREEN-SPF50";

    /** Past {@code daqiqa.ordering.hold} (5 minutes by default), so the hold has certainly run out. */
    private static final Duration PAST_THE_HOLD = Duration.ofMinutes(6);

    /** Timing is generous everywhere: this suite must never fail because a shared machine was busy. */
    private static final Duration GENEROUS = Duration.ofSeconds(30);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.registerWith(registry);
    }

    /**
     * The one clock every module reads. The server's own clock bean is {@code @ConditionalOnMissingBean}, and
     * {@code @Primary} settles it wherever both are registered.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class ControllableClock {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @Autowired
    MutableClock clock;

    @Value("${local.server.port}")
    int port;

    private final ServerInvariants invariants = new ServerInvariants(DB.dataSource());
    private final JdbcClient db = JdbcClient.create(DB.dataSource());
    private ServerDriver server;

    @BeforeEach
    void stockTheMarinaStore() {
        server = new ServerDriver(port, DB.dataSource());
        server.setStoreStatus(MARINA, "OPEN");
        server.adjustStock(MARINA, SKU, 5, "RECEIVED");
    }

    @AfterEach
    void everyInvariantHolds() {
        assertThat(server.serverErrors()).as("answers with a 5xx status").isEmpty();
        invariants.assertHold();
    }

    /** The hold runs out while the payment is still in flight: the sweepers give the stock back and the order up. */
    @RepeatedTest(3)
    void aSweeperGetsThereFirstAndTheLatePaymentIsTurnedAway() throws Exception {
        Checkout checkout = server.checkout(CARD, line(SKU, 1));
        StockLevel before = server.stock(MARINA, SKU);
        Future<Reply> checkingOut = inBackground(() -> server.placeOrder(checkout));
        UUID orderId = awaitOrderAwaitingPayment(checkout, checkingOut);

        clock.advance(PAST_THE_HOLD);
        // The payment still has most of its two seconds to run; the sweepers come round every few hundred ms.
        await(
                "the order to be abandoned or the checkout to answer",
                GENEROUS,
                () -> checkingOut.isDone()
                                || server.orderStatus(orderId)
                                        .filter("ABANDONED"::equals)
                                        .isPresent()
                        ? Optional.of(true)
                        : Optional.empty());

        assertTurnedAway(checkingOut.get(GENEROUS.toMillis(), TimeUnit.MILLISECONDS), orderId, before);
    }

    /**
     * The payment comes back first. Its confirmation is held at the reservation's row lock, the way a slow transaction
     * elsewhere would hold it, until the hold has run out; the sweepers skip both rows the checkout holds, so the
     * checkout itself has to notice that the hold is gone.
     */
    @RepeatedTest(3)
    void theCheckoutGetsThereFirstExpiresTheHoldItselfAndAbandonsItsOrder() throws Exception {
        Checkout checkout = server.checkout(CARD, line(SKU, 1));
        StockLevel before = server.stock(MARINA, SKU);
        Future<Reply> checkingOut = inBackground(() -> server.placeOrder(checkout));
        UUID orderId = awaitOrderAwaitingPayment(checkout, checkingOut);
        Reservation hold = server.reservationOf(orderId);

        try (RowLock lock = lockRowOf(hold.id())) {
            lock.awaitWaiter(); // the payment is back: its transaction queues here, holding the order's row
            clock.advance(PAST_THE_HOLD);
        }

        Reply answer = checkingOut.get(GENEROUS.toMillis(), TimeUnit.MILLISECONDS);
        assertTurnedAway(answer, orderId, before);
        assertThat(abandonment(orderId).<String>json("$.reason"))
                .as("the checkout, not a sweeper, gave the order up")
                .isEqualTo("PAID_AFTER_HOLD_EXPIRED");
        assertThat(server.reservationOf(orderId))
                .extracting(Reservation::status, Reservation::releaseReason)
                .containsExactly("EXPIRED", "EXPIRED");
    }

    /** What every way through the race must end in. */
    private void assertTurnedAway(Reply answer, UUID orderId, StockLevel before) {
        assertThat(answer.status()).as(answer.toString()).isEqualTo(409);
        assertThat(answer.code()).isEqualTo("HOLD_EXPIRED");
        assertThat(server.getOrder(orderId).<String>json("$.status")).isEqualTo("ABANDONED");
        assertThat(server.stock(MARINA, SKU))
                .as("the stock went back on sale exactly once")
                .isEqualTo(before);

        Reservation hold = server.reservationOf(orderId);
        assertThat(hold)
                .extracting(Reservation::status, Reservation::releaseReason)
                .isIn(List.of("EXPIRED", "EXPIRED"), List.of("RELEASED", "ORDER_ABANDONED"));
        List<Event> events = server.eventsAbout(orderId);
        assertThat(events).extracting(Event::type).doesNotContain(OrderPlacedV1.TYPE);
        assertThat(events)
                .filteredOn(event -> event.type().equals(ReservationExpiredV1.TYPE))
                .as("a hold that ended by expiring says so once; one released with the order does not")
                .hasSize("EXPIRED".equals(hold.status()) ? 1 : 0);

        Event abandoned = abandonment(orderId);
        assertThat(abandoned.<Boolean>json("$.paymentAuthorizationMayExist"))
                .as("payments must look for an authorization to void")
                .isTrue();
        log.info(
                "Race won by {}: order abandoned as {}, hold {} ({})",
                "PAID_AFTER_HOLD_EXPIRED".equals(abandoned.json("$.reason")) ? "the checkout" : "a sweeper",
                abandoned.<String>json("$.reason"),
                hold.status(),
                hold.releaseReason());
    }

    /** The one {@code OrderAbandonedV1} of this order. */
    private Event abandonment(UUID orderId) {
        List<Event> abandoned = server.eventsAbout(orderId).stream()
                .filter(event -> event.type().equals(OrderAbandonedV1.TYPE))
                .toList();
        assertThat(abandoned).as("OrderAbandonedV1 events of order %s", orderId).hasSize(1);
        return abandoned.get(0);
    }

    /** The order the checkout created in T1, once its stock is held and its card is being charged. */
    private UUID awaitOrderAwaitingPayment(Checkout checkout, Future<Reply> checkingOut) {
        return await("the checkout to hold its stock and start paying", GENEROUS, () -> {
            if (checkingOut.isDone()) {
                throw new AssertionError("The checkout answered before it paid: " + answerOf(checkingOut));
            }
            return server.orderIdOf(checkout)
                    .filter(id -> server.orderStatus(id)
                            .filter("AWAITING_PAYMENT"::equals)
                            .isPresent());
        });
    }

    private static Reply answerOf(Future<Reply> checkingOut) {
        try {
            return checkingOut.get(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("The checkout failed", e);
        }
    }

    private static Future<Reply> inBackground(Callable<Reply> request) {
        FutureTask<Reply> task = new FutureTask<>(request);
        Thread.ofPlatform().name("checkout-in-flight").daemon().start(task);
        return task;
    }

    private RowLock lockRowOf(UUID reservationId) throws SQLException {
        Connection connection = DB.dataSource().getConnection();
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement lock =
                    connection.prepareStatement("SELECT id FROM inventory.reservation WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, reservationId);
                try (ResultSet locked = lock.executeQuery()) {
                    assertThat(locked.next())
                            .as("reservation %s exists", reservationId)
                            .isTrue();
                }
            }
            return new RowLock(connection, backendPid(connection));
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }

    private static int backendPid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT pg_backend_pid()")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** A row lock held by a transaction of the test's own until it is closed, as a slow transaction would hold it. */
    private final class RowLock implements AutoCloseable {

        private final Connection connection;
        private final int pid;

        private RowLock(Connection connection, int pid) {
            this.connection = connection;
            this.pid = pid;
        }

        /** Waits until another transaction is queued behind this lock. */
        void awaitWaiter() {
            await(
                    "a transaction to queue behind the row lock",
                    GENEROUS,
                    () -> db.sql("SELECT count(*) FROM pg_stat_activity WHERE :pid = ANY(pg_blocking_pids(pid))")
                                            .param("pid", pid)
                                            .query(Long.class)
                                            .single()
                                    > 0
                            ? Optional.of(true)
                            : Optional.empty());
        }

        @Override
        public void close() throws SQLException {
            try {
                connection.commit();
            } finally {
                connection.close();
            }
        }
    }

    /** The system clock plus an offset the test moves forward: time flows as usual, and jumps when told to. */
    static final class MutableClock extends Clock {

        private final Clock system = Clock.systemUTC();
        private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

        void advance(Duration by) {
            offset.accumulateAndGet(by, Duration::plus);
        }

        @Override
        public Instant instant() {
            return system.instant().plus(offset.get());
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("Daqiqa works in UTC");
        }
    }
}
