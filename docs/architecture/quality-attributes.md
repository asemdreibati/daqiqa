# Quality attributes

What Daqiqa must guarantee, how fast it must be, and how each claim is checked. A target without a way to verify it
is a wish, so every row names its evidence and its status.

**Status words.** *Built*: merged with tests. *Measured*: backed by a published measurement. *Planned*: a later
wave. *Design target*: a goal the architecture is shaped for, not yet measured.

## Targets

| Attribute | Target | How it is verified | Status |
|---|---|---|---|
| Promise latency | p99 < 25 ms at 1,000 requests/s per instance | Timer `daqiqa.promise.requests` (percentile histogram with a 25 ms bucket). [Baseline](../benchmarks/2026-09-22-promise-baseline.md): p99 ≤ 14 ms at 2,370 requests/s, on a 2-vCPU sandbox shared with the load generator and PostgreSQL. k6 on dedicated hardware in wave 3 | Measured: met in the baseline |
| Checkout latency | p99 < 150 ms, excluding the payment provider | Timer `daqiqa.ordering.checkout` (tag `outcome`); k6 with the simulated gateway's latency set to zero | Metric built; not measured yet (wave 3) |
| Oversell | 0 units, under any concurrency. A hard invariant, not a percentile | `CHECK (reserved <= on_hand)` in the database; 7 concurrency tests on real PostgreSQL (below); chaos drills in wave 3 | Built |
| Durability | No acknowledged order is lost | The response is rendered and stored in the transaction that settles the order, and sent only after it commits; `CheckoutEndToEndTest`; chaos drill that kills an instance mid-checkout (wave 3) | Built; drill in wave 3 |
| Retry safety | Every call that creates a business fact is safe to retry | `Idempotency-Key` on `POST /v1/orders`; byte-exact replays marked `Idempotent-Replayed: true`; `CheckoutConcurrencyTest` | Built |
| One live order per promise | A promise converts to at most one live order | Partial unique index on live orders' `promise_id`, plus a per-promise advisory lock; `CheckoutConcurrencyTest` | Built |
| Bounded lock waits | No request waits more than 3 s for a row lock; background jobs never wait | `lock_timeout = '3s'` on every connection; `SKIP LOCKED` and try-locks in jobs; inventory persistence tests prove its locks with `lock_timeout` and `pg_stat_activity` | Built |
| Event delivery | At-least-once, in order per store, sub-second in the normal case | `OutboxRelayTest` (order, stop on failure, backoff, two relays, crash before commit); `OutboxToKafkaTest` with `@EmbeddedKafka` | Built |
| Job isolation | A relay stuck on an unreachable broker cannot stop holds from expiring | Scheduled jobs run on a pool of 4 platform threads ([ADR-0014](../adr/0014-background-jobs-on-a-platform-thread-scheduler.md)) | Built (configuration) |
| Promise accuracy | On-time rate ≥ 95% (delivered at or before `deliverBy`) | City simulator (wave 2) plus the accuracy loop (wave 3), per store, tier and hour | Planned |
| Availability | 99.9% for the HTTP API: at most 43 minutes of unavailability per 30 days | Stateless replicas, readiness probes, graceful shutdown; chaos drills | Design target |
| Security | Promises cannot be forged, altered, moved to another address or used after their store stopped taking orders; signing keys rotate without downtime | Token tests for every failure reason, rotation and fuzzed input; `CheckoutEndToEndTest` over HTTP (tampered, moved, store paused) | Built; OAuth2 in wave 4 |
| Observability | RED metrics per endpoint, business metrics per module, structured logs, health probes | `/actuator/prometheus`, `/actuator/health/liveness` and `/actuator/health/readiness`; Grafana dashboards; OpenTelemetry traces | Metrics and probes built; dashboards and tracing in wave 3 |
| Modularity | No module reaches into another's internals; the domain is framework-free | ArchUnit rules on every build ([ADR-0003](../adr/0003-hexagonal-modules-enforced-by-tests.md)) | Built |
| Determinism | Time-dependent behaviour is testable without sleeping | One injected `Clock` ([ADR-0011](../adr/0011-injected-clock.md)); fixed or advanced clocks in tests; gates instead of sleeps for interleavings | Built |

## The invariants, and the tests that defend them

