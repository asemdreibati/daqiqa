# Quality attributes

What Daqiqa must guarantee, how fast it must be, and how each claim is checked. A target without a way to verify it
is a wish, so every row names its evidence and its status.

**Status words.** *Built*: merged with tests. *In progress*: specified, being built in wave 1. *Planned*: a later
wave. *Design target*: a goal the architecture is shaped for, not yet measured.

## Targets

| Attribute | Target | How it is verified | Status |
|---|---|---|---|
| Promise latency | p99 < 25 ms at 1,000 requests/s per instance | Timer `daqiqa.promise.requests` with a percentile histogram; k6 load test against the simulator | Metric in progress (wave 1); measured in wave 3 |
| Checkout latency | p99 < 150 ms, excluding the payment provider | Timer `daqiqa.ordering.checkout`; k6 with the simulated gateway's latency set to zero | Metric in progress; measured in wave 3 |
| Oversell | 0 units, under any concurrency. A hard invariant, not a percentile | `CHECK (reserved <= on_hand)` in the database; concurrency tests on real PostgreSQL (below); chaos drills | In progress |
| Durability | No acknowledged order is lost | `201` is sent only after the transaction that confirms the order, stores the response and appends the event has committed; chaos drill that kills an instance mid-checkout | In progress; drill in wave 3 |
| Retry safety | Every call that creates a business fact is safe to retry | `Idempotency-Key` on `POST /v1/orders`; test: 20 identical requests in parallel create one order and one reservation | In progress |
| Event delivery | At-least-once, in order per store, sub-second in the normal case | Relay tests: publishes in `seq` order, stops the batch on failure, two relays never publish the same row; `@EmbeddedKafka` adapter test | In progress |
| Promise accuracy | On-time rate ≥ 95% (delivered at or before `deliverBy`) | City simulator (wave 2) plus the accuracy loop (wave 3), per store, tier and hour | Planned |
| Availability | 99.9% for the HTTP API: at most 43 minutes of unavailability per 30 days | Stateless replicas, readiness probes, graceful shutdown; chaos drills | Design target |
| Security | Promises cannot be forged or altered; signing keys rotate without downtime | Token tests for every failure reason, including a token signed with a previous key that is still configured | In progress; OAuth2 in wave 4 |
| Observability | RED metrics per endpoint, business metrics per module, structured logs, health probes | `/actuator/prometheus`, `/actuator/health/liveness` and `/actuator/health/readiness`; Grafana dashboards; OpenTelemetry traces | Endpoint and probes built; dashboards and tracing in wave 3 |
| Modularity | No module reaches into another's internals; the domain is framework-free | ArchUnit rules on every build ([ADR-0003](../adr/0003-hexagonal-modules-enforced-by-tests.md)) | Built |
| Determinism | Time-dependent behaviour is testable without sleeping | One injected `Clock` ([ADR-0011](../adr/0011-injected-clock.md)); fixed or advanced clocks in tests | Built (convention) |

## The invariants, and the tests that defend them

Latency targets are percentiles. These are not: one violation is a defect.

| Invariant | Defence in the design | Test (wave 1 specification) |
|---|---|---|
| A store never has more units reserved than on hand | Basket rows locked in SKU order with `FOR UPDATE`, shortages checked on the locked rows; `CHECK (reserved <= on_hand)` as the last line | 200 threads each reserve 1 unit of a SKU with 50 on hand: exactly 50 succeed, 150 are rejected, `reserved = 50`, no exceptions |
| Concurrent baskets never deadlock | Same lock order (by SKU) for every basket | Many threads reserve `[A, B]` and `[B, A]` at once: no deadlock errors; total reserved equals the sum of successful reservations |
| Held stock returns to sale exactly once | Sweeper batches with `FOR UPDATE SKIP LOCKED`; a late `confirm` expires the hold instead of confirming it | Concurrent reserve, release and sweep: invariants hold at the end |
| One key, one order | Key claimed in its own transaction with a lease; final response stored with the order | 20 identical requests with one key in parallel: one order, one reservation, every response is the `201` or `409 IDEMPOTENCY_REQUEST_IN_PROGRESS` |
| No event is published twice by the relay, none is lost | Advisory lock per round; rows marked published only after Kafka acknowledges | Two relays in parallel never publish the same row; a failed send stops the batch |

## Metrics

Names as registered with Micrometer. Prometheus replaces dots with underscores and adds `_seconds` to timers and
`_total` to counters, so `daqiqa.promise.requests` is scraped as `daqiqa_promise_requests_seconds_bucket`, `_count`
and `_sum`.

| Metric | Type | Tags | Answers |
|---|---|---|---|
| `http.server.requests` | timer (Spring) | `uri`, `method`, `status`, `outcome` | Rate, errors and duration per endpoint (RED) |
| `daqiqa.promise.requests` | timer, percentile histogram | `status`, `tier`, `reason` | Promise latency; share of `UNAVAILABLE` answers and why |
| `daqiqa.promise.eta` | distribution summary (minutes) | | What customers are being promised |
| `daqiqa.inventory.reservations` | counter | `outcome` = `reserved`, `rejected`, `replayed` | How often stock runs out between promise and checkout |
| `daqiqa.inventory.releases` | counter | `reason` | Why held stock goes back on sale |
| `daqiqa.inventory.expirations` | counter | | Holds that timed out |
| `daqiqa.inventory.reserve` | timer | | How long a reservation takes, which bounds how long row locks are held |
| `daqiqa.ordering.checkouts` | counter | `outcome` = `confirmed`, `out_of_stock`, `payment_declined`, `promise_invalid`, `replayed`, ... | Conversion and failure mix |
| `daqiqa.ordering.checkout` | timer | | Checkout latency |
| `daqiqa.outbox.backlog` | gauge | | Unpublished events: the first sign that Kafka or the relay is unwell |
| `daqiqa.outbox.published`, `daqiqa.outbox.failures` | counters | | Relay throughput and errors |

