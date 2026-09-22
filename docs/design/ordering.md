# Ordering and messaging — design specification (wave 1)

> Owner: ordering engineer. Status: specification; add "Implementation notes" at the end as you build.
> Related: ADR-0008 (idempotency), ADR-0009 (outbox), ADR-0005 (reservations), ADR-0007 (tokens).

## Part 1 — Ordering

### Order model

`Order`: `id` (UUIDv7), `customerId`, `storeId`, `promiseId`, `tier`, `etaMinutes`, `placedAt`,
`deliverBy = placedAt + etaMinutes`, delivery location, payment method, `reservationId`, lines (SKU, bilingual name,
quantity, unit price — **snapshotted** at order time), total, status.

```
AWAITING_PAYMENT ──▶ CONFIRMED ──▶ (wave 2: PICKING → OUT_FOR_DELIVERY → DELIVERED)
       │   \
       │    └──▶ PAYMENT_FAILED
       └──▶ ABANDONED (hold expired / payment never completed)
```

Cash-on-delivery orders are created directly as `CONFIRMED`. Transitions are enforced in the domain.

### Checkout: `POST /v1/orders`

1. **Validate**: `Idempotency-Key` present and well-formed (400 `IDEMPOTENCY_KEY_MISSING` / `VALIDATION_FAILED`),
   body valid, basket merged.
2. **Claim the key** (own short transaction, ADR-0008): table keyed by `(customer_id, key)` with the SHA-256
   `request_hash` of a canonical form of the request. Outcomes:
   - new → claim `IN_PROGRESS` with a lease (`locked_until`, default 60 s) and continue;
   - `COMPLETED` + same hash → **replay** the stored status and body;
   - `COMPLETED` + different hash → 422 `IDEMPOTENCY_KEY_REUSED`;
   - `IN_PROGRESS` with a live lease → 409 `IDEMPOTENCY_REQUEST_IN_PROGRESS` (with `Retry-After`);
   - `IN_PROGRESS` with an expired lease (a crashed instance) → take it over and continue.
3. **Verify the promise** with `PromiseTokenVerifier` → 422 `PROMISE_EXPIRED` / `PROMISE_INVALID` /
   `PROMISE_CART_MISMATCH`.
4. **Price** the basket with `ProductCatalog.findAll` → 422 `UNKNOWN_SKU` if any SKU is missing.
5. **Transaction T1**: `InventoryReservations.reserve(store from the promise, reference = orderId, lines,
   hold = daqiqa.ordering.hold, default 5 min)`. `Rejected` → 409 `OUT_OF_STOCK` with `shortages`. Otherwise insert the
   order: `CONFIRMED` for cash on delivery (also confirm the reservation, append `OrderPlacedV1`, complete the key with
   the 201 response — all in T1), `AWAITING_PAYMENT` for card.
6. **Card payment**, outside any transaction: `PaymentGateway.authorize(orderId as the provider idempotency key,
   amount)`.
7. **Transaction T2**: approved → confirm the reservation, `CONFIRMED`, append `OrderPlacedV1`, complete the key with
   201. Declined → release the reservation (`PAYMENT_FAILED` reason), `PAYMENT_FAILED`, complete the key with 402
   `PAYMENT_DECLINED`. If `confirm` throws `ReservationNoLongerHeldException` (payment took longer than the hold) →
   `ABANDONED`, complete the key with 409 `OUT_OF_STOCK` semantics or a dedicated code — document your choice.
8. Deterministic business outcomes (4xx) are stored on the key and replayed. Unexpected failures (5xx) **release the
   claim** so the client can retry with the same key.

### Payment gateway (simulated)

Port `PaymentGateway` (application). v1 adapter `SimulatedPaymentGateway`: approves unless the amount exceeds
`daqiqa.ordering.payment.decline-above` (default 1,000.00 AED) — deterministic for tests and demos; optional
`latency`. Real providers come later behind the same port.

### Abandoned-order sweeper

Scheduled: `AWAITING_PAYMENT` orders older than the hold → `ABANDONED` and release their reservation
(`ORDER_ABANDONED`). Batch with `FOR UPDATE SKIP LOCKED`.

