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