Queries used to verify the targets:

```promql
# Promise p99 over 5 minutes
histogram_quantile(0.99, sum by (le) (rate(daqiqa_promise_requests_seconds_bucket[5m])))

# Share of checkouts that found an item sold out after the promise (stale availability)
  sum(rate(daqiqa_ordering_checkouts_total{outcome="out_of_stock"}[15m]))
/ sum(rate(daqiqa_ordering_checkouts_total[15m]))

# Events waiting for Kafka
daqiqa_outbox_backlog
```

Logs are plain text by default and JSON (Elastic Common Schema) with `DAQIQA_LOG_FORMAT=ecs`. They carry no personal
data (definition of done in the [conventions](../engineering/conventions.md)).

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
  per second, below the 1,000 requests per second a single instance is specified for. Up to roughly ten busy
  cities, replicas are there for availability (at least two, across failure domains) rather than for load.
- **Row locks are not the bottleneck.** If T1 holds a `(store, SKU)` row for 5 ms, one row serialises up to 200
  reservations per second. A busy store peaks at 0.17 orders per second: three orders of magnitude of headroom. The
  ADRs size the design for tens of orders per second per city at peak, several times this model, and name a Redis
  pre-reservation layer as the next step if a flash sale ever concentrates demand on one SKU
  ([ADR-0005](../adr/0005-never-oversell-inventory-reservations.md)).
- **The connection pool is not the bottleneck either.** By Little's law, 1,000 promise requests per second at an
  assumed 2 ms of database time each keep 2 connections busy; each instance has a pool of 20 (`DAQIQA_DB_POOL_SIZE`). The
  local PostgreSQL allows 200 connections, room for about 9 instances at the default pool size.
- **Storage stays small.** About 48,000 orders per day per busy city means about 48,000 outbox rows per day, kept 7 days
  (about 340,000 rows), and the same number of idempotency keys, kept 24 hours.
- **The first workload with a different profile is rider location.** Assuming 1,000 active riders per city reporting
  every 5 seconds, that is 200 writes per second per city, more than all orders and promises together, and
  short-lived data. This is why dispatch (wave 2) keeps rider positions in Redis GEO rather than PostgreSQL, and why
  it is the first candidate for extraction ([architecture guide](overview.md#10-evolution-path)).

## Availability

99.9% over 30 days allows 43.2 minutes of unavailability. The design spends that budget carefully:

| Dependency | On the request path? | Effect when it fails |
|---|---|---|
| Daqiqa instances | Yes | Stateless: the load balancer routes around a lost instance. In-flight requests fail and are retried safely with the same `Idempotency-Key`. |
| PostgreSQL | Yes | Promises and checkouts fail; nothing is half-written. This is the component whose failover time dominates the budget; production needs a managed, highly available PostgreSQL (wave 4). |
| Kafka | **No** | Checkout keeps working: events wait in the outbox and drain in order when Kafka returns. The backlog gauge shows the delay. |
| Payment provider | For card orders only | Card checkouts fail and can be retried with the same key; cash-on-delivery orders are unaffected. |

Keeping Kafka off the request path is a direct result of the outbox: an order depends on one database commit, not on
two systems agreeing.

## Durability

An order is acknowledged (`201`) only after PostgreSQL has committed it together with its reservation, its
`OrderPlacedV1` event and the stored idempotent response. A crash before the commit loses nothing the client was told
about; the client's retry either replays the stored answer or runs the checkout again. Surviving the loss of the
database server itself needs synchronous replication or a managed HA database, which is part of the production
deployment in wave 4.

## Security

| Concern | Design | Status |
|---|---|---|
| Forged or altered promises | Tokens are HMAC-SHA256 signed; verification compares signatures in constant time (`MessageDigest.isEqual`) | In progress |
| Replaying an old promise | Tokens expire after 90 s (`daqiqa.promise.token.validity`), checked against the injected clock | In progress |
| Using a promise for another basket | Tokens carry a SHA-256 hash of the merged, SKU-sorted basket | In progress |
| Weak or missing keys | Keys are at least 32 bytes; the application refuses to start if the active key is missing or too short | In progress |
| Key compromise or routine rotation | Each token names its key (`kid`), so keys rotate without downtime (below) | In progress |
| Personal data | Tokens contain none (store, tier, ETA, times, basket hash); logs contain none | In progress |
| Authentication and authorisation | None in v1: `customerId` comes from the request body | OAuth2 with Keycloak in wave 4 |

Rotating the signing key without downtime:

1. Add the new key (`daqiqa.promise.token.keys.k2`) to every instance while `k1` stays active. After this rolling
   restart every instance can verify both.
2. Set `daqiqa.promise.token.active-key-id=k2`. New tokens are signed with `k2`; tokens signed with `k1` still verify.
3. After at least one validity period (90 s), remove `k1`.

Doing steps 1 and 2 in one restart would let an updated instance issue `k2` tokens that an instance not yet updated
cannot verify.