### `GET /v1/orders/{orderId}` → `Order` or 404 `ORDER_NOT_FOUND`.

### Schema (suggested; you own it)

`V5_1__ordering_schema.sql`: `ordering.customer_order` (partial index on `placed_at WHERE status = 'AWAITING_PAYMENT'`
for the sweeper; index `(customer_id, placed_at DESC)` for "my orders"), `ordering.order_line` (primary key
`(order_id, line_no)`), `ordering.idempotency_key` (primary key `(customer_id, key)`, index on `expires_at` for
cleanup). Clean up expired keys on a schedule.

### Metrics

`daqiqa.ordering.checkouts` (counter, tag `outcome=confirmed|out_of_stock|payment_declined|promise_invalid|replayed|...`),
`daqiqa.ordering.checkout` (timer).

### Required tests

- Domain: order state machine, pricing.
- Checkout service with fakes for the ports: every outcome in the table above.
- **Concurrency**: 20 identical requests with the same key in parallel → exactly one order and one reservation; all
  responses are either the 201 (replayed) or `IDEMPOTENCY_REQUEST_IN_PROGRESS`.
- Persistence against real PostgreSQL, the sweeper, web slices (headers, status codes, problem codes, `Location`),
  `ArchitectureTest`.

## Part 2 — Messaging (outbox, ADR-0009)

- Table `messaging.outbox_event` (`V6_1__messaging_schema.sql`): `id uuid` (= event id), `seq bigint generated always
  as identity` (publication order), `type`, `topic`, `partition_key`, `payload jsonb`, `occurred_at`, `created_at`,
  `published_at`, `attempts`, `last_error`; partial index on `seq WHERE published_at IS NULL`.
- `JdbcOutboxWriter implements OutboxWriter`: throws `IllegalStateException` without an active transaction;
  serialises the event record with Jackson 3.
- `OutboxRelay` (scheduled, `daqiqa.messaging.relay.interval`, default 250 ms): in one transaction, take
  `pg_try_advisory_xact_lock(<constant>)` — if another instance holds it, skip this round (one relay at a time keeps
  per-key order); select a batch of unpublished rows by `seq` `FOR UPDATE SKIP LOCKED`, publish them in order, mark
  them published. On a publish failure, record `attempts`/`last_error` and stop the batch (later events for the same
  key must not overtake it).
- `EventPublisher` port with two adapters, chosen by `daqiqa.messaging.publisher`:
  - `kafka`: `KafkaTemplate<String, String>`, key = partition key, CloudEvents headers (`ce_specversion=1.0`, `ce_id`,
    `ce_type`, `ce_source=daqiqa-server`, `ce_time`), idempotent producer, wait for acknowledgements;
  - `logging` (default for local runs without Kafka): logs the event.
- Retention: delete published rows older than `daqiqa.messaging.retention` (default 7 days) on a schedule.
- Metrics: `daqiqa.outbox.backlog` (gauge: unpublished rows), `daqiqa.outbox.published`, `daqiqa.outbox.failures`.
- Ordering guarantee to document: events of one aggregate are published in the order they were committed; unrelated
  concurrent transactions for the same store may interleave, which is fine because they are not causally related.
- Tests: writer requires a transaction; relay publishes in `seq` order and marks rows; failure stops the batch;
  two relays in parallel never publish the same row twice; Kafka adapter with `@EmbeddedKafka` (key, payload,
  headers).

## Implementation notes (wave 1)

Everything in the specification above is built as written, except where this section says otherwise and why.

### What was built

- **Messaging** (`platform/messaging`): `messaging.outbox_event` (`V6_1`); `JdbcOutboxWriter`; `OutboxRelay`
  (advisory-lock leader, `seq` order, `FOR UPDATE SKIP LOCKED`, stop at the first failure); the `EventPublisher` port
  with `KafkaEventPublisher` and `LoggingEventPublisher`; `OutboxPurge` (retention); metrics.
