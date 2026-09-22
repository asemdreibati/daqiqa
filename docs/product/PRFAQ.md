# Daqiqa: PR/FAQ

> **About this document.** This is a working-backwards planning document in the PR/FAQ format. The press release
> describes Daqiqa as if it had launched with the whole [roadmap](../roadmap.md) delivered; the FAQ answers the
> questions that decide whether it should be built this way. As is customary for the format, the launch date is
> hypothetical, the quotes are illustrative and the people quoted are fictional personas. What exists today is
> listed in the [roadmap](../roadmap.md). Daqiqa is an independent portfolio project, not affiliated with noon or
> Amazon.

## Press release

### Daqiqa gives quick-commerce shoppers a delivery time calculated for their basket, then keeps it

*Dark-store operators can now show every shopper an honest delivery time for their exact basket and address, hold
the exact items so they can never be oversold, and turn each promise into exactly one order.*

**DUBAI — 1 March 2027 —** Daqiqa (دقيقة, Arabic for "minute"), a delivery-promise engine for quick-commerce
operators, launched today. For every basket and address, Daqiqa calculates in milliseconds how fast the order can
really arrive, and then makes sure the operator keeps its word.

Quick commerce in the Gulf is a race measured in minutes. noon runs Noon Minutes from dark stores, and Amazon launched
15-minute delivery in the UAE in October 2025 from micro-fulfilment centres inside neighbourhoods. The number on the
button is the product. Yet the time a basket really takes is not a constant: it depends on how many orders are ahead
of it in the store, how many items the picker must collect, whether a rider is free, and how far the address is. One
fixed "15 minutes" is late when the store is busy and leaves speed unsold when it is quiet. At peak, several shoppers
reach for the last carton of the same milk at the same moment, and one of them may pay for an item that is no longer
there. And mobile networks drop responses: a shopper who taps "Order" again can end up with two orders and two
charges. For the operator, every broken promise costs twice: a refund now, and a customer who trusts the next promise
less.

Daqiqa is the engine behind the button. It finds the dark stores that cover the address, keeps those that can supply
the whole basket, and adds up where the minutes go: waiting for a picker, picking, waiting for a rider, the ride, and a
safety buffer. It offers the fastest tier it can keep (15, 30 or 60 minutes) and shows the breakdown. When the
shopper taps "Order", Daqiqa holds the exact units in the chosen store so that no two shoppers can buy the same last
one, confirms the order exactly once however many times the app retries, and gives pickers and riders the minute the
order must arrive by. Every delivery is then measured against its promise, and each store's safety buffer learns from
the late ones.

**How it works for a shopper**

1. **See an honest time for this basket.** As the basket changes, the app shows the delivery time and where it comes
   from. When one item is only in stock at a store further away, it says so: "remove the ice cream and get it in 12
   minutes instead of 24".
2. **Tap Order once.** The items are held in the store while payment completes. If the connection drops and the app
   retries, the shopper still gets one order and one charge. If an item sold out in the meantime, the shopper learns
   which one before paying.
3. **Get it by the promised minute.** The order carries its deadline ("by 20:41") to the picker, the rider and the
   live tracking screen.

"Our Marina store used to promise 15 minutes all evening and miss it whenever the dinner rush arrived," said Layla
Haddad, head of operations at a Dubai grocery chain *(illustrative quote; fictional persona)*. "Now it promises 15
minutes when it can keep it and about 20 when it is busy, and it keeps both. When the store is quiet, customers two
kilometres further out get 15 minutes too. We used to leave that on the table."

"It was 11 at night and we were out of baby formula," said Omar, a parent in Jumeirah Lake Towers *(illustrative
quote; fictional persona)*. "The app said 14 minutes, not 10, and I believed it. The rider rang the bell at minute 12.
I had pressed Order twice in the lift, and I paid once."

Operators can watch Daqiqa run a simulated day in Dubai or Riyadh: start the city simulator, pause a store at the
evening peak or send half the riders home, and see the promises change while every order is still measured against
its own. Engineers can run it locally with two commands from the [README](../../README.md) and read how it works in
the [architecture guide](../architecture/overview.md).

## Tenets

Tenets decide what to do when two good options conflict. Unless we learn better ones:

1. **A promise is a contract.** The time a shopper sees is signed, verified at checkout, carried by the order to the
   door and measured against the delivery.
2. **Never oversell.** A unit is sold once. The database refuses anything else, under any concurrency, even if our
   code is wrong.
3. **Fast on the read path, strict on the write path.** Promises come from memory and one indexed read; orders take
   locks and transactions. The hot path never pays for a guarantee it does not give.
