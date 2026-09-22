# Event catalogue

Integration events are Daqiqa's published language: how one module tells the others, and later other systems, that
something happened. The schema of each event is a Java record in
[`platform/contracts`](../../platform/contracts/src/main/java/io/daqiqa/contracts/events), which uses JDK types only
so that it outlives internal refactoring.

> **Status.** Built in wave 1: every event below is published through the transactional outbox, to Kafka by default.
> No consumer exists yet; the first ones arrive with fulfilment, dispatch and the payments void job.

| Event | Type | Topic | Key | Producer |
|---|---|---|---|---|
| [`OrderPlacedV1`](#orderplacedv1) | `daqiqa.ordering.order-placed.v1` | `daqiqa.orders.v1` | store id | ordering |
| [`OrderAbandonedV1`](#orderabandonedv1) | `daqiqa.ordering.order-abandoned.v1` | `daqiqa.orders.v1` | store id | ordering |
| [`StockAdjustedV1`](#stockadjustedv1) | `daqiqa.inventory.stock-adjusted.v1` | `daqiqa.inventory.v1` | store id | inventory |
| [`ReservationExpiredV1`](#reservationexpiredv1) | `daqiqa.inventory.reservation-expired.v1` | `daqiqa.inventory.v1` | store id | inventory |

The JSON examples are illustrative; their field names and types match the records exactly.

## How an event travels

1. A module calls `OutboxWriter.append(event)` inside the transaction that changes its state, so the event and the
   change commit together or not at all. The writer refuses to run without an active transaction
   ([ADR-0009](../adr/0009-transactional-outbox-and-kafka.md)).
2. The row lands in `messaging.outbox_event`: `id` (the event id), `seq` (identity column, publication order),
   `type`, `topic`, `partition_key`, `payload` (`jsonb`), `occurred_at`, `created_at`, `published_at`, `attempts`,
   `last_error`, `next_attempt_at`.
3. Every 250 ms, one instance (the one that wins `pg_try_advisory_xact_lock` for that round) reads unpublished rows
   in `seq` order, sends them to Kafka one at a time, waiting for each acknowledgement, and marks them published. A
   failed send stops the round.
4. Consumers read the topic and deduplicate on the event id.

The sequence diagram is in the [architecture guide](overview.md#74-outbox-relay).

## Envelope

A Kafka record carries an event in three places. Metadata travels in headers that follow the CloudEvents Kafka
binding in binary mode, so a consumer can route and deduplicate without parsing the payload; the value is the JSON form
of the record and nothing else.

| Part | Content | Example |
|---|---|---|
| Key | `partitionKey()`: the store id for store-scoped facts | `DXB-MARINA` |
| Header `ce_specversion` | CloudEvents version | `1.0` |
| Header `ce_id` | `eventId()`, a UUIDv7; the deduplication key | `01a0c8fd-0a08-773f-b239-b1c5d8cba0b1` |
| Header `ce_type` | `type()`, versioned | `daqiqa.ordering.order-placed.v1` |
| Header `ce_source` | the producing application | `daqiqa-server` |
| Header `ce_time` | `occurredAt()`, when the fact happened (ISO-8601, UTC) | `2026-09-22T12:00:21.512Z` |
| Header `content-type` | media type of the value | `application/json` |
| Value | the record serialised by a dedicated Jackson 3 mapper: exactly its components, camelCase, instants as ISO-8601 UTC strings | see each event |

The event mapper is separate from the HTTP API's on purpose: the payload is a published contract and must not change
shape when someone tunes `spring.jackson.*` for the web API. `type()`, `topic()` and `partitionKey()` are methods,
not record components, so they are not repeated in the JSON body. Every event also carries `eventId` and `occurredAt`
in the body, so a consumer that stores only values keeps them.

Payloads can carry personal data: `OrderPlacedV1` includes the delivery location. The `logging` publisher (an
explicit opt-in for runs without Kafka) therefore logs envelopes only.

## Topics and partitioning

| Topic | Carries | Key | Partitions |
|---|---|---|---|
| `daqiqa.orders.v1` | `OrderPlacedV1`, `OrderAbandonedV1` (later: the rest of the order lifecycle) | store id | 12 locally (`KAFKA_NUM_PARTITIONS` in [compose](../../deploy/compose/compose.yaml)) |
| `daqiqa.inventory.v1` | `StockAdjustedV1`, `ReservationExpiredV1` | store id | 12 locally |

**Why the store id is the key.** Everything that happens after checkout happens in one store: a basket is fulfilled
from a single store, pickers work per store, and dispatch (wave 2) assigns riders per store. Keying by store gives
each store one ordered stream, lets a consumer instance own a set of stores, and spreads load well: one busy store
produces well under one event per second (see the [capacity model](quality-attributes.md#capacity-model)), far below
what a partition handles.

**Why the topic name has a version.** Changing the key would break per-key ordering for consumers in the middle of a
stream, so the topic version changes only when the partitioning key changes. New event types and new fields do not
change the topic.

## Delivery guarantees

| Guarantee | Mechanism |
|---|---|
| No lost event | The event commits with the state change. The relay marks a row published only after Kafka acknowledges it; the producer must be configured with `acks=all` and `enable.idempotence=true`, or the server refuses to start. |
| No silent drop | Kafka is the default publisher. The `logging` publisher, which marks events published without sending them, must be chosen explicitly (`DAQIQA_EVENT_PUBLISHER=logging`). |
| No phantom event | A rolled-back transaction rolls back its outbox rows, so nothing is sent for a change that never happened. |
| At-least-once, not exactly-once | If a relay dies after the broker acknowledged and before its round committed, the rows are sent again next round. Consumers deduplicate on `eventId`. |
| Order per aggregate | One relay at a time, rows sent in `seq` order, and a failed send stops the round so later events cannot overtake it. |
| Sub-second latency, typically | The relay polls every 250 ms (`daqiqa.messaging.relay.interval`). |
| A failing event backs off | Retried after 1 s, doubling up to 5 min (`next_attempt_at`); events after it wait, and failures are logged on attempts 1, 2, 4, 8, and so on. Alert on `daqiqa.outbox.failures` and the backlog gauge. |

Ordering is guaranteed for events of one aggregate because changes to one aggregate are serialised by its row lock,
provided the module appends the event after making the change, so that the event's `seq` is taken while the lock is
held. Unrelated transactions for the same store may interleave; they are not causally related, so no consumer should
rely on their relative order.

The relay selects rows with `published_at IS NULL` in `seq` order, never "rows after the last published `seq`".
`seq` is assigned at insert time, not at commit time: a transaction that took `seq` 10 can commit after the one that
took `seq` 11. A high-water-mark cursor would skip row 10 forever; selecting by `published_at` picks it up on the
next round. Every relay statement repeats the partial index's predicate (`published_at IS NULL`), which keeps marking
a row published at 0.6 ms instead of 30 ms with 300,000 retained rows.

The idempotent producer removes duplicates caused by producer retries inside one send. It does not remove
duplicates caused by a relay that dies before committing; the `eventId` does.

## Rules for consumers

No consumer exists yet. These rules apply to the first ones and are the reason the events look the way they do.

1. **Deduplicate on `eventId`.** Record processed ids in the consumer's own schema in the same transaction as the
   effect, for example `INSERT ... ON CONFLICT (event_id) DO NOTHING` and skip the effect when no row was inserted.
   Alternatively make the effect naturally idempotent (an upsert keyed by `orderId`).
2. **Commit offsets after the effect commits.** A crash in between replays the event, which rule 1 absorbs.
3. **Dispatch on `ce_type`.** Each topic carries two event types today and will carry more. Skip types you do not
   handle.
4. **Ignore unknown fields.** Events evolve by adding fields.
5. **Rely only on per-store order.** There is no order across stores or across topics.

## Versioning rules

These rules are in the contracts' [`package-info.java`](../../platform/contracts/src/main/java/io/daqiqa/contracts/events/package-info.java);
`EventContractTest` fails the build when the checkable ones are broken.

| Rule | Checked by |
|---|---|
| An event is a record of something that happened (past tense), never a command. | review |
| Components use JDK types only: no shared-kernel types, so internal refactoring cannot change the contract. | `EventContractTest` |
| `TYPE` matches `daqiqa.<context>.<event-name>.v<N>` and the class name ends with the same `V<N>`. | `EventContractTest` |
| Changes are additive: new fields only, never a removed, renamed or retyped field. | review |
| A breaking change is a new type (`...v2`, `SomethingHappenedV2`) published side by side with `v1` until every consumer has moved. | review |
| The topic version changes only if the partitioning key changes. | review |

## OrderPlacedV1

An order was confirmed and must now be picked at `storeId` and delivered by `promisedBy`.

| | |
|---|---|
| Type | `daqiqa.ordering.order-placed.v1` |
| Topic, key | `daqiqa.orders.v1`, `storeId` |
| Producer | ordering, in the transaction that confirms the order: T2 after an approved card payment, T1 for cash on delivery ([ordering.md](../design/ordering.md)) |
| Consumers | None yet. Planned: fulfilment (picking queue) and dispatch (rider assignment) in wave 2; live tracking in wave 2; promise accuracy in wave 3 |

**Why it carries the promise.** `promiseId`, `deliveryTier`, `promisedEtaMinutes` and `promisedBy` travel with the
order, so every downstream consumer can measure the delivery against what the customer was shown without calling
back into ordering. This is what makes the wave 3 accuracy loop a pure consumer.

| Field | Type | Meaning |
|---|---|---|
| `eventId` | UUID | Unique id of this occurrence (UUIDv7); consumers deduplicate on it |
| `occurredAt` | instant | When the order was confirmed |
| `orderId` | UUID | The order |
| `storeId` | string | Store that fulfils the order; also the partition key |
| `customerId` | UUID | Customer, owned by an upstream identity system |
| `promiseId` | UUID | The promise the order was placed under |
| `deliveryTier` | string | `EXPRESS_15`, `STANDARD_30` or `EXTENDED_60` |
| `promisedEtaMinutes` | int | ETA shown to the customer |
| `promisedBy` | instant | `placedAt + promisedEtaMinutes`: the deadline the delivery is measured against |
| `deliveryLatitude`, `deliveryLongitude` | double | Delivery location (WGS84); personal data |
| `lines` | list of `{sku, quantity}` | What to pick, merged by SKU |
| `totalMinor` | long | Order total in minor units (fils for AED) |
| `currency` | string | ISO 4217 code |

```json
{
  "eventId": "01a0c8fd-0a08-773f-b239-b1c5d8cba0b1",
  "occurredAt": "2026-09-22T12:00:21.512Z",
  "orderId": "01a0c8fd-093b-7eeb-be96-e59ce40f9cb1",
  "storeId": "DXB-MARINA",
  "customerId": "019ceb9e-93a9-76c4-9863-875e34487ca3",
  "promiseId": "01a0c8fc-b600-7513-9945-2af7c1768eeb",
  "deliveryTier": "EXPRESS_15",
  "promisedEtaMinutes": 11,
  "promisedBy": "2026-09-22T12:11:21.307Z",
  "deliveryLatitude": 25.0772,
  "deliveryLongitude": 55.139,
  "lines": [
    { "sku": "MILK-FULL-1L", "quantity": 2 },
    { "sku": "BREAD-ARABIC-6PK", "quantity": 1 }
  ],
  "totalMinor": 1690,
  "currency": "AED"
}
```

The order was placed at 12:00:21.307 under an 11-minute promise, so `promisedBy` is 12:11:21.307. The card payment
was approved and T2 committed 205 ms later, which is `occurredAt`. The total is 2 × 6.95 + 1 × 3.00 = 16.90 AED.
`CheckoutEndToEndTest` checks that one checkout, retried, leaves exactly one `OrderPlacedV1` in the outbox.

## OrderAbandonedV1

An order was abandoned: its card payment did not complete while its stock was held, so the stock went back on sale
and nothing will be picked or delivered.

| | |
|---|---|
| Type | `daqiqa.ordering.order-abandoned.v1` |
| Topic, key | `daqiqa.orders.v1`, `storeId` |
| Producer | ordering, on every abandonment, in the transaction that abandons the order and releases its reservation. One code path serves all four places an order can be abandoned: the abandoned-order sweeper, a retry that finds the hold run out (T1), a confirmation refused because the hold expired (T2), and its fallback (T3) ([ordering.md](../design/ordering.md)) |
| Consumers | None yet. Planned: the payments void job, which releases any authorization under the order id ([known gaps](../roadmap.md#known-gaps-not-yet-scheduled)) |

**Why it is a to-do, not only a fact.** The payment provider may hold an authorization under the order id (its
idempotency key) that nothing will capture. Checkout cannot tell a declined or never-started payment from an approval
whose confirmation was lost in a crash, so every abandoned card order says an authorization may exist. The void job
must treat an order without one as nothing to void.

| Field | Type | Meaning |
|---|---|---|
| `eventId` | UUID | Unique id of this occurrence (UUIDv7) |
| `occurredAt` | instant | When the order was abandoned |
| `orderId` | UUID | The order, and the payment provider's idempotency key |
| `storeId` | string | Store whose stock was released; also the partition key |
| `customerId` | UUID | Customer |
| `paymentMethod` | string | `CARD` or `CASH_ON_DELIVERY`. Only card orders wait for a payment, so only card orders are abandoned |
| `reason` | string | `HOLD_EXPIRED`: the payment never completed within the hold. `PAID_AFTER_HOLD_EXPIRED`: the payment was approved only after the hold expired, so an authorization certainly exists |
| `paymentAuthorizationMayExist` | boolean | `true` for every abandoned card order |
| `totalMinor` | long | Order total in minor units |
| `currency` | string | ISO 4217 code |

```json
{
  "eventId": "01a0c91d-051a-79e0-afc0-534d185508d0",
  "occurredAt": "2026-09-22T12:35:17.402Z",
  "orderId": "01a0c918-3582-7c13-835c-a9e2dc5603e0",
  "storeId": "DXB-MARINA",
  "customerId": "019ceb9e-93a9-76c4-9863-875e34487ca3",
  "paymentMethod": "CARD",
  "reason": "HOLD_EXPIRED",
  "paymentAuthorizationMayExist": true,
  "totalMinor": 3300,
  "currency": "AED"
}
```

The same order appears in the [`ReservationExpiredV1`](#reservationexpiredv1) example: placed at 12:30:02.114 with the
default 5-minute hold, its payment never completed. Inventory's sweeper expired the hold at 12:35:04.870; ordering's
sweeper, which runs every 30 seconds, abandoned the order at 12:35:17.402. The total is 1 × 18.00 + 6 × 2.50 = 33.00
AED.

## StockAdjustedV1

The on-hand quantity of a SKU at a store changed outside the order flow: a delivery was received, stock was damaged
or expired, or a count corrected it.

| | |
|---|---|
| Type | `daqiqa.inventory.stock-adjusted.v1` |
| Topic, key | `daqiqa.inventory.v1`, `storeId` |
| Producer | inventory, in the transaction that applies `POST /v1/stores/{storeId}/stock/adjustments` and writes the audit row ([inventory.md](../design/inventory.md)) |
| Consumers | None yet. Candidates: the ops console's live stock view (wave 4), replenishment and shrinkage analytics |

| Field | Type | Meaning |
|---|---|---|
| `eventId` | UUID | Unique id of this occurrence (UUIDv7); the same id as the adjustment's audit row: one fact, one id |
| `occurredAt` | instant | When the adjustment was recorded |
| `storeId` | string | Store; also the partition key |
| `sku` | string | Product |
| `delta` | int | Signed change to on-hand units: positive adds, negative removes; never 0 |
| `reason` | string | `RECEIVED`, `DAMAGED`, `EXPIRED`, `CYCLE_COUNT` or `CORRECTION` |
| `onHandAfter` | int | Units on hand after the change |
| `reservedAfter` | int | Units reserved for orders at that moment; never more than `onHandAfter` |

```json
{
  "eventId": "01a0c7a2-cbc6-796a-8ee8-155565a3e33b",
  "occurredAt": "2026-09-22T05:42:10.118Z",
  "storeId": "DXB-MARINA",
  "sku": "MILK-FULL-1L",
  "delta": 48,
  "reason": "RECEIVED",
  "onHandAfter": 60,
  "reservedAfter": 3
}
```

Carrying both `onHandAfter` and `reservedAfter` lets a consumer rebuild the sellable quantity (57 here) without
replaying history or calling inventory. An adjustment that would leave fewer units on hand than are reserved is
refused with `409 STOCK_BELOW_RESERVED`, so no event ever shows `reservedAfter > onHandAfter`. Adjustments are not
idempotent yet: a retried request is a second adjustment and a second event (a [known gap](../roadmap.md#known-gaps-not-yet-scheduled)).

## ReservationExpiredV1

Stock held for `reference` (usually an order id) went back on sale because the hold timed out before the order was
confirmed.

| | |
|---|---|
| Type | `daqiqa.inventory.reservation-expired.v1` |
| Topic, key | `daqiqa.inventory.v1`, `storeId` |
| Producer | inventory, whenever a hold expires: the expiry sweeper, a `confirm` that arrives after `expiresAt` and expires the hold instead of confirming it, or a retried `reserve` that finds its reference's hold run out ([inventory.md](../design/inventory.md)) |
| Consumers | None yet. Ordering does not need it to stay consistent (its own sweeper abandons orders whose hold has run out); it is published for analytics and for services outside the monolith |

Releases (payment declined, order abandoned) are not announced by inventory. When an order is abandoned, ordering
announces it with `OrderAbandonedV1`.

| Field | Type | Meaning |
|---|---|---|
| `eventId` | UUID | Unique id of this occurrence (UUIDv7) |
| `occurredAt` | instant | When the hold was expired |
| `reservationId` | UUID | The reservation |
| `storeId` | string | Store; also the partition key |
| `reference` | string | Caller's reference, for ordering the order id |
| `lines` | list of `{sku, quantity}` | Units returned to sale |

```json
{
  "eventId": "01a0c91c-d426-743e-9b89-821647539500",
  "occurredAt": "2026-09-22T12:35:04.870Z",
  "reservationId": "01a0c918-3584-7487-813b-ea243bd1188e",
  "storeId": "DXB-MARINA",
  "reference": "01a0c918-3582-7c13-835c-a9e2dc5603e0",
  "lines": [
    { "sku": "ICECREAM-VANILLA-1L", "quantity": 1 },
    { "sku": "WATER-1-5L", "quantity": 6 }
  ]
}
```

The order was placed at 12:30:02.114 with the default 5-minute hold, so the hold expired at 12:35:02.114. The sweeper
runs every 5 seconds and released it at 12:35:04.870.

## Adding an event

1. Ask first ([AGENTS.md](../../AGENTS.md)): events are contracts, so changes to `platform/contracts` are reviewed
   before they are built.
2. Add a record `SomethingHappenedV1` implementing `IntegrationEvent` in `platform/contracts`, with a `TYPE`
   constant, a topic from `Topics` and a partition key (the store id for store-scoped facts), and add it to
   `EventContractTest`.
3. Publish it only through `OutboxWriter`, inside the transaction that makes the change and after the change
   itself (see [delivery guarantees](#delivery-guarantees)).
4. Add it to this catalogue with a JSON example that matches the record exactly.
