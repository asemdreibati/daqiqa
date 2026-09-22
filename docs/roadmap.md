# Roadmap

Daqiqa is built in waves. Each wave has one goal, a fixed list of deliverables and exit criteria ("done when") that
can be checked by running something, not by opinion. Inside a wave, modules are built in parallel against contracts
written first: the OpenAPI document, the event records, each module's `api` package and a design note.

| Wave | Goal | Status |
|---|---|---|
| [0: Foundation](#wave-0-foundation) | A build, a skeleton and contracts that make parallel work safe | Done |
| [1: Promise to order](#wave-1-promise-to-order) | Promise a time, hold the stock, place the order exactly once | In progress |
| [2: Fulfil and deliver](#wave-2-fulfil-and-deliver) | Pick, dispatch, track, and a simulated city to run it all in | Planned |
| [3: Measure and harden](#wave-3-measure-and-harden) | Prove the promises: accuracy, latency, resilience, published | Planned |
| [4: Product surface and production](#wave-4-product-surface-and-production) | Ops console, search, security, cloud deployment | Planned |

Status words mean the same thing everywhere in the docs: **Done** means merged to `main` with tests; **In progress**
means specified and being built now; **Planned** means scoped for a later wave, not started.

## Wave 0: Foundation

**Goal.** Make five modules buildable in parallel by different engineers without collisions, and make the
architecture enforce itself.

| Deliverable | Status |
|---|---|
| Gradle build: convention plugins, version catalog, Spotless formatting, JaCoCo reports | Done |
| Shared kernel: UUIDv7 ids, `Money` in minor units, `GeoPoint`, `LocalizedText`, `SkuQuantity` | Done |
| Contracts: `OrderPlacedV1`, `StockAdjustedV1`, `ReservationExpiredV1`, guarded by `EventContractTest` | Done |
| Messaging port: `OutboxWriter` and an in-memory test double | Done |
| Test infrastructure: `PostgresTestDatabase` (Testcontainers or any server), `ArchitectureRules` | Done |
| Catalog reference module: bilingual products, keyset pagination, problem details | Done |
| Server app: health probes, Prometheus endpoint, OpenAPI UI, demo catalog of 52 products | Done |
| CI on every push to `main` and every pull request | Done |
| 13 ADRs, engineering conventions, design specifications for wave 1, OpenAPI contract | Done |

**Done when**

- [x] `./gradlew build` runs formatting checks, compilation, unit tests, integration tests against real PostgreSQL
      and architecture rules, locally and in CI.
- [x] The server boots against PostgreSQL with every migration and the demo catalog, and serves health, Prometheus
      metrics and the OpenAPI document (`DaqiqaServerApplicationTest`).
- [x] Every wave 1 module has its public `api` package (empty on purpose for ordering), a design specification and
      its endpoints in the OpenAPI contract.

## Wave 1: Promise to order

**Goal.** The core loop: answer "how fast can this basket get here?" from memory and one indexed read, then turn
the answer into exactly one order without ever selling a unit twice.

| Deliverable | Design | Status |
|---|---|---|
| Network: dark stores, status changes, H3 coverage index, travel-time model | [promise.md](design/promise.md), [ADR-0006](adr/0006-h3-for-coverage.md) | In progress |
| Promise engine: ETA breakdown, tiers, remove-item suggestions, signed tokens | [promise.md](design/promise.md), [ADR-0007](adr/0007-signed-promise-tokens.md) | In progress |
| Inventory: never-oversell reservations, expiry sweeper, stock adjustments with audit trail | [inventory.md](design/inventory.md), [ADR-0005](adr/0005-never-oversell-inventory-reservations.md) | In progress |
| Ordering: idempotent checkout, simulated payment, abandoned-order sweeper | [ordering.md](design/ordering.md), [ADR-0008](adr/0008-idempotency-keys.md) | In progress |
| Messaging: transactional outbox, relay to Kafka under an advisory lock | [ordering.md](design/ordering.md), [ADR-0009](adr/0009-transactional-outbox-and-kafka.md) | In progress |
| Product and architecture documentation (this set) | [README](../README.md) | In progress |
| Dubai demo stores and stock, so the README walkthrough runs end to end | [conventions](engineering/conventions.md) | Not started |

**Done when**

- [ ] Promise, order and stock endpoints behave as the [OpenAPI contract](../api/openapi/daqiqa-v1.yaml) says, for
      card and cash-on-delivery orders.
- [ ] The headline concurrency tests pass against real PostgreSQL: 200 threads reserving 1 unit of a SKU with 50 on
      hand end with exactly 50 reservations; baskets sharing SKUs in opposite orders never deadlock; 20 identical
      checkouts with one `Idempotency-Key` create one order and one reservation.
- [ ] Expired holds return to sale exactly once with several sweepers running; abandoned orders are closed.
- [ ] Committed events reach Kafka in order per store; two relays never publish the same row; a failed send stops
      the batch.
- [ ] Property tests hold for coverage and promises: every returned store is within its radius, results are sorted
      by travel time, the chosen store can always fulfil the basket.
- [ ] Each design note has an "Implementation notes" section and the metrics it lists are exported.

## Wave 2: Fulfil and deliver

**Goal.** Follow an order from confirmation to the doorstep, and build the simulated city that every later wave is
measured in.

| Deliverable | Status |
|---|---|
| Fulfilment: a picking queue per store; picked reservations become `COMMITTED` (on hand and reserved both drop) | Planned |
| Order lifecycle beyond `CONFIRMED`: `PICKING`, `OUT_FOR_DELIVERY`, `DELIVERED` | Planned |
| Dispatch: riders, nearest free rider via Redis GEO, assignment partitioned by store | Planned |
| Live order tracking over server-sent events (SSE) | Planned |
| City simulator: Dubai and Riyadh presets, demand curves, accelerated clock, chaos events | Planned |

**Done when**

- [ ] A simulated day in Dubai runs end to end on an accelerated clock: orders are promised, placed, picked,
      assigned to riders and delivered.
- [ ] Every delivered order records its stage timestamps next to the promise it was sold with.
- [ ] A chaos event (for example, pausing a store at peak) visibly changes promises in the simulation.
- [ ] A shopper can follow an order live.

## Wave 3: Measure and harden

**Goal.** Replace targets with measurements, and publish them.

| Deliverable | Status |
|---|---|
| Promise accuracy loop: on-time rate per store, tier and hour; lateness attributed to ETA components | Planned |
| Self-calibrating safety buffers per store, replacing the fixed 90 s | Planned |
| OpenTelemetry tracing across HTTP, PostgreSQL and Kafka | Planned |
| Grafana dashboards for the metrics in [quality attributes](architecture/quality-attributes.md) | Planned |
| k6 load tests and chaos drills, with results published in the repository | Planned |

**Done when**

- [ ] On-time rate is at least 95% in the Dubai and Riyadh simulations, with buffers learned per store.
- [ ] Published load tests show promise p99 under 25 ms at 1,000 requests per second per instance and checkout p99
      under 150 ms excluding the payment provider.
- [ ] Published chaos drills (instance killed mid-checkout, Kafka outage, store paused under load) show zero
      oversold units and zero lost acknowledged orders.
- [ ] One trace follows an order from promise to delivery.

## Wave 4: Product surface and production

**Goal.** The surfaces a real operator would need, and a real deployment.

| Deliverable | Status |
|---|---|
| Ops console: Angular live map of stores, riders and orders | Planned |
| Bilingual Arabic and English product search on OpenSearch | Planned |
| OAuth2 with Keycloak for every endpoint | Planned |
| Kubernetes deployment provisioned with Terraform | Planned |

**Done when**

- [ ] Operators see stores, riders and orders live on a map and can change a store's status from it.
- [ ] A search for "حليب" and a search for "milk" find the same products.
- [ ] Every endpoint requires a token with the right scope, and the customer id comes from the token.
- [ ] One Terraform apply produces a running deployment with several replicas, PostgreSQL and Kafka.

## Not yet scheduled

Options the ADRs and design notes already discuss, to be scheduled when a measurement asks for them:

| Item | Trigger | Discussed in |
|---|---|---|
| `StoreStatusChanged` event so a status change reaches every replica | More than one replica serving promises | [promise.md](design/promise.md) |
| Traffic-aware travel times; polygon delivery zones | Accuracy data shows travel is the late component | [promise.md](design/promise.md), [ADR-0006](adr/0006-h3-for-coverage.md) |
| Redis pre-reservation in front of the same guarantee | A flash sale makes one SKU a hot spot | [ADR-0005](adr/0005-never-oversell-inventory-reservations.md) |
| Debezium CDC instead of the polling relay | Relay latency or throughput becomes a constraint | [ADR-0009](adr/0009-transactional-outbox-and-kafka.md) |
| Avro or Protobuf with a schema registry | External event consumers appear | [ADR-0009](adr/0009-transactional-outbox-and-kafka.md) |
| Extracting a module into a service | A different scaling profile, availability need or team boundary | [ADR-0002](adr/0002-modular-monolith-first.md) |