4. **Degrade gracefully and visibly.** A busy store gives longer promises instead of broken ones, a sold-out item is
   named, and a Kafka outage delays events without stopping orders.
5. **Measure what the customer feels.** Our first number is the share of orders delivered by the promised minute, not
   server latency.
6. **Boring technology, sharp design.** PostgreSQL, Kafka and plain SQL. The ideas are in the design: ordered locks,
   signed tokens, an outbox.

## Customer FAQ

### How is my delivery time calculated?

From five parts, for your exact basket and address: the wait for a picker, the picking itself (more items take
longer), the wait for a free rider, the ride, and a safety buffer learned from how late past deliveries were. The app
can show this breakdown. You are offered the fastest of three tiers that we can keep: 15, 30 or 60 minutes.

### Why is it 15 minutes now and 20 this evening?

Because the store is busier. With the default settings, a store working normally delivers a small basket within 15
minutes up to about 3 km away. When it is busy, the waits for pickers and riders grow, and 15 minutes is honest only
within about 1 km. We would rather show 20 minutes and keep it than show 15 and be late.

### What happens if an item sells out after I see the time?

The time is an offer; your items are guaranteed once you tap "Order". If the last unit went to someone else in the
meantime, you are told which item is missing and how many are left before any payment is taken, and you can get a new
time for the rest of your basket. You are never charged for an item that is not held for you.

### My connection dropped and I tapped Order again. Will I pay twice?

No. The app sends the same request key with every retry of one checkout. Daqiqa processes the first and answers every
retry with the same result: one order, one hold on the items, one payment.

### Why does a delivery time expire after 90 seconds?

Stock and store load change minute by minute, so a time calculated two minutes ago may no longer be true. A fresh one
takes milliseconds, and the app can refresh it without asking you.

### For operators: what happens when we pause a store or mark it busy?

Pausing or closing a store removes it from new promises immediately; nearby stores take over the addresses they cover.
Marking it busy keeps it selling but lengthens its promises (by default 4 more minutes waiting for a picker and 2 more
for a rider), so it stops promising what it cannot keep. Orders already placed are not affected.

## Internal FAQ

### 1. Why not a fixed 15-minute promise?

Because delivery time depends on the store's load, the basket and the distance, and a constant is wrong in both
directions. With the v1 model's defaults (picker queue 60 s when open, 300 s when busy; picking 45 s plus 8 s per
unit; rider wait 60 s or 180 s; travel at 7 m/s with a 1.3 road factor plus a 60 s handoff; a 90 s buffer), a 3-item
basket from an open store makes 15 minutes up to 3.0 km away. From a busy store, only up to 1.1 km. A fixed "15
minutes" over a 3 km radius would be late for about 87% of that area whenever the store is busy; a fixed "30 minutes"
gives speed away whenever it is quiet. Daqiqa computes the time per basket and address and snaps it to three tiers
(`EXPRESS_15`, `STANDARD_30`, `EXTENDED_60`) so shoppers see familiar choices. Past 60 minutes it answers
`NO_CAPACITY` rather than make a promise it cannot keep. The model and its parameters are in
[promise.md](../design/promise.md).

### 2. How is overselling prevented under concurrency?

By making the database the referee ([ADR-0005](../adr/0005-never-oversell-inventory-reservations.md)):

- Stock is one row per `(store, SKU)` with `CHECK (reserved <= on_hand)`. That constraint refuses an oversell even if
  the application code is wrong.
- A basket is reserved in one transaction that locks its rows with `SELECT ... ORDER BY sku FOR UPDATE`. Every basket
  locks in SKU order, so two baskets sharing items queue behind each other instead of deadlocking. Shortages are
  computed on the locked rows; any shortage means nothing is written.
- Holds expire (5 minutes by default) and are swept with `FOR UPDATE SKIP LOCKED`; a confirmation that arrives after
  expiry expires the hold instead of confirming it.
- A reservation is idempotent by its order reference, enforced by a partial unique index.
- The proof is a test, not an argument: 200 threads race for 50 units on real PostgreSQL, and exactly 50 win.

Rejected: optimistic locking (retry storms on hot items), serializable isolation (every caller must handle
serialization failures), Redis as the source of truth (loses the atomic commit with the order).

### 3. Why a modular monolith?

