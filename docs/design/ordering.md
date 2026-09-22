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

Everything in the specification above is built as written, except where this section says otherwise and why. The
fixes from the wave 1 review are folded in.

### What was built

- **Messaging** (`platform/messaging`): `messaging.outbox_event` (`V6_1`); `JdbcOutboxWriter`; `OutboxRelay`
  (advisory-lock leader, `seq` order, `FOR UPDATE SKIP LOCKED`, stops at the first failure, exponential backoff per
  event); the `EventPublisher` port with `KafkaEventPublisher` (the default) and `LoggingEventPublisher` (opt-in);
  `OutboxPurge` (retention); metrics.
- **Ordering** (`modules/ordering`): the `Order` aggregate and `BasketPricing` (domain); `ordering.customer_order`,
  `ordering.order_line` and `ordering.idempotency_key` (`V5_1`); idempotent checkout (`CheckoutService` →
  `CheckoutClaims`, `CheckoutFlow`, `CheckoutTransactions`); `OrderAbandonment`, the single way an order gets
  abandoned; `GET /v1/orders/{orderId}`; the `PaymentGateway` port and `SimulatedPaymentGateway`;
  `AbandonedOrderSweeper`; `IdempotencyKeyPurge`; metrics.
- **Contracts** (`platform/contracts`, agreed with the coordinator): `OrderAbandonedV1`.

### Checkout as built

