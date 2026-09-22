package io.daqiqa.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

/**
 * Drives the assembled server the way customers and store operators do, over HTTP, and reads its database to see what
 * the requests left behind. Every answer is recorded, so a scenario can prove that none of them was a server error,
 * and {@link #concurrently} fires requests through a start gate, so they reach the server together and really
 * collide.
 */
final class ServerDriver {

    static final String MARINA = "DXB-MARINA";

    /** About 400 m from the Marina store, the fastest store for it: promises for this address come from Marina. */
    static final Location NEAR_MARINA = new Location(25.0772, 55.1390);

    static final String CARD = "CARD";
    static final String CASH_ON_DELIVERY = "CASH_ON_DELIVERY";

    /** How long a burst may take before the scenario fails instead of hanging. */
    private static final Duration BURST_TIMEOUT = Duration.ofSeconds(60);

    private static final Duration POLL_INTERVAL = Duration.ofMillis(10);

    private final RestClient http;
    private final JdbcClient db;
    private final Queue<Reply> replies = new ConcurrentLinkedQueue<>();

    ServerDriver(int port, DataSource database) {
        this.http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
                .build();
        this.db = JdbcClient.create(database);
    }

    record Location(double latitude, double longitude) {

        String json() {
            return String.format(Locale.ROOT, "{\"latitude\": %s, \"longitude\": %s}", latitude, longitude);
        }
    }

    record Line(String sku, int quantity) {}

    static Line line(String sku, int quantity) {
        return new Line(sku, quantity);
    }

    /** A signed promise to deliver a basket to an address from a store. */
    record Offer(String promiseId, String storeId, String token, Location location) {}

    /** A checkout request: the customer's idempotency key and the promise, basket and payment method it carries. */
    record Checkout(String idempotencyKey, UUID customerId, Offer offer, List<Line> basket, String paymentMethod) {

        /** The same request under a new key, as a client that forgot the first key would send it. */
        Checkout withNewKey() {
            return new Checkout(newKey(), customerId, offer, basket, paymentMethod);
        }

        String body() {
            return """
                    {"promiseToken": "%s", "customerId": "%s", "items": %s, "deliveryLocation": %s, \
                    "paymentMethod": "%s"}""".formatted(
                    offer.token(), customerId, items(basket), offer.location().json(), paymentMethod);
        }
    }

    /** One HTTP answer, body kept as the exact bytes the server sent. */
    record Reply(String request, int status, HttpHeaders headers, byte[] body) {

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        <T> T json(String path) {
            return JsonPath.read(text(), path);
        }

        /** The problem's {@code code}, if this is one of Daqiqa's RFC 9457 problems. */
        @Nullable
        String code() {
            try {
                return JsonPath.read(text(), "$.code");
            } catch (RuntimeException notAProblemWithACode) {
                return null;
            }
        }

        boolean replayed() {
            return "true".equals(headers.getFirst("Idempotent-Replayed"));
        }

        @Override
        public String toString() {
            return request + " -> " + status + " " + text();
        }
    }

    record StockLevel(int onHand, int reserved, int available) {}

    /** A reservation as the inventory stores it. */
    record Reservation(UUID id, String status, @Nullable String releaseReason) {}

    /** An integration event in the outbox. */
    record Event(String type, String payload) {

        <T> T json(String path) {
            return JsonPath.read(payload, path);
        }
    }

    // ---- HTTP -------------------------------------------------------------------------------------------------------

    /** A promise for {@code basket} at {@code location}; fails unless the server offers one. */
    Offer promise(Location location, List<Line> basket) {
        Reply reply =
                post("/v1/promises", "{\"location\": " + location.json() + ", \"items\": " + items(basket) + "}", null);
        assertThat(reply.status()).as(reply.toString()).isEqualTo(200);
        assertThat(reply.<String>json("$.status")).as(reply.toString()).isEqualTo("AVAILABLE");
        return new Offer(
                reply.json("$.offer.promiseId"), reply.json("$.offer.storeId"), reply.json("$.offer.token"), location);
    }

    /** A new customer's checkout of {@code basket} near the Marina store, under a promise just made for it. */
    Checkout checkout(String paymentMethod, Line... basket) {
        List<Line> lines = List.of(basket);
        return new Checkout(newKey(), UUID.randomUUID(), promise(NEAR_MARINA, lines), lines, paymentMethod);
    }

    Reply placeOrder(Checkout checkout) {
        return post("/v1/orders", checkout.body(), checkout.idempotencyKey());
    }

    Reply getOrder(UUID orderId) {
        return exchange(
                "GET /v1/orders/" + orderId,
                () -> http.get().uri("/v1/orders/{id}", orderId).retrieve().toEntity(byte[].class));
    }

    StockLevel stock(String store, String sku) {
        Reply reply = exchange(
                "GET stock " + store + "/" + sku,
                () -> http.get()
                        .uri("/v1/stores/{store}/stock?sku={sku}", store, sku)
                        .retrieve()
                        .toEntity(byte[].class));
        assertThat(reply.status()).as(reply.toString()).isEqualTo(200);
        return new StockLevel(
                reply.json("$.items[0].onHand"), reply.json("$.items[0].reserved"), reply.json("$.items[0].available"));
    }

    void adjustStock(String store, String sku, int delta, String reason) {
        Reply reply = post(
                "/v1/stores/" + store + "/stock/adjustments",
                "{\"sku\": \"%s\", \"delta\": %d, \"reason\": \"%s\"}".formatted(sku, delta, reason),
                null);
        assertThat(reply.status()).as(reply.toString()).isEqualTo(200);
    }

