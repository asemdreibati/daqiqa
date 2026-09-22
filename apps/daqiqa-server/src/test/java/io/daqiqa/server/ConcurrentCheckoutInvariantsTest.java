package io.daqiqa.server;

import static io.daqiqa.server.ServerDriver.CARD;
import static io.daqiqa.server.ServerDriver.CASH_ON_DELIVERY;
import static io.daqiqa.server.ServerDriver.MARINA;
import static io.daqiqa.server.ServerDriver.concurrently;
import static io.daqiqa.server.ServerDriver.line;
import static org.assertj.core.api.Assertions.assertThat;

import io.daqiqa.server.ServerDriver.Checkout;
import io.daqiqa.server.ServerDriver.Reply;
import io.daqiqa.server.ServerDriver.StockLevel;
import io.daqiqa.testing.postgres.PostgresTestDatabase;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Correctness under concurrency, proven through the assembled server rather than one module with fakes: every module
 * real, requests over HTTP, real PostgreSQL, and the sweepers and the outbox relay running on the server's scheduler.
 * Each scenario fires its requests through a start gate so they collide, checks its own outcome, and then the
 * invariants of the whole database ({@link ServerInvariants}) and that no answer was a server error.
 */
@Tag("integration")
@ActiveProfiles("dev")
@DirtiesContext // stop this context's sweepers and relay with the class, so they never run into other test classes
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "daqiqa.messaging.publisher=logging")
class ConcurrentCheckoutInvariantsTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentCheckoutInvariantsTest.class);

    private static final PostgresTestDatabase DB = PostgresTestDatabase.shared();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.registerWith(registry);
    }

    @Value("${local.server.port}")
    int port;

    private final ServerInvariants invariants = new ServerInvariants(DB.dataSource());
    private ServerDriver server;

    @BeforeEach
    void openTheMarinaStore() {
        server = new ServerDriver(port, DB.dataSource());
        // Another test class may have left it paused in the database; this instance learns of the change at once.
        server.setStoreStatus(MARINA, "OPEN");
    }

    @AfterEach
    void everyInvariantHolds() {
        assertThat(server.serverErrors()).as("answers with a 5xx status").isEmpty();
        invariants.assertHold();
    }

    /** A client that makes up a new key for every retry must still get one order out of one promise. */
    @Test
    void sixteenKeysRacingUnderOnePromiseMakeExactlyOneOrder() {
        String sku = "LABNEH-400G";
        server.adjustStock(MARINA, sku, 20, "RECEIVED");
        StockLevel before = server.stock(MARINA, sku);
        Checkout first = server.checkout(CARD, line(sku, 1));
        List<Checkout> sameOrderSixteenKeys =
                Stream.iterate(first, Checkout::withNewKey).limit(16).toList();

        List<Reply> replies = concurrently(sameOrderSixteenKeys, server::placeOrder);

        assertThat(replies)
                .filteredOn(reply -> reply.status() == 201)
                .singleElement()
                .satisfies(reply -> assertThat(reply.<String>json("$.status")).isEqualTo("CONFIRMED"));
        assertThat(replies)
                .filteredOn(reply -> reply.status() != 201)
                .hasSize(15)
                .allSatisfy(reply -> {
                    assertThat(reply.status()).as(reply.toString()).isEqualTo(409);
                    assertThat(reply.code()).isEqualTo("PROMISE_ALREADY_USED");
                });
        assertThat(server.ordersUnderPromise(first.offer().promiseId())).isOne();
        assertThat(server.stock(MARINA, sku).reserved())
                .as("the fifteen refused checkouts hold nothing")
                .isEqualTo(before.reserved() + 1);
    }

    /** The headline promise: under contention a store sells exactly what it has, and every other customer is told. */
    @Test
    void checkoutsRacingForTheLastUnitsConfirmExactlyAsManyOrdersAsThereAreUnits() {
        String sku = "HALLOUMI-250G";
        int units = 5;
        int customers = 16;
        StockLevel before = server.makeAvailable(MARINA, sku, units);
        List<Checkout> checkouts = IntStream.range(0, customers)
                .mapToObj(customer -> server.checkout(CASH_ON_DELIVERY, line(sku, 1)))
                .toList();
        assertThat(checkouts)
                .allSatisfy(checkout -> assertThat(checkout.offer().storeId()).isEqualTo(MARINA));

        List<Reply> replies = concurrently(checkouts, server::placeOrder);

        assertThat(replies)
                .as("no deadlock, no lock timeout, nothing but orders and refusals")
                .extracting(Reply::status)
                .containsOnly(201, 409);
        assertThat(replies)
                .filteredOn(reply -> reply.status() == 201)
                .hasSize(units)
                .allSatisfy(reply -> assertThat(reply.<String>json("$.status")).isEqualTo("CONFIRMED"));
        assertThat(replies)
                .filteredOn(reply -> reply.status() == 409)
                .hasSize(customers - units)
                .allSatisfy(reply -> {
                    assertThat(reply.code()).isEqualTo("OUT_OF_STOCK");
                    assertThat(reply.<String>json("$.shortages[0].sku")).isEqualTo(sku);
                    assertThat(reply.<Integer>json("$.shortages[0].available")).isZero();
                });
        assertThat(server.stock(MARINA, sku)).isEqualTo(new StockLevel(before.onHand(), before.reserved() + units, 0));
    }

    /**
     * Real traffic mixes everything: cash and card orders, cards the issuer declines, and apps that submit again while
     * their first request is still running. Every basket includes a bottle of water, so all of them queue for one
     * stock row.
     */
    @Test
    void aBurstOfCashCardDeclinedAndDuplicateSubmissionsSettlesEveryOrderExactlyOnce() {
        String water = "WATER-1-5L";
        String milk = "MILK-FULL-1L";
        String eggs = "EGGS-12";
        String formula = "BABY-FORMULA-800G";
        String powerBank = "POWERBANK-10000";
        List<String> skus = List.of(water, milk, eggs, formula, powerBank);
        skus.forEach(sku -> server.adjustStock(MARINA, sku, 60, "RECEIVED"));
        Map<String, StockLevel> before = levels(skus);

        List<Checkout> cash = times(4, () -> server.checkout(CASH_ON_DELIVERY, line(eggs, 1), line(water, 1)));
        List<Checkout> approved = times(4, () -> server.checkout(CARD, line(milk, 2), line(water, 1)));
        // 11 × 95.00 or 11 × 99.00 AED, plus 2.50 AED: above the simulated gateway's 1,000.00 AED limit.
        List<Checkout> declined = new ArrayList<>();
        declined.addAll(times(2, () -> server.checkout(CARD, line(formula, 11), line(water, 1))));
        declined.addAll(times(2, () -> server.checkout(CARD, line(powerBank, 11), line(water, 1))));
        List<Checkout> checkouts =
                Stream.of(cash, approved, declined).flatMap(List::stream).toList();
        assertThat(checkouts)
                .allSatisfy(checkout -> assertThat(checkout.offer().storeId()).isEqualTo(MARINA));

        // Each checkout submitted three times at once with its key, all 36 requests in one burst.
        List<Checkout> submissions = new ArrayList<>();
        checkouts.forEach(checkout -> submissions.addAll(Collections.nCopies(3, checkout)));
        Collections.shuffle(submissions, new Random(20260922));
        List<Reply> replies = concurrently(submissions, server::placeOrder);

        log.info(
                "{} submissions of {} checkouts answered: {} × 201, {} × 402, {} of them replays, {} asked to come back",
                submissions.size(),
                checkouts.size(),
                replies.stream().filter(reply -> reply.status() == 201).count(),
                replies.stream().filter(reply -> reply.status() == 402).count(),
                replies.stream().filter(Reply::replayed).count(),
                replies.stream()
                        .filter(reply -> "IDEMPOTENCY_REQUEST_IN_PROGRESS".equals(reply.code()))
                        .count());

        Map<Checkout, Reply> outcomes = new LinkedHashMap<>();
        for (Checkout checkout : checkouts) {
            // Same instance three times in the submissions, so its three answers are found by identity.
            List<Reply> answers = IntStream.range(0, submissions.size())
                    .filter(i -> submissions.get(i) == checkout)
                    .mapToObj(replies::get)
                    .toList();
            outcomes.put(checkout, theOneOutcome(answers));
        }
        assertThat(cash).allSatisfy(checkout -> assertConfirmed(outcomes.get(checkout)));
        assertThat(approved).allSatisfy(checkout -> assertConfirmed(outcomes.get(checkout)));
        assertThat(declined).allSatisfy(checkout -> {
            Reply outcome = outcomes.get(checkout);
            assertThat(outcome.status()).as(outcome.toString()).isEqualTo(402);
            assertThat(outcome.code()).isEqualTo("PAYMENT_DECLINED");
        });

        // A retry long after the burst is answered from the stored response, byte for byte.
        outcomes.forEach((checkout, outcome) -> {
            Reply retry = server.placeOrder(checkout);
            assertThat(retry.status()).isEqualTo(outcome.status());
            assertThat(retry.body()).isEqualTo(outcome.body());
            assertThat(retry.replayed()).isTrue();
            assertThat(retry.headers().getLocation())
                    .isEqualTo(outcome.headers().getLocation());
        });
        // An acknowledged order reads back exactly as it was acknowledged.
        Stream.concat(cash.stream(), approved.stream())
                .map(outcomes::get)
                .forEach(created -> assertThat(server.getOrder(UUID.fromString(created.json("$.orderId")))
                                .body())
                        .isEqualTo(created.body()));
        // A declined card holds nothing: its order failed and its reservation was released.
        assertThat(declined).allSatisfy(checkout -> {
            UUID orderId = server.orderIdOf(checkout).orElseThrow();
            assertThat(server.orderStatus(orderId)).contains("PAYMENT_FAILED");
            assertThat(server.reservationOf(orderId))
                    .extracting(ServerDriver.Reservation::status, ServerDriver.Reservation::releaseReason)
                    .containsExactly("RELEASED", "PAYMENT_FAILED");
        });
        // The books: reserved grew by exactly the units of the orders that were confirmed.
        Map<String, Integer> confirmedUnits = Map.of(water, 8, milk, 8, eggs, 4, formula, 0, powerBank, 0);
        assertThat(levels(skus))
                .allSatisfy((sku, after) -> assertThat(after)
                        .as(sku)
                        .isEqualTo(new StockLevel(
                                before.get(sku).onHand(),
                                before.get(sku).reserved() + confirmedUnits.get(sku),
                                before.get(sku).available() - confirmedUnits.get(sku))));
    }

    /**
     * Of the answers to one key, exactly one did the work; the others replay it byte for byte, or ask the client to
     * come back because it was still running.
     */
    private static Reply theOneOutcome(List<Reply> answers) {
        List<Reply> inProgress = answers.stream()
                .filter(answer -> "IDEMPOTENCY_REQUEST_IN_PROGRESS".equals(answer.code()))
                .toList();
        List<Reply> settled = answers.stream()
                .filter(answer -> !"IDEMPOTENCY_REQUEST_IN_PROGRESS".equals(answer.code()))
                .toList();
        assertThat(inProgress).allSatisfy(answer -> {
            assertThat(answer.status()).isEqualTo(409);
            assertThat(answer.headers().getFirst("Retry-After")).isIn("1", "2");
        });
        List<Reply> did = settled.stream().filter(answer -> !answer.replayed()).toList();
        assertThat(did)
                .as("answers that did the work, rather than replaying it")
                .hasSize(1);
        Reply original = did.get(0);
        assertThat(settled).allSatisfy(answer -> {
            assertThat(answer.status()).isEqualTo(original.status());
            assertThat(Arrays.equals(answer.body(), original.body()))
                    .as("replayed byte for byte: %s", answer)
                    .isTrue();
        });
        return original;
    }

    private static void assertConfirmed(Reply outcome) {
        assertThat(outcome.status()).as(outcome.toString()).isEqualTo(201);
        assertThat(outcome.<String>json("$.status")).isEqualTo("CONFIRMED");
        assertThat(outcome.headers().getLocation()).hasToString("/v1/orders/" + outcome.json("$.orderId"));
    }

    private Map<String, StockLevel> levels(List<String> skus) {
        Map<String, StockLevel> levels = new LinkedHashMap<>();
        skus.forEach(sku -> levels.put(sku, server.stock(MARINA, sku)));
        return levels;
    }

    private static List<Checkout> times(int count, Supplier<Checkout> checkout) {
        return IntStream.range(0, count).mapToObj(i -> checkout.get()).toList();
    }
}