```
POST /v1/orders
  │ 400 VALIDATION_FAILED / IDEMPOTENCY_KEY_MISSING before anything is written
  ▼
T0  claim the key ──────────────▶ replay the stored response │ 422 KEY_REUSED │ 409 IN_PROGRESS + Retry-After
  │ (new key, or a takeover after the lease ran out; the order id is fixed at the first claim)
  ▼
    verify the promise against the basket and the address,
    price the basket                              (no transaction: HMAC, one catalog read)
  ▼
T1  hold the claim · order exists? → continue it  (this attempt took over the claim of a stalled or dead one)
        AWAITING_PAYMENT, hold running  → pay it (below)
        AWAITING_PAYMENT, hold run out  → abandon · store 409 HOLD_EXPIRED
        ABANDONED (by the sweeper)      → store 409 HOLD_EXPIRED
    refused? → store 422 PROMISE_* / UNKNOWN_SKU, or 409 STORE_UNAVAILABLE
    a live order already holds the promise → store 409 PROMISE_ALREADY_USED
    reserve → Rejected → store 409 OUT_OF_STOCK
            → Reserved → insert the order, unless a live order took the promise meanwhile
                         (then T1 rolls back, stock included, and a new T1 stores 409 PROMISE_ALREADY_USED)
                         cash: confirm reservation · OrderPlacedV1 · store 201      (done)
                         card: AWAITING_PAYMENT
  ▼
    authorize the card                            (no transaction; the order id is the provider's idempotency key)
  ▼
T2  hold the claim · lock the order
    approved → confirm reservation · CONFIRMED · OrderPlacedV1 · store 201
               confirm refused (ReservationNoLongerHeldException) → abandon · store 409 HOLD_EXPIRED
    declined → release (PAYMENT_FAILED) · PAYMENT_FAILED · store 402 PAYMENT_DECLINED
    order already ABANDONED by the sweeper → store 409 HOLD_EXPIRED
T3  only if T2 fails to commit after a refused confirmation:
    hold the claim · lock the order · abandon unless the sweeper did · store 409 HOLD_EXPIRED

abandon = ABANDONED · release (ORDER_ABANDONED) · OrderAbandonedV1, all in the transaction that decides it
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
2. **A takeover continues, it does not restart.** If the order already exists, the attempt skips the promise's verdict
   (the promise was honoured when the order was created and may well have expired since) and carries on from the
   order's status. An order awaiting payment whose hold is still running is authorized again (the provider
   deduplicates). One whose hold has run out (`placedAt + daqiqa.ordering.hold`) is abandoned there and then, under the
   key lock, instead of authorizing a card for an order that can no longer be confirmed: the sweeper may simply not
   have run yet. An abandoned one is answered with `HOLD_EXPIRED`. `placedAt` is read just after the stock is held, so
   this check and the inventory's own expiry differ by no more than the reservation call took; a payment that lands in
   between is refused by `confirm` and handled in T2.
3. **Every claim transaction starts by locking the key row** (`CheckoutClaims.hold`: `SELECT ... FOR UPDATE` on the key,
   still `IN_PROGRESS`, still for this order). This serialises all attempts with one key. Without it, an attempt that
   stalled past its lease could confirm an order while a newer attempt stored `PROMISE_EXPIRED` for the same key, and
   the key's answer would contradict the order. It is also why refusals decided before T1 are stored *in* T1: under the
   lock, after checking that no attempt created the order meanwhile. An attempt that finds the claim settled rolls back
   and answers with the stored response, byte for byte what the other attempt answered.
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
   new promise" does the right thing. The `detail` says what happens to the money in each of the three ways to get
   there: no payment completed (a retry found the hold run out, or the sweeper abandoned the order first: "you will not
   be charged; if your bank reserved the amount, it will be released"), the payment was declined while the sweeper
   abandoned the order, or it was approved only after the hold expired ("the amount your bank reserved will be
   released"). A refused `confirm` is caught *inside* T2, which abandons the order and commits: the inventory expires
   the hold in the caller's transaction and, as its contract states, does not mark that transaction rollback-only.
   T3 stays as a fallback for an inventory that does anyway: T2 then fails to commit with everything it wrote, and T3
   abandons the order in a transaction of its own, unless the sweeper did so in between. Only a refused confirmation
   arms the fallback; any other failed commit propagates.
8. **Every abandonment is announced.** `OrderAbandonment` is the single path (sweeper, T1, T2, T3): `ABANDONED`,
   reservation released, and `OrderAbandonedV1` appended to the outbox in the same transaction (topic `ORDERS`, keyed
   by store; `orderId`, `storeId`, `customerId`, `paymentMethod`, `reason`, `paymentAuthorizationMayExist`,
   `totalMinor`, `currency`). A committed abandonment is logged at WARN and counted by reason. The consumer is the
   future payments void job: it voids whatever authorization exists under the order id, retried until it succeeds.
   `paymentAuthorizationMayExist` is `true` for every card order, not only for `PAID_AFTER_HOLD_EXPIRED`: an attempt
   that crashed after the provider approved but before T2 leaves no trace here, so checkout cannot rule an
   authorization out, and the job must treat an order without one as nothing to void. (Only card orders wait for a
   payment, so only card orders are ever abandoned.)
9. **A promise buys at most one live order.** A partial unique index on `customer_order (promise_id) WHERE status IN
   ('AWAITING_PAYMENT', 'CONFIRMED')` enforces it; the insert uses `ON CONFLICT ... DO NOTHING`. A second order under
   the same promise with another key gets `409 PROMISE_ALREADY_USED`, stored and replayed like every refusal. The check
   runs twice: once before holding stock, so that a sequential second order is told the truth rather than
   `OUT_OF_STOCK` (the first order holds the items), and once at the insert, which settles a race between keys; the
   loser's T1 rolls back, its stock hold included, and the refusal is stored under its key in a new T1. An order whose
   payment failed or that was abandoned does not block the promise, which may be used again while it is valid.
10. **The promise is verified against the delivery address and the store** (`PromiseTokenVerifier.verify(token, lines,
    deliveryLocation)`): `LOCATION_MISMATCH` → `422 PROMISE_LOCATION_MISMATCH`, `STORE_UNAVAILABLE` →
    `409 STORE_UNAVAILABLE` (nothing is wrong with the request; the store stopped taking orders). Both are stored and
    replayed.
11. **Responses are rendered once and stored as bytes.** The use case calls a `CheckoutResponseRenderer` port,
    implemented by the web adapter with the application's `JsonMapper`, inside the transaction that settles the order;
    the controller sends the stored bytes for the first response too, so the original and every replay are identical by
    construction. `response_body` is `text`, not `jsonb`, which would reorder keys and whitespace. Stored problems set
    `instance` to `/v1/orders` exactly as Spring MVC does for the problems it renders (a test compares the two shapes).
    Replays carry `Idempotent-Replayed: true`; the `Location` of a `201` is relative (`/v1/orders/{id}`), so it is
    stable across hosts.
12. **`Retry-After` on `409 IDEMPOTENCY_REQUEST_IN_PROGRESS` is the remaining lease, capped at 2 s** (at least 1 s).
    The first request usually finishes within a second or two, so the client comes back soon and is answered with the
    replay; if the owner died, a later retry takes over once the lease ends. Asking every client to wait out a full
    60 s lease would turn a brief overlap into a minute-long checkout.
13. **The sweeper abandons each order in its own short transaction**, locking it with `FOR UPDATE SKIP LOCKED` (instead
    of one transaction for the whole batch): an order a checkout is settling is skipped, never waited for, and one order
    whose release fails is counted (`daqiqa.ordering.sweeper.failures`) and retried next sweep instead of rolling back
    and blocking the rest of the batch forever. The sweeper does not touch idempotency keys: the checkout that owns the
    key answers `HOLD_EXPIRED` when it finds the order abandoned.
14. **Timestamps are truncated to milliseconds** when an order is placed: PostgreSQL and JSON both keep them exactly, so
    the `201` body and a later `GET` are byte-identical (tested).
15. **The domain keeps the delivery tier as text.** The promise context owns the tiers; an order records which one it
    was sold under, and the domain stays free of other modules' types (conventions: the domain depends only on the
    kernel).
16. **Baskets are validated twice**: Bean Validation for shape (1–50 lines, SKU format, quantity 1–99, coordinates),
    then the value objects, which catch what annotations cannot (the same SKU on two lines adding up to more than 99):
    both answer `400 VALIDATION_FAILED` before the key is claimed. The request fingerprint is the SHA-256 of a canonical
    form (basket merged and sorted by SKU, `-0.0` written as `0.0`), so listing the same basket in another order is the
    same request.
17. **Messaging.** Events are serialised with a dedicated Jackson 3 mapper: the payload is a published contract and must
    not change shape when someone tunes `spring.jackson.*` for the HTTP API. The relay publishes sequentially and waits
    for each acknowledgement (simple and obviously order-preserving; see next steps for throughput), treats *any*
    exception from the publisher as a failed publication of that event, and marks the events published before it
    (`... AND published_at IS NULL`, so every hot statement repeats the partial index's predicate and uses it; a test
    checks the plans). A failed event is retried after a backoff that doubles per attempt (`next_attempt_at`; 1 s up to
    5 min), and while it waits nothing after it is published; its failures are logged at WARN on attempts 1, 2, 4, 8,
    ..., at DEBUG otherwise. The logging publisher logs the envelope only: payloads carry a delivery location, which is
    personal data. The backlog gauge is read at scrape time from the partial index on unpublished rows.
18. **Kafka is the default publisher, and only an explicitly durable producer is accepted** (a deviation from the
    specification, which made `logging` the default). A deployment that forgets the setting must not mark events
    published that went nowhere; `daqiqa.messaging.publisher=logging` is an explicit opt-in for local runs. The Kafka
    publisher refuses to start unless `acks=all` and `enable.idempotence=true` are set explicitly (Kafka's defaults
    changed between versions: `acks` was `1` before 3.0), `max.in.flight.requests.per.connection` is at most 5, and
    `max.block.ms` is set and at most `daqiqa.messaging.kafka.send-timeout`: `send` itself may block that long on
    metadata or a full buffer before the relay's own timeout starts, while holding the relay lock. The error names every
    missing setting.
19. **The scheduled jobs assume a multi-threaded scheduler.** The relay, the outbox purge, the sweeper and the key
    purge are `@Scheduled(fixedDelay)`. Boot's default scheduler has one thread (`spring.task.scheduling.pool.size=1`),
    and with virtual threads enabled Boot's `SimpleAsyncTaskScheduler` runs every fixed-delay task on its single
    scheduler thread; either way, a relay waiting on an unreachable broker would hold up the sweeper that gives stock
    back. The jobs are not redesigned around this: the server provides a multi-threaded `ThreadPoolTaskScheduler` (the
    coordinator adds it), and modules only declare `@Scheduled` methods (no `@EnableScheduling` here).

**Ordering guarantee of the outbox.** Events of one aggregate are published in the order they were committed: the
transactions that append them are serialised by the aggregate's row lock, so a later event also gets a higher `seq` and
becomes visible only after the earlier one committed. Unrelated concurrent transactions for the same store may
interleave, which is fine because they are not causally related. Delivery is at-least-once (a relay that dies after
the broker acknowledged but before its round committed publishes those events again); consumers deduplicate on
`eventId`.

### Configuration

| Property | Default | Meaning |
|---|---|---|
| `daqiqa.messaging.publisher` | `kafka` | `kafka`, or `logging` as an explicit opt-in for local runs; selects the `EventPublisher` bean |
| `daqiqa.messaging.relay.interval` | `250ms` | pause between relay rounds |
| `daqiqa.messaging.relay.batch-size` | `100` | events per round at most |
| `daqiqa.messaging.relay.initial-backoff` / `.max-backoff` | `1s` / `5m` | wait before retrying a failed event, doubled per attempt, capped |
| `daqiqa.messaging.retention` | `7d` | published events are deleted after this |
| `daqiqa.messaging.purge.interval` / `.batch-size` | `1h` / `1000` | how often and in what batches |
| `daqiqa.messaging.kafka.send-timeout` | `10s` | wait for one acknowledgement before counting a failure |
| `daqiqa.ordering.hold` | `5m` | stock hold per checkout (30 s–30 min, as the inventory accepts); also the sweeper's cutoff |
| `daqiqa.ordering.idempotency.lease` | `60s` | exclusive claim; a retry after it takes over. Must be shorter than the hold |
| `daqiqa.ordering.idempotency.retention` | `24h` | how long keys and responses are kept |
| `daqiqa.ordering.idempotency.purge-interval` / `.purge-batch-size` | `1h` / `1000` | key purge |
| `daqiqa.ordering.sweeper.interval` / `.batch-size` | `30s` / `100` | abandoned-order sweeper |
| `daqiqa.ordering.payment.decline-above` | `1000.00` | simulated gateway declines amounts above this (major units) |
| `daqiqa.ordering.payment.latency` | `0s` | simulated gateway delay |

Invalid values fail at startup: a hold the inventory would refuse, a retention shorter than the lease, a lease at least
as long as the hold (every takeover would then find the hold run out), a maximum backoff shorter than the initial one,
an unknown publisher. The scheduled delays read the same properties, with the same defaults, through shared constants.

**Kafka producer settings the server must use** (`spring.kafka.*`; the Kafka publisher refuses to start without the
first four):

```yaml
spring:
  kafka:
    bootstrap-servers: ${DAQIQA_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      acks: all
      properties:
        enable.idempotence: true
        max.in.flight.requests.per.connection: 5 # at most 5 keeps idempotent retries in order
        max.block.ms: 5000                       # at most the send timeout: send() must not stall the relay longer
        linger.ms: 0                             # the relay sends one event and waits; batching delay is pure latency
        request.timeout.ms: 5000
        delivery.timeout.ms: 8000                # below daqiqa.messaging.kafka.send-timeout: a failure the relay
                                                 # records is final, no stray retry lands later
    # Never set spring.kafka.producer.transaction-id-prefix: Boot would add a KafkaTransactionManager, which displaces
    # the JDBC transaction manager the outbox and checkout rely on.
daqiqa:
  messaging:
    publisher: ${DAQIQA_EVENT_PUBLISHER:kafka} # DAQIQA_EVENT_PUBLISHER=logging for local runs without a broker
```

### Metrics

| Metric | Type, tags | Question it answers |
|---|---|---|
| `daqiqa.ordering.checkouts` | counter, `outcome` | how checkouts end: `confirmed`, `out_of_stock`, `payment_declined`, `hold_expired`, `promise_expired`, `promise_invalid`, `promise_cart_mismatch`, `promise_location_mismatch`, `store_unavailable`, `promise_already_used`, `unknown_sku`, `replayed`, `idempotency_request_in_progress`, `idempotency_key_reused`, `failed` |
| `daqiqa.ordering.checkout` | timer, `outcome` | how long each kind takes, payment included |
| `daqiqa.ordering.abandoned` | counter, `reason` | card orders abandoned, counted once committed wherever it happens: `hold_expired` (payment never completed), `paid_after_hold_expired` (an authorization to void certainly exists) |
| `daqiqa.ordering.sweeper.failures` | counter | orders the sweeper could not abandon (retried) |
| `daqiqa.outbox.backlog` | gauge | committed events not yet published: alert when it grows |
| `daqiqa.outbox.published` / `daqiqa.outbox.failures` | counters, `type` | publications and failed attempts per event type |

### How the tests prove each guarantee

| Guarantee | Test (all against real PostgreSQL unless noted) |
|---|---|
| The outbox writer refuses to run without a transaction; a rolled-back event never exists | `JdbcOutboxWriterTest` |
| The relay publishes in `seq` order and marks rows; one batch per round | `OutboxRelayTest` |
| A failure stops the round, records `attempts`/`last_error`, nothing overtakes it; the event backs off exponentially and nothing after it is published meanwhile | `OutboxRelayTest.stopsTheRoundAtTheFirstFailure...`, `waitsOutTheBackoff...`, `doublesTheBackoff...` |
| Only one relay at a time; two concurrent relays never publish a row twice, and keep the order | `OutboxRelayTest.skipsTheRound...`, `twoRelaysRunningConcurrently...` |
| At-least-once: a relay dying before commit publishes again, loses nothing | `OutboxRelayTest.anEventIsPublishedAgain...` |
| The relay's statements and the backlog gauge use the partial index | `OutboxQueryPlanTest` (`EXPLAIN`) |
| Kafka: key = store, payload, CloudEvents headers, one partition per store in order; acks awaited | `OutboxToKafkaTest` (`@EmbeddedKafka`) |
| Kafka is the default; logging only when asked; a producer that is not explicitly durable, or could stall the relay, is refused with every missing setting named; unacknowledged sends fail | `PublisherSelectionTest`, `KafkaEventPublisherTest` (no broker) |
| Retention deletes only old published events | `OutboxPurgeTest` |
| Scheduled jobs run on their configured intervals | `MessagingSchedulingTest`, `OrderingSchedulingTest` |
| Event contracts, `OrderAbandonedV1` included, round-trip and keep their topic and key | `EventContractTest` (`platform/contracts`) |
| Order state machine, cash-on-delivery rules, pricing snapshots and totals | `OrderTest`, `OrderStatusTest`, `BasketPricingTest` (unit + jqwik) |
| The idempotency decision table; the canonical request fingerprint | `IdempotencyRecordTest`, `PlaceOrderCommandTest` (unit) |
| Every checkout outcome, stored and replayed, the promise's address and store checks included; resumption after a crash; release after a failure | `CheckoutServiceTest` (fakes for promise, inventory, catalog, payment) |
| Payment outlives the hold: abandoned in T2 (reservation expired), in T3 when the inventory rolls T2 back (reservation released), by a retry in T1 without a second authorization, or by the sweeper meanwhile; each with the right `detail`, one `OrderAbandonedV1` and the counter | `CheckoutServiceTest.abandons...`, `fallsBack...`, `aRetryAfterTheHoldExpired...`, `answersHoldExpired...`, `saysThePaymentWasDeclined...`, `announcesAnAbandonedCardOrder...` |
| An attempt stalled past its lease never contradicts the attempt that took over: stalled in the payment, it replays the order the retry confirmed; stalled before T1, it replays the retry's `PROMISE_EXPIRED`; T3 respects a sweeper that abandoned the order between T2's rollback and T3 | `StalledCheckoutTest` (each interleaving held in place with gates, no sleeps) |
| **20 identical requests in parallel → one order, one reservation, one charge, one event; answers are the 201 or `IN_PROGRESS`; 20 concurrent retries all replay** | `CheckoutConcurrencyTest` (repeated 3×) |
| A promise buys one live order: 8 keys racing under one promise → one order, the others `PROMISE_ALREADY_USED` holding no stock; the loser of the insert race gives its hold back; a failed order frees the promise | `CheckoutConcurrencyTest`, `CheckoutServiceTest.refusesASecondOrder...`, `aPromiseWhoseOrderFailed...`, `JdbcOrderRepositoryTest` |
| Sweeper: abandons after the hold, releases stock, announces it, skips locked orders, isolates failures | `AbandonedOrderSweeperTest` |
| Round trips, lost-update guard, `SKIP LOCKED`, CHECK constraints, the promise index, byte-exact response storage | `JdbcOrderRepositoryTest`, `JdbcIdempotencyKeyRepositoryTest` |
| Wire contract: status codes, problem codes, `Location`, `Retry-After` (capped at 2 s), missing key → 400 | `OrderControllerTest` (`@WebMvcTest`) |
| Replays are byte-identical over HTTP; `GET` returns the `201` body; stored problems look like Spring's | `CheckoutHttpTest` |
| Configuration defaults and validation (lease shorter than the hold, among others) | `OrderingConfigurationTest`, `PublisherSelectionTest` |
| Hexagonal layers; other modules used only through their `api` | `ArchitectureTest` |

The fakes honour their contracts, which the atomicity tests depend on: `FakeInventory` joins the caller's transaction
(its changes are undone when the transaction rolls back), expires a hold on a late `confirm` without marking the caller
rollback-only, and can be told to misbehave (mark it rollback-only) to exercise T3. `Gate`s hold a call at a precise
point so that interleavings are reproduced, not hoped for.

### Next steps

- **The payments void job** consumes `OrderAbandonedV1`: add `PaymentGateway.voidAuthorization(orderId)`, call it for
  each event until it succeeds (the provider deduplicates by order id), and alert on events it cannot process. Every
  authorization is made under an order that exists, and every such order ends confirmed, declined or announced as
  abandoned, so nothing else checkout leaves behind needs voiding. The simulated gateway holds no money, so v1 is
  unaffected; a real provider must not go live without it.
- **Relay throughput**: publishing is sequential (about 1/ack-latency events per second, a few hundred per second with
  a local cluster). Next: pipeline each batch's sends and await them in order (the idempotent producer keeps
  per-partition order), or move to Debezium CDC on the same table (ADR-0009).
- **Poison events** block the relay by design (order first), now with backoff and throttled logs; still to add: alerts
  on `daqiqa.outbox.failures` and on backlog age, and an operator command to park an event.
- React to `ReservationExpiredV1` (abandon the order immediately instead of waiting for the sweeper), cancellation
  (`CANCELLED`, in the contract but not produced yet), and "my orders" listing (the index exists).
- Authentication: the customer id comes from the body until identity arrives; `GET` does not check ownership yet.

### Outside this worktree

- The coordinator adds to `api/openapi/daqiqa-v1.yaml`: `HOLD_EXPIRED`, `PROMISE_ALREADY_USED` and `STORE_UNAVAILABLE`
  under `409`, `PROMISE_LOCATION_MISMATCH` under `422`, the `Idempotent-Replayed` and `Retry-After` headers, and `400`
  on a malformed order id in `GET /v1/orders/{orderId}`.
- The coordinator adds the multi-threaded `ThreadPoolTaskScheduler` to the server (decision 19).
- `apps/daqiqa-server/src/main/resources/application.yaml` needs the Kafka settings above; since `kafka` is now the
  default publisher, the server's tests need `daqiqa.messaging.publisher=logging` or a broker.