- **Ordering** (`modules/ordering`): the `Order` aggregate and `BasketPricing` (domain); `ordering.customer_order`,
  `ordering.order_line`, `ordering.idempotency_key` (`V5_1`); idempotent checkout (`CheckoutService` →
  `CheckoutClaims`, `CheckoutFlow`, `CheckoutTransactions`); `GET /v1/orders/{orderId}`; the `PaymentGateway` port and
  `SimulatedPaymentGateway`; `AbandonedOrderSweeper`; `IdempotencyKeyPurge`; metrics.

### Checkout as built

```
POST /v1/orders
  │ 400 VALIDATION_FAILED / IDEMPOTENCY_KEY_MISSING before anything is written
  ▼
T0  claim the key ──────────────▶ replay the stored response │ 422 KEY_REUSED │ 409 IN_PROGRESS + Retry-After
  │ (new key, or a takeover after the lease ran out; the order id is fixed at the first claim)
  ▼
    verify the promise, price the basket          (no transaction: HMAC and one catalog read)
  ▼
T1  hold the claim · order exists? → resume it    (a retry that took over a dead instance's claim)
    refused? → store 422                          (PROMISE_* / UNKNOWN_SKU)
    reserve → Rejected → store 409 OUT_OF_STOCK
            → Reserved → insert the order
                         cash: confirm reservation · OrderPlacedV1 · store 201      (done)
                         card: AWAITING_PAYMENT
  ▼
    authorize the card                            (no transaction; the order id is the provider's idempotency key)
  ▼
T2  hold the claim · lock the order
    approved → confirm reservation · CONFIRMED · OrderPlacedV1 · store 201
    declined → release (PAYMENT_FAILED) · PAYMENT_FAILED · store 402 PAYMENT_DECLINED
    order already ABANDONED by the sweeper → store 409 HOLD_EXPIRED
    confirm throws ReservationNoLongerHeldException → T2 rolls back, then
T3  hold the claim · lock the order · ABANDONED · release (ORDER_ABANDONED) · store 409 HOLD_EXPIRED
```

Any unexpected exception releases the claim and propagates (500). Business outcomes are values, not exceptions: the
problem handler only maps validation failures, key misuse, the in-progress answer and `ORDER_NOT_FOUND`.

### Decisions and deviations

1. **The order id is fixed when the key is first claimed** and stored on the key. Every later step is idempotent by
   it: it is the reservation's `reference`, the payment provider's idempotency key and the order's primary key. A retry
   that takes over the claim of a dead instance therefore *continues the same order* instead of starting a second one
   (which would hold the stock twice until the sweeper caught up, and could authorize the card twice). ADR-0008's
   "payments use the order id as the provider's idempotency key" only works if the id survives retries; this is what
   makes it so.
2. **A takeover resumes, it does not restart.** If the order already exists, the attempt skips the promise's verdict
   (the promise was honoured when the order was created and may well have expired since) and continues from the
   order's status: an order still awaiting payment is authorized again (the provider deduplicates), an abandoned one is
   answered with `HOLD_EXPIRED`.
3. **Every claim transaction starts by locking the key row** (`CheckoutClaims.hold`: `SELECT ... FOR UPDATE` on the key,
   still `IN_PROGRESS`, still for this order). This serialises all attempts with one key. Without it, an attempt that
   stalled past its lease could confirm an order while a newer attempt stored `PROMISE_EXPIRED` for the same key, and
   the key's answer would contradict the order. It is also why refusals decided before T1 are stored *in* T1: under the
   lock, after checking that no attempt created the order meanwhile. An attempt that finds the claim settled rolls back
   and answers with the stored response.
4. **"Release the claim" ends the lease rather than deleting the key.** The row keeps the order id, so the client's
   retry (immediately, no `409`) takes the claim over and continues the same order, for example one whose stock is held
   and whose payment call timed out. Deleting the key would lose that link.
5. **A different request is refused whatever the key's state** (`422 IDEMPOTENCY_KEY_REUSED` for in-progress keys too,
   not only completed ones): it can never succeed with that key, so there is nothing to wait for.