Latency targets are percentiles. These are not: one violation is a defect. The concurrency and persistence tests
below run against real PostgreSQL; the property tests exercise the domain model directly.

| Invariant | Defence in the design | Tests |
|---|---|---|
| A store never has more units reserved than on hand | Basket rows locked in (store, SKU) order with `FOR UPDATE`, shortfalls computed on the locked rows; a guarded increment with an asserted row count; `CHECK (reserved <= on_hand)` as the last line | Inventory concurrency: 200 threads each reserve 1 of 50 units → exactly 50 held, 150 rejected, no exceptions; a mix of every operation → the books balance. Property test: random operation sequences keep `0 ≤ reserved ≤ onHand` |
| Concurrent baskets never deadlock | One lock order for the whole module; expiry batches return their stock in one sorted pass | Inventory concurrency: baskets in opposite SKU orders → no deadlock, reserved equals the successful baskets |
| Held stock returns to sale exactly once | Every status change guarded in SQL (`AND status = <expected>`, asserted row count); sweeper batches with `FOR UPDATE SKIP LOCKED`; a late `confirm` expires the hold instead of confirming it | Inventory concurrency: 6 sweepers expire each of 300 holds once; retries of run-out holds racing checkouts and sweepers expire each once. Persistence: the SQL refuses a double release and a re-expiry |
| One key, one order | Key claimed in its own transaction with a lease; the order id fixed at the first claim; every checkout transaction locks the key row first | `CheckoutConcurrencyTest` (repeated 3 times): 20 identical requests → one order, one reservation, one charge, one event, every answer the `201` or `IDEMPOTENCY_REQUEST_IN_PROGRESS`; 20 concurrent retries all replay. `StalledCheckoutTest`: an attempt stalled past its lease never contradicts the one that took over |
| One promise, one live order | Partial unique index on `promise_id` for live orders; a per-promise advisory lock before any stock is held | `CheckoutConcurrencyTest`: 8 keys racing under one promise → one order, the others `PROMISE_ALREADY_USED` holding no stock |
| No event lost, none published twice by the relay | Advisory lock per round; rows marked published only after Kafka acknowledges | `OutboxRelayTest`: two relays in parallel never publish a row twice and keep the order; a relay dying before commit publishes again. `CheckoutEndToEndTest`: a retried checkout leaves exactly one `OrderPlacedV1` |

## Server-level invariants

The tests above defend each invariant where it lives, with the neighbouring modules faked. These defend the same
invariants through the **whole assembled server**: every module real, requests over HTTP, real PostgreSQL with the
Dubai demo data, and the sweepers and the outbox relay running on the server's scheduler
(`apps/daqiqa-server/src/test/java/io/daqiqa/server`). Every scenario fires its requests through a start gate so that
they really collide, and after each one the same check runs over the whole database (`ServerInvariants`), reading one
snapshot so that a job committing meanwhile cannot make balanced books look unbalanced:

| Checked after every scenario | What it means |
|---|---|
| The stock ledger balances | For every (store, SKU), `reserved` equals the units of its `HELD` and `CONFIRMED` reservation lines, and `0 ≤ reserved ≤ on_hand` |
| Orders and holds pair up | Every live order (`AWAITING_PAYMENT`, `CONFIRMED`) has exactly one active reservation — its own, at its store, confirmed exactly when the order is — and every active reservation belongs to a live order |
| Every outcome is announced once | Exactly one `OrderPlacedV1` per `CONFIRMED` order, exactly one `OrderAbandonedV1` per `ABANDONED` order (only card orders reach that state), no order has both, no such event names an order that does not exist, and every abandoned card order tells payments that an authorization may exist |
| The server comes to rest | Once every checkout has been answered, no order still waits for its payment and the relay has published every event |
| No answer was a server error | No 5xx at all: a deadlock, a lock timeout or any unexpected failure would show up here |