    /** Counts the shelf so that exactly {@code available} units of {@code sku} can still be sold at {@code store}. */
    StockLevel makeAvailable(String store, String sku, int available) {
        int delta = available - stock(store, sku).available();
        if (delta != 0) {
            adjustStock(store, sku, delta, "CYCLE_COUNT");
        }
        StockLevel level = stock(store, sku);
        assertThat(level.available()).isEqualTo(available);
        return level;
    }

    void setStoreStatus(String store, String status) {
        Reply reply = exchange(
                "PUT /v1/stores/" + store + "/status",
                () -> http.put()
                        .uri("/v1/stores/{store}/status", store)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\": \"" + status + "\"}")
                        .retrieve()
                        .toEntity(byte[].class));
        assertThat(reply.status()).as(reply.toString()).isEqualTo(200);
    }

    /** Every answer with a 5xx status this driver received. */
    List<Reply> serverErrors() {
        return replies.stream().filter(reply -> reply.status() >= 500).toList();
    }

    private Reply post(String path, String body, @Nullable String idempotencyKey) {
        return exchange("POST " + path + (idempotencyKey == null ? "" : " [" + idempotencyKey + "]"), () -> {
            RestClient.RequestBodySpec request = http.post().uri(path).contentType(MediaType.APPLICATION_JSON);
            if (idempotencyKey != null) {
                request = request.header("Idempotency-Key", idempotencyKey);
            }
            return request.body(body).retrieve().toEntity(byte[].class);
        });
    }

    private Reply exchange(String description, Supplier<ResponseEntity<byte[]>> call) {
        ResponseEntity<byte[]> response = call.get();
        byte[] body = response.getBody();
        Reply reply = new Reply(
                description,
                response.getStatusCode().value(),
                response.getHeaders(),
                body == null ? new byte[0] : body);
        replies.add(reply);
        return reply;
    }

    // ---- SQL --------------------------------------------------------------------------------------------------------

    /** The order a checkout's key was claimed for: fixed at the first claim, before the order exists. */
    Optional<UUID> orderIdOf(Checkout checkout) {
        return db.sql("SELECT order_id FROM ordering.idempotency_key WHERE customer_id = :customer AND key = :key")
                .param("customer", checkout.customerId())
                .param("key", checkout.idempotencyKey())
                .query(UUID.class)
                .optional();
    }

    Optional<String> orderStatus(UUID orderId) {
        return db.sql("SELECT status FROM ordering.customer_order WHERE id = :id")
                .param("id", orderId)
                .query(String.class)
                .optional();
    }

    Reservation reservationOf(UUID orderId) {
        return db.sql("""
                        SELECT id, status, release_reason FROM inventory.reservation
                        WHERE id = (SELECT reservation_id FROM ordering.customer_order WHERE id = :orderId)""")
                .param("orderId", orderId)
                .query((rs, row) -> new Reservation(
                        rs.getObject("id", UUID.class), rs.getString("status"), rs.getString("release_reason")))
                .single();
    }

    long ordersUnderPromise(String promiseId) {
        return db.sql("SELECT count(*) FROM ordering.customer_order WHERE promise_id = :promise")
                .param("promise", UUID.fromString(promiseId))
                .query(Long.class)
                .single();
    }

    /** The events about an order, its reservation's included (their reference is the order id), in outbox order. */
    List<Event> eventsAbout(UUID orderId) {
        return db.sql("""
                        SELECT type, payload::text AS payload FROM messaging.outbox_event
                        WHERE payload ->> 'orderId' = :id OR payload ->> 'reference' = :id
                        ORDER BY seq""")
                .param("id", orderId.toString())
                .query((rs, row) -> new Event(rs.getString("type"), rs.getString("payload")))
                .list();
    }

    // ---- Concurrency and time ---------------------------------------------------------------------------------------

    /**
     * Runs every task at once, each on a thread of its own. The threads wait at a start gate until all of them are
     * ready, so the requests leave together and collide at the server instead of trickling in one after another.
     *
     * @return the tasks' results, in the tasks' order
     */
    static <T> List<T> concurrently(List<? extends Callable<T>> tasks) {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(threads.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS))
                    .as("every thread reached the gate")
                    .isTrue();
            go.countDown();
            List<T> results = new ArrayList<>(futures.size());
            for (Future<T> future : futures) {
                results.add(future.get(BURST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the burst", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("A request of the burst failed or did not finish in " + BURST_TIMEOUT, e);
        } finally {
            threads.shutdownNow();
        }
    }

    /** {@link #concurrently(List)} with one task per input: {@code concurrently(checkouts, server::placeOrder)}. */
    static <I, T> List<T> concurrently(List<I> inputs, Function<? super I, ? extends T> action) {
        return concurrently(inputs.stream()
                .<Callable<T>>map(input -> () -> action.apply(input))
                .toList());
    }

    /** Polls {@code probe} until it has a value, failing after {@code timeout}. */
    static <T> T await(String what, Duration timeout, Supplier<Optional<T>> probe) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            Optional<T> value = probe.get();
            if (value.isPresent()) {
                return value.get();
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out after " + timeout + " waiting for " + what);
            }
            try {
                Thread.sleep(POLL_INTERVAL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for " + what, e);
            }
        }
    }

    static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static String items(List<Line> basket) {
        return basket.stream()
                .map(line -> "{\"sku\": \"%s\", \"quantity\": %d}".formatted(line.sku(), line.quantity()))
                .collect(Collectors.joining(", ", "[", "]"));
    }
}