6. **An expired key (24 h) is forgotten** at the next claim even if the purge has not deleted it yet, so behaviour does
   not depend on when the purge last ran. The purge re-checks expiry on the row it deletes, so it never deletes a key
   that was just reclaimed.
7. **Payment outlives the hold → `409 HOLD_EXPIRED`**, a dedicated code rather than `OUT_OF_STOCK`: nothing may actually
   have sold out, and `shortages` would have to be invented. It keeps `409`, so a client that treats `409` as "ask for a
   new promise" does the right thing. The same code answers a checkout whose order the sweeper abandoned while the
   payment was in flight. When `confirm` refuses, T2 rolls back *entirely*, including whatever the inventory wrote while
   refusing (a real inventory joined to T2 is rolled back with it), and T3 decides afresh: abandon the order and release
   the reservation, which is a no-op if the inventory had already expired it. This is correct whichever way the
   inventory's own transaction settings go. (Contract change requested below.)
8. **Responses are rendered once and stored as bytes.** The use case calls a `CheckoutResponseRenderer` port, implemented
   by the web adapter with the application's `JsonMapper`, inside the transaction that settles the order; the controller
   sends the stored bytes for the first response too, so the original and every replay are identical by construction.
   `response_body` is `text`, not `jsonb`, which would reorder keys and whitespace. Stored problems set `instance` to
   `/v1/orders` exactly as Spring MVC does for the problems it renders (a test compares the two shapes). Replays carry
   `Idempotent-Replayed: true`; the `Location` of a `201` is relative (`/v1/orders/{id}`), so it is stable across hosts.
9. **The sweeper abandons each order in its own short transaction**, locking it with `FOR UPDATE SKIP LOCKED` (instead
   of one transaction for the whole batch): an order a checkout is settling is skipped, never waited for, and one order
   whose release fails is counted (`daqiqa.ordering.sweeper.failures`) and retried next sweep instead of rolling back
   and blocking the rest of the batch forever. The sweeper does not touch idempotency keys: the checkout that owns the
   key answers `HOLD_EXPIRED` when it finds the order abandoned.
10. **Timestamps are truncated to milliseconds** when an order is placed: PostgreSQL and JSON both keep them exactly, so
    the `201` body and a later `GET` are byte-identical (tested).
11. **The domain keeps the delivery tier as text.** The promise context owns the tiers; an order records which one it
    was sold under, and the domain stays free of other modules' types (conventions: the domain depends only on the
    kernel).
12. **Baskets are validated twice**: Bean Validation for shape (1–50 lines, SKU format, quantity 1–99, coordinates),
    then the value objects, which catch what annotations cannot (the same SKU on two lines adding up to more than 99):
    both answer `400 VALIDATION_FAILED` before the key is claimed. The request fingerprint is the SHA-256 of a canonical
    form (basket merged and sorted by SKU), so listing the same basket in another order is the same request.
13. **Messaging.** Events are serialised with a dedicated Jackson 3 mapper: the payload is a published contract and must
    not change shape when someone tunes `spring.jackson.*` for the HTTP API. The relay publishes sequentially and waits
    for each acknowledgement (simple and obviously order-preserving; see next steps for throughput), treats *any*
    exception from the publisher as a failed publication of that event, and marks the events published before it. The
    Kafka publisher refuses to start with `acks` other than `all` or with idempotence disabled. The logging publisher
    logs the envelope only: payloads carry a delivery location, which is personal data. The backlog gauge is read at
    scrape time from the partial index on unpublished rows.

**Ordering guarantee of the outbox.** Events of one aggregate are published in the order they were committed: the
transactions that append them are serialised by the aggregate's row lock, so a later event also gets a higher `seq` and
becomes visible only after the earlier one committed. Unrelated concurrent transactions for the same store may
interleave, which is fine because they are not causally related. Delivery is at-least-once (a relay that dies after
the broker acknowledged but before its round committed publishes those events again); consumers deduplicate on
`eventId`.

### Configuration