Because checkout must hold stock and create the order atomically. In one process that is a local transaction; across
services it is a saga, before there is any evidence that inventory and ordering need to scale or deploy
independently (see question 10). The risk of a monolith is erosion, so the boundaries are enforced: one Gradle module
and one PostgreSQL schema per context, access to another module only through its `api` package (ArchUnit checks it on
every build), and cross-module facts as events through the outbox, as between services. A module leaves when a
measurement says so; rider-location ingestion in wave 2 is the likely first
([ADR-0002](../adr/0002-modular-monolith-first.md), [evolution path](../architecture/overview.md#10-evolution-path)).

### 4. Why H3?

Coverage ("which stores can serve this point?") is on the hot path of every promise, so it must be answered from
memory in constant time. H3 at resolution 9 tiles the city with hexagons of about 0.1 km². Each store's service area
is precomputed as the cells within its radius plus one cell's circumradius: 358 cells for a 3 km store, measured in
Dubai. An immutable map from cell to stores answers the question with one lookup and no lock. The margin means no
customer inside a radius is missed; exact distances, computed for the few candidates only, mean no point outside it
is ever served. Hexagons have uniform neighbour distances, which also suits the demand and rider heatmaps planned later. PostGIS
would put a database round trip on the hot path; geohash cells are rectangles with uneven neighbours; per-store
polygons are a future extension that H3 absorbs, because cells can be generated from polygons
([ADR-0006](../adr/0006-h3-for-coverage.md)).

### 5. Why signed tokens instead of storing promises?

Most promises are never ordered. At the capacity model's assumption of 20 promise requests per order, storing them
would add 20 writes per order to the hottest path, plus a lookup at checkout. Instead, each offer carries a compact
token signed with HMAC-SHA256: promise id, store, tier, ETA, issue and expiry times, a SHA-256 hash of the basket, and
the location the promise was computed for. Checkout verifies it without I/O: the signature (compared in constant
time), the expiry (90 s, against the injected clock), the basket hash, a delivery point within 100 m of the promised
location, and, from the in-memory network view, a store that still accepts orders. Changing the ETA breaks the
signature; reusing the token for another basket or another address fails its check. Tokens name their signing key
(`kid`), so keys rotate without downtime. The trade-off is that a single token cannot be revoked: within its 90
seconds it verifies wherever it is presented. Two rules bound that. A paused store's tokens are refused. And a promise
converts to at most one live order: a partial unique index guarantees it, and a per-promise lock makes a racing second
checkout hear `PROMISE_ALREADY_USED`. A small format that is fully under test was preferred to a JWT library and its
algorithm-negotiation pitfalls ([ADR-0007](../adr/0007-signed-promise-tokens.md)).

### 6. How are retries made safe?

At every layer that can be retried ([ADR-0008](../adr/0008-idempotency-keys.md)):

| Layer | Mechanism |
|---|---|
| HTTP | `Idempotency-Key`, scoped to the customer and kept 24 hours. The first request claims it with a 60 s lease; completion stores the status and body; a retry gets the stored response; the same key with a different body gets `422 IDEMPOTENCY_KEY_REUSED`; a concurrent duplicate gets `409 IDEMPOTENCY_REQUEST_IN_PROGRESS` with `Retry-After` |
| Inventory | A reservation is idempotent by its order reference (partial unique index) |
| Payment | The order id is the provider's idempotency key |
| Kafka producer | `enable.idempotence=true`, `acks=all` |
| Consumers | Deduplicate on `eventId` |
| Background jobs | `SKIP LOCKED` batches and an advisory lock, so two instances never process the same row |

The proof: 20 identical checkouts in parallel with one key create exactly one order and one reservation.

### 7. How do events reach Kafka without dual writes?

Writing to PostgreSQL and then to Kafka loses the event if the process dies in between; writing to Kafka first can
announce an order that then rolls back. So a module appends its event to `messaging.outbox_event` in the same
transaction as the state change, and a relay publishes committed rows every 250 ms, in `seq` order, keyed by store id.
One instance relays per round: whichever wins `pg_try_advisory_xact_lock`, which is leader election without extra
infrastructure. A failed send stops the batch, so later events for a store cannot overtake it. Delivery is
at-least-once and consumers deduplicate on `eventId`. A useful side effect: Kafka is off the request path, so a Kafka
outage delays events but does not stop checkout. Debezium CDC on the same table is the upgrade path
([ADR-0009](../adr/0009-transactional-outbox-and-kafka.md), [event catalogue](../architecture/events.md)).

### 8. How will promise accuracy be measured and calibrated?

Planned for waves 2 and 3. Every `OrderPlacedV1` already carries its promise: tier, ETA and `promisedBy`. In wave 2,
fulfilment and dispatch record when each stage actually happened (picked, handed to a rider, delivered). The accuracy
loop then measures:

- **on-time rate**: the share of orders delivered at or before `promisedBy`, per store, tier and hour, against a
  target of at least 95% in simulation;
- **lateness by component**: each stage against its estimate, so a late order is attributed to the queue, picking,
  the rider wait or the ride;
- **earliness**: orders that arrive far earlier than promised mean the buffer is too large and the shopper was shown
  a slower time than necessary.

The fixed 90-second buffer then becomes a per-store value learned from recent deliveries, for example the smallest
margin that would have made 95% of the store's recent orders on time, with bounds so that it cannot swing wildly. The
city simulator (Dubai and Riyadh presets, demand curves, an accelerated clock) makes all of this measurable before
any real traffic exists.

### 9. What are the SLO targets?

| Target | Value |
|---|---|
| Promise latency | p99 < 25 ms at 1,000 requests/s per instance |
| Checkout latency | p99 < 150 ms, excluding the payment provider |
| Oversold units | 0 under any concurrency: an invariant, not a percentile |
| Lost acknowledged orders | 0 |
| Retry safety | every call that creates a business fact is retry-safe |
| On-time rate | ≥ 95% in simulation |
| Availability | 99.9% (design target) |

Each target, its evidence and its status are in [quality attributes](../architecture/quality-attributes.md). Load
tests and chaos drills that measure them are wave 3.

### 10. How does it scale to many cities?

- **Stateless instances.** Add replicas behind a load balancer; state lives in PostgreSQL and Kafka.
- **Everything is store-scoped.** A basket ships from one store, so no transaction spans two stores, Kafka is keyed
  by store, and a city is a natural shard.
- **The numbers are small.** Under the [capacity model's](../architecture/quality-attributes.md#capacity-model)
  assumptions, a busy city at 3,000 orders per hour averages 0.83 orders per second and peaks near 4 (×5); at 20
  promise requests per order, promises peak at 83 per second. Ten such cities peak at about 42 orders and 830 promise
  requests per second, within one instance's promise target. Replicas are there for availability, not load.
- **Next steps, in order of need**: dedicated promise replicas reading a database replica or a read model; dispatch
  as its own service for rider location updates; independent cells per region, each with its own database and Kafka.

### 11. What is out of scope for v1?

- Authentication and authorisation: `customerId` comes from the request body until OAuth2 with Keycloak (wave 4).
- Real payment providers: a simulated gateway sits behind the `PaymentGateway` port.
- Baskets split across stores: a basket ships from one store.
- Scheduled delivery slots, substitutions, promotions, returns and refunds.
- Order cancellation: the contract lists a `CANCELLED` status, but no cancellation flow is specified yet.
- Traffic-aware travel times and polygon delivery zones.
- Instant store-status propagation between replicas: a change reaches other replicas within 30 s today, and the
  planned `StoreStatusChangedV1` event makes it immediate.
- Fulfilment, dispatch, tracking, search and the ops console, which are waves 2 to 4.

### 12. What are the biggest risks, and how are they mitigated?

| Risk | Why it matters | Mitigation |
|---|---|---|
| The v1 ETA model is simple: straight-line distance, fixed speeds, fixed waits | Late deliveries break the promise, and the promise is the product | A safety buffer now; per-store calibration from the accuracy loop (wave 3); a breakdown that shows which component was wrong |
| Promises read availability without locks | A shopper can be promised an item that sells out before checkout | Checkout re-checks under lock and answers `409 OUT_OF_STOCK` naming what is missing; the rate is a metric (`daqiqa.ordering.checkouts` with `outcome=out_of_stock`) |
| A flash sale concentrates demand on one SKU | Reservations for that SKU queue on one row | Locks are held only for a short transaction (at an assumed 5 ms, 200 reservations per second per row); a Redis pre-reservation layer in front of the same guarantee is the next step |
| One failing event blocks the outbox | The relay stops at the first failed send, so one bad event delays all later ones | The event backs off (1 s doubling to 5 min) with throttled logs; `attempts`, `last_error`, the failures counter and the backlog gauge make it visible within seconds; parking an event is the next step if it happens in practice |
| Store status reaches other replicas only at their next 30 s refresh | A paused store can keep receiving promises, and honouring its tokens, on other replicas for up to 30 s | The planned `StoreStatusChangedV1` event |
| PostgreSQL is on every request path | Its outage is an outage | A managed, highly available PostgreSQL in the production deployment (wave 4). Kafka is already off the request path |
| Many modules built in parallel by a small team | Boundaries erode and migrations collide | Contracts first, ArchUnit rules, one schema and one Flyway major version per module, CI on every change |