| Scenario | What it fires at the server | What must come out |
|---|---|---|
| **Many keys, one promise** (`ConcurrentCheckoutInvariantsTest`) | 16 checkouts under one promise at once, each with its own idempotency key, as an app that makes up a new key per retry would send them | One `201`, fifteen `409 PROMISE_ALREADY_USED`, one order under the promise, one unit held |
| **Contended last units** | 16 customers, each with their own promise and key, race for the 5 units one store has left | Exactly 5 orders, 11 × `409 OUT_OF_STOCK` with the shortage, nothing but `201` and `409` (no deadlock, no lock timeout), `available = 0` afterwards |
| **Mixed load with retries** | 12 checkouts — cash, approved cards, and cards above the 1,000 AED decline limit — each submitted three times at once, every basket sharing one stock row | Per key exactly one answer that did the work, the others replaying it byte for byte or `409 IDEMPOTENCY_REQUEST_IN_PROGRESS`; a later retry replays it too; declines hold nothing (`PAYMENT_FAILED`, reservation released); `reserved` grew by exactly the confirmed units |
| **Payment outlives its hold** (`PaymentOutlivesHoldTest`, its own context: a clock the test moves, 2 s payments, sweeps every few hundred milliseconds) | A card checkout whose hold runs out while its payment is in flight, three times for each order of the race: the sweepers first, and the checkout first — held at the reservation's row lock while the clock passes the hold, so the sweepers, which skip locked rows, cannot get in front of it | `409 HOLD_EXPIRED`, the order `ABANDONED`, the stock back on sale exactly once, and exactly one `OrderAbandonedV1` with `paymentAuthorizationMayExist = true`: `HOLD_EXPIRED` when a sweeper won, `PAID_AFTER_HOLD_EXPIRED` when the checkout did |

Both orders of that race are exercised on every run — the second one deterministically — and all three interleavings
of the two sweepers and the checkout have been observed. Together the two classes take about 30 seconds on a 2-vCPU
machine, most of it the two Spring contexts and the six deliberate 2-second payments.

What this suite adds to the module tests: it proves that the guarantees survive **composition**. A module test can
only show that its own invariant holds against its own fakes; here the promise, the catalog, the inventory's locks,
the ordering transactions, the idempotency keys, the outbox and the background jobs all run at once, against one
database, under load.

## Metrics

Names as registered with Micrometer. Prometheus replaces dots with underscores and adds `_seconds` to timers and
`_total` to counters, so `daqiqa.promise.requests` is scraped as `daqiqa_promise_requests_seconds_bucket`, `_count`
and `_sum`.

| Metric | Type | Tags | Answers |
|---|---|---|---|
| `http.server.requests` | timer (Spring) | `uri`, `method`, `status`, `outcome` | Rate, errors and duration per endpoint (RED) |
| `daqiqa.promise.requests` | timer, percentile histogram and a 25 ms bucket | `status`, `tier`, `reason` | Promise latency against the target; share of `UNAVAILABLE` answers and why |
| `daqiqa.promise.eta` | distribution summary (minutes) | | What customers are being promised |
| `daqiqa.network.stores` | gauge | `status` | Stores by status, as this instance sees them |
| `daqiqa.network.coverage.cells` | gauge | | Cells covered by at least one store |
| `daqiqa.network.refresh` | timer | | Time to reload the network and reconcile the snapshot |
| `daqiqa.inventory.reservations` | counter | `outcome` = `reserved`, `rejected`, `replayed` | How often stock runs out between promise and checkout |
| `daqiqa.inventory.releases` | counter | `reason` | Why held stock goes back on sale on request |
| `daqiqa.inventory.expirations` | counter | `trigger` = `sweeper`, `late_confirm`, `replay` | Holds that expired unconfirmed |
| `daqiqa.inventory.expiry.failures` | counter | `scope` = `batch`, `hold` | Holds the sweeper could not expire; alert on `hold` |
| `daqiqa.inventory.expiry.oldest.overdue` | gauge (seconds) | | How late the most overdue hold is; rises whatever stalls expiry |
| `daqiqa.inventory.reserve` | timer, percentile histogram | | Deciding and applying a reservation, lock waits included |
| `daqiqa.ordering.checkouts` | counter | `outcome` = `confirmed`, `out_of_stock`, `payment_declined`, `hold_expired`, `store_unavailable`, `promise_already_used`, `replayed`, ... | Conversion and failure mix |
| `daqiqa.ordering.checkout` | timer | `outcome` | Checkout latency per outcome, payment included |
| `daqiqa.ordering.abandoned` | counter | `reason` = `hold_expired`, `paid_after_hold_expired` | Abandoned card orders; `paid_after_hold_expired` means an authorization to void certainly exists |
| `daqiqa.ordering.sweeper.failures` | counter | | Orders the sweeper could not abandon (retried) |
| `daqiqa.outbox.backlog` | gauge | | Unpublished events: the first sign that Kafka or the relay is unwell |
| `daqiqa.outbox.published`, `daqiqa.outbox.failures` | counters | `type` | Relay throughput and failed attempts per event type |