| Property | Default | Meaning |
|---|---|---|
| `daqiqa.messaging.publisher` | `logging` | `logging` or `kafka`; selects the `EventPublisher` bean (the Kafka one exists only for `kafka`) |
| `daqiqa.messaging.relay.interval` | `250ms` | pause between relay rounds |
| `daqiqa.messaging.relay.batch-size` | `100` | events per round at most |
| `daqiqa.messaging.retention` | `7d` | published events are deleted after this |
| `daqiqa.messaging.purge.interval` / `.batch-size` | `1h` / `1000` | how often and in what batches |
| `daqiqa.messaging.kafka.send-timeout` | `10s` | wait for one acknowledgement before counting a failure |
| `daqiqa.ordering.hold` | `5m` | stock hold per checkout (30 s–30 min, as the inventory accepts); also the sweeper's cutoff |
| `daqiqa.ordering.idempotency.lease` | `60s` | exclusive claim; a retry after it takes over |
| `daqiqa.ordering.idempotency.retention` | `24h` | how long keys and responses are kept |
| `daqiqa.ordering.idempotency.purge-interval` / `.purge-batch-size` | `1h` / `1000` | key purge |
| `daqiqa.ordering.sweeper.interval` / `.batch-size` | `30s` / `100` | abandoned-order sweeper |
| `daqiqa.ordering.payment.decline-above` | `1000.00` | simulated gateway declines amounts above this (major units) |
| `daqiqa.ordering.payment.latency` | `0s` | simulated gateway delay |

Invalid values fail at startup (for example a hold the inventory would refuse, or a retention shorter than the lease).
The scheduled delays read the same properties, with the same defaults, through shared constants.

**Kafka producer settings the server must use** (`spring.kafka.*`; Kafka's own defaults already give `acks=all` and
idempotence, and the publisher refuses anything weaker):

```yaml
spring:
  kafka:
    bootstrap-servers: ${DAQIQA_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      acks: all
      properties:
        enable.idempotence: true
        max.in.flight.requests.per.connection: 5 # at most 5 keeps idempotent retries in order
        linger.ms: 0                             # the relay sends one event and waits; batching delay is pure latency
        request.timeout.ms: 5000
        delivery.timeout.ms: 8000                # below daqiqa.messaging.kafka.send-timeout: a failure the relay
                                                 # records is final, no stray retry lands later
    # Never set spring.kafka.producer.transaction-id-prefix: Boot would add a KafkaTransactionManager, which displaces
    # the JDBC transaction manager the outbox and checkout rely on.
daqiqa:
  messaging:
    publisher: ${DAQIQA_EVENT_PUBLISHER:logging} # kafka in real deployments
```

### Metrics

| Metric | Type, tags | Question it answers |
|---|---|---|
| `daqiqa.ordering.checkouts` | counter, `outcome` | how checkouts end: `confirmed`, `out_of_stock`, `payment_declined`, `hold_expired`, `promise_expired`, `promise_invalid`, `promise_cart_mismatch`, `unknown_sku`, `replayed`, `idempotency_request_in_progress`, `idempotency_key_reused`, `failed` |
| `daqiqa.ordering.checkout` | timer, `outcome` | how long each kind takes, payment included |
| `daqiqa.ordering.abandoned` | counter | orders abandoned because payment did not complete within the hold |
| `daqiqa.ordering.sweeper.failures` | counter | orders the sweeper could not abandon (retried) |
| `daqiqa.outbox.backlog` | gauge | committed events not yet published: alert when it grows |
| `daqiqa.outbox.published` / `daqiqa.outbox.failures` | counters, `type` | publications and failed attempts per event type |

### How the tests prove each guarantee