Queries used to verify the targets and to alert:

```promql
# Promise p99 over 5 minutes
histogram_quantile(0.99, sum by (le) (rate(daqiqa_promise_requests_seconds_bucket[5m])))

# Share of promises answered within the 25 ms target
  sum(rate(daqiqa_promise_requests_seconds_bucket{le="0.025"}[5m]))
/ sum(rate(daqiqa_promise_requests_seconds_count[5m]))

# Share of checkouts that found an item sold out after the promise (stale availability)
  sum(rate(daqiqa_ordering_checkouts_total{outcome="out_of_stock"}[15m]))
/ sum(rate(daqiqa_ordering_checkouts_total[15m]))

# Authorizations that certainly need voiding
increase(daqiqa_ordering_abandoned_total{reason="paid_after_hold_expired"}[1h])

# Events waiting for Kafka
daqiqa_outbox_backlog
```

Logs are plain text by default and JSON (Elastic Common Schema) with `DAQIQA_LOG_FORMAT=ecs`. The conventions keep
personal data out of them: the logging publisher prints event envelopes only, and promise errors report distances,
never coordinates.

## Capacity model

Back-of-envelope numbers that decide what the design has to handle. **The inputs are assumptions**, chosen to be
modest; the load tests in wave 3 replace them with measurements.

| Input (assumption) | Value |
|---|---|
| Orders in a busy city, average over trading hours | 3,000 per hour |
| Peak factor (evening rush, Iftar in Ramadan) | ×5, for minutes at a time |
| Promise requests per order (basket edits, app refreshes) | 20 |
| Dark stores in a busy city | 25 |
| Trading hours per day | 16 |
| SQL statements per checkout (claim, pricing, T1, T2) | about 15 |
| Time a checkout holds its row locks (T1) | about 5 ms |

| Derived, per busy city | Average | Peak |
|---|---|---|
| Orders | 0.83 per second | 4.2 per second |
| Promise requests | 17 per second | 83 per second |
| Orders per store | 0.03 per second (120 per hour) | 0.17 per second |
| SQL statements (checkout and promise) | 30 per second | 150 per second |
| Events to Kafka (about one per order) | 0.8 per second | 4 per second |

What the numbers say:

- **One instance covers many cities on throughput.** Ten busy cities peak at about 830 promise requests and 42 orders
  per second, below the 1,000 requests per second a single instance is specified for; the first baseline sustained
  2,370 per second at p99 ≤ 14 ms on two shared cores. Up to roughly ten busy cities, replicas are there for availability (at least
  two, across failure domains) rather than for load.
- **Row locks are not the bottleneck.** If T1 holds a `(store, SKU)` row for 5 ms, one row serialises up to 200
  reservations per second. A busy store peaks at 0.17 orders per second: three orders of magnitude of headroom. The
  ADRs size the design for tens of orders per second per city at peak, several times this model, and name a Redis
  pre-reservation layer as the next step if a flash sale ever concentrates demand on one SKU
  ([ADR-0005](../adr/0005-never-oversell-inventory-reservations.md)). If a row does get stuck, `lock_timeout` turns
  the wait into a fast, retryable failure after 3 s.
- **The connection pool is not the bottleneck either.** By Little's law, 1,000 promise requests per second at an
  assumed 2 ms of database time each keep 2 connections busy; each instance has a pool of 20 (`DAQIQA_DB_POOL_SIZE`).
  The local PostgreSQL allows 200 connections, room for about 9 instances at the default pool size.