| Guarantee | Test (all against real PostgreSQL unless noted) |
|---|---|
| The outbox writer refuses to run without a transaction; a rolled-back event never exists | `JdbcOutboxWriterTest` |
| The relay publishes in `seq` order and marks rows; one batch per round | `OutboxRelayTest` |
| A failure stops the round, records `attempts`/`last_error`, nothing overtakes it | `OutboxRelayTest.stopsTheRoundAtTheFirstFailure...` |
| Only one relay at a time; two concurrent relays never publish a row twice, and keep the order | `OutboxRelayTest.skipsTheRound...`, `twoRelaysRunningConcurrently...` |
| At-least-once: a relay dying before commit publishes again, loses nothing | `OutboxRelayTest.anEventIsPublishedAgain...` |
| Kafka: key = store, payload, CloudEvents headers, one partition per store in order; acks awaited | `OutboxToKafkaTest` (`@EmbeddedKafka`) |
| The publisher is chosen by property; the Kafka one refuses a non-durable producer; unacknowledged sends fail | `PublisherSelectionTest`, `KafkaEventPublisherTest` (no broker) |
| Retention deletes only old published events | `OutboxPurgeTest` |
| Scheduled jobs run on their configured intervals | `MessagingSchedulingTest`, `OrderingSchedulingTest` |
| Order state machine, cash-on-delivery rules, pricing snapshots and totals | `OrderTest`, `OrderStatusTest`, `BasketPricingTest` (unit + jqwik) |
| The idempotency decision table | `IdempotencyRecordTest` (unit) |
| Every checkout outcome, stored and replayed; resumption after a crash; release after a failure | `CheckoutServiceTest` (fakes for promise, inventory, catalog, payment) |
| **20 identical requests in parallel → one order, one reservation, one charge, one event; answers are the 201 or `IN_PROGRESS`; 20 concurrent retries all replay** | `CheckoutConcurrencyTest` (repeated 3×) |
| Sweeper: abandons after the hold, releases stock, skips locked orders, isolates failures | `AbandonedOrderSweeperTest` |
| Round trips, lost-update guard, `SKIP LOCKED`, CHECK constraints, byte-exact response storage | `JdbcOrderRepositoryTest`, `JdbcIdempotencyKeyRepositoryTest` |
| Wire contract: status codes, problem codes, `Location`, `Retry-After`, missing key → 400 | `OrderControllerTest` (`@WebMvcTest`) |
| Replays are byte-identical over HTTP; `GET` returns the `201` body; stored problems look like Spring's | `CheckoutHttpTest` |
| Hexagonal layers; other modules used only through their `api` | `ArchitectureTest` |

The fakes honour their contracts, which the atomicity tests depend on: `FakeInventory` joins the caller's transaction
(its changes are undone when the transaction rolls back) and expires a hold on a late `confirm`.

### Next steps

- **Void authorizations that end without an order** (`HOLD_EXPIRED` after an approval): add `PaymentGateway.voidAuthorization`
  and drive it from an outbox event so it is retried until it succeeds. The simulated gateway holds no money, so v1 is
  unaffected; a real provider must not go live without it.
- **Relay throughput**: publishing is sequential (about 1/ack-latency events per second, a few hundred per second with
  a local cluster). Next: pipeline each batch's sends and await them in order (the idempotent producer keeps per-partition
  order), or move to Debezium CDC on the same table (ADR-0009).
- **Poison events** block the relay by design (order first); add an alert on `daqiqa.outbox.failures` and on backlog age,
  and an operator command to park an event.
- React to `ReservationExpiredV1` (abandon the order immediately instead of waiting for the sweeper), cancellation
  (`CANCELLED`, in the contract but not produced yet), and "my orders" listing (the index exists).
- Contract updates (below) and authentication: the customer id comes from the body until identity arrives; `GET` does
  not check ownership yet.

### Changes needed in shared files (not made here)

- `.gitignore`: `out/` also ignores every `adapter/out` package (persistence and payment adapters were silently left out
  of commits until force-added). Anchor it: `/out/`.
- `api/openapi/daqiqa-v1.yaml`, `POST /v1/orders`: document `HOLD_EXPIRED` under `409`, the `Retry-After` header on
  `IDEMPOTENCY_REQUEST_IN_PROGRESS`, and the `Idempotent-Replayed` header on replays; `GET /v1/orders/{orderId}`: add
  `400` (`VALIDATION_FAILED` for a malformed id).
- `apps/daqiqa-server/src/main/resources/application.yaml`: the Kafka settings above.