- **Storage stays small, with one exception.** About 48,000 orders per day per busy city means about 48,000 outbox
  rows per day, kept 7 days (about 340,000 rows), and the same number of idempotency keys, kept 24 hours. Ended
  reservations, however, are kept forever until pruning is added (a [known gap](../roadmap.md#known-gaps-not-yet-scheduled)).
- **The relay is the first ceiling on events.** It sends one event and waits for its acknowledgement, a few hundred
  events per second against a local cluster: ample for about 4 events per second per busy city, and the next step when
  it is not (pipelined sends or CDC).
- **The first workload with a different profile is rider location.** Assuming 1,000 active riders per city reporting
  every 5 seconds, that is 200 writes per second per city, more than all orders and promises together, and
  short-lived data. This is why dispatch (wave 2) keeps rider positions in Redis GEO rather than PostgreSQL, and why
  it is the first candidate for extraction ([architecture guide](overview.md#10-evolution-path)).

## Availability

99.9% over 30 days allows 43.2 minutes of unavailability. The design spends that budget carefully:

| Dependency | On the request path? | Effect when it fails |
|---|---|---|
| Daqiqa instances | Yes | Stateless: the load balancer routes around a lost instance. In-flight requests fail and are retried safely with the same `Idempotency-Key`; a retry after the 60 s lease continues the same order. |
| PostgreSQL | Yes | Promises and checkouts fail; nothing is half-written. This is the component whose failover time dominates the budget; production needs a managed, highly available PostgreSQL (wave 4). |
| Kafka | **No** | Checkout keeps working: events wait in the outbox, the relay backs off (1 s doubling to 5 min), and events drain in order when Kafka returns. A relay stuck on the broker holds one scheduler thread, so holds keep expiring ([ADR-0014](../adr/0014-background-jobs-on-a-platform-thread-scheduler.md)). |
| Payment provider | For card orders only | Card checkouts fail and can be retried with the same key; the provider deduplicates on the order id. Cash-on-delivery orders are unaffected. |

Keeping Kafka off the request path is a direct result of the outbox: an order depends on one database commit, not on
two systems agreeing.

## Durability

An order is acknowledged (`201`) only after PostgreSQL has committed it together with its reservation, its
`OrderPlacedV1` event and the stored response. A crash before the commit loses nothing the client was told about; the
client's retry either replays the stored answer or, once the lease has run out, continues the same order under the
same order id. Surviving the loss of the database server itself needs synchronous replication or a managed HA
database, which is part of the production deployment in wave 4.

## Security

| Concern | Design | Status |
|---|---|---|
| Forged or altered promises | Tokens are HMAC-SHA256 signed; the signature is compared in constant time before anything is decoded; tokens over 1,024 characters are rejected | Built |
| Replaying an old promise | Tokens expire after 90 s (`daqiqa.promise.token.validity`), checked against the injected clock | Built |
| Using a promise for another basket | Tokens carry a SHA-256 hash of the merged, SKU-sorted basket (`PROMISE_CART_MISMATCH`) | Built |
| Using a promise for another address | Tokens carry the promised location; a delivery point more than 100 m away is refused (`PROMISE_LOCATION_MISMATCH`) | Built |
| Using a promise after the store stopped | Checkout checks that the store still accepts orders (`STORE_UNAVAILABLE`): at once on the instance that paused it, within the 30 s refresh on other replicas | Built |
| Using one promise for two orders | At most one live order per promise (`PROMISE_ALREADY_USED`) | Built |
| Weak or missing keys | Every configured key must be at least 32 bytes; the server refuses to start without an active key, and refuses to start at all when the published development key is configured while the `dev` profile is inactive (it only warns when it is) | Built |
| A leaked key minting long-lived promises | Verification also refuses correctly signed tokens no instance issues: valid for longer than `daqiqa.promise.token.validity`, or issued more than 30 s in the future | Built |
| Key compromise or routine rotation | Each token names its key (`kid`), so keys rotate without downtime (below) | Built |
| Personal data | Tokens carry no customer identity, but they do carry the promised location in readable base64: treat a token like the address it was made for. The logging publisher and promise errors keep coordinates out of logs | Built |
| Authentication and authorisation | None in v1: `customerId` comes from the request body and `GET /v1/orders/{orderId}` checks no ownership | OAuth2 with Keycloak in wave 4 |

Rotating the signing key without downtime:

1. Add the new key (`daqiqa.promise.token.keys.k2`) to every instance while `k1` stays active. After this rolling
   restart every instance can verify both.
2. Set `daqiqa.promise.token.active-key-id=k2`. New tokens are signed with `k2`; tokens signed with `k1` still verify.
3. After at least one validity period (90 s), remove `k1`.

Doing steps 1 and 2 in one restart would let an updated instance issue `k2` tokens that an instance not yet updated
cannot verify.
