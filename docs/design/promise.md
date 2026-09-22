# Network and promise — design specification (wave 1)

> Owner: promise engineer. Status: specification; add "Implementation notes" at the end as you build.
> Related: ADR-0006 (H3), ADR-0007 (tokens), ADR-0011 (clock), `modules/network/.../api`, `modules/promise/.../api`.

## Part 1 — Network

### Responsibilities

Dark stores (where they are, whether they are open, how many pickers work there, how far they deliver) and the
**coverage index**: which stores can serve a point, and how long the ride takes.

### Schema (suggested; you own it)

`V1_1__network_schema.sql`: `network.dark_store(id text pk, name_en, name_ar, latitude, longitude, status,
active_pickers ≥ 0, service_radius_m between 500 and 10000, created_at, updated_at)`, with a `CHECK` on the id format
and the status values. Tests insert their own stores; demo stores are added by the server app later.

### Coverage index

- H3 resolution 9 (`com.uber:h3`). For each store accepting orders or not, precompute the cells its service circle
  touches: a grid disk around the store's cell, keeping the cells whose centre lies within `serviceRadiusMeters`
  **plus one cell radius**. *(Corrected during implementation: keeping only cells whose centre is within the radius
  misses customers inside the radius near its edge, and the exact distance check can only remove candidates, never add
  them back. See the implementation notes.)*
- Index: `Map<Long cell, List<StoreId>>`, built as an **immutable snapshot** and swapped atomically
  (`AtomicReference`) whenever the store set changes (startup, status change). Readers never lock.
- `storesServing(point)`: cell lookup → candidates → keep those whose status accepts orders (read the current status,
  so a pause takes effect immediately) → compute exact distance from the store to the point → drop candidates beyond
  the radius → travel time → sort fastest first.
- Travel model v1 (`TravelTimeModel`, domain): `distance × roadFactor / speed + handoff`, configurable under
  `daqiqa.network.travel.*` (defaults: road factor 1.3, speed 7 m/s ≈ 25 km/h, handoff 60 s). Traffic by time of day is
  a later refinement.

### HTTP

`GET /v1/stores`, `GET /v1/stores/{storeId}`, `PUT /v1/stores/{storeId}/status` (per the contract). A status change
must be visible to `CoverageIndex` immediately (same instance). Multi-instance propagation comes with the
`StoreStatusChanged` event in a later wave; note it in the implementation notes.

### Required tests

Coverage (point next to a store covered; far point not; fastest first; paused store excluded; property test: every
returned store is within its radius and results are sorted by travel time), travel model, persistence against real
PostgreSQL, web slices, `ArchitectureTest`.

## Part 2 — Promise engine

### Algorithm

Input: location, basket (1–50 lines, duplicates merged). Output: an offer (store, tier, ETA, breakdown, token) or an
unavailable reason, plus suggestions.

1. `candidates = coverageIndex.storesServing(location)`. None → `UNAVAILABLE / OUT_OF_COVERAGE`.
2. `snapshot = availabilityQuery.availability(candidateStores, basketSkus)` — one database round trip.
3. For every candidate compute the ETA breakdown (seconds):
   - `queue` — waiting for a picker: `StoreLoadProvider` (v1: configured per store status; `OPEN` 60 s, `BUSY` 300 s).
   - `pick` — `pickBase + perUnit × totalUnits` (defaults 45 s + 8 s per unit, capped at 600 s).
   - `riderWait` — `StoreLoadProvider` (v1: `OPEN` 60 s, `BUSY` 180 s).
   - `travel` — from the coverage index.
   - `buffer` — `PromiseCalibration` (v1: 90 s for every store; wave 3 learns it from late deliveries).
   - `etaMinutes = ceil(total / 60)`.
4. Among candidates that can fulfil the **whole** basket, choose the smallest ETA (ties: shorter distance, then store
   id). None can → `UNAVAILABLE / OUT_OF_STOCK`.
5. `tier = DeliveryTier.forEta(etaMinutes)`; none → `UNAVAILABLE / NO_CAPACITY`.
6. **Suggestions** (`REMOVE_ITEMS`): for candidates that cannot fulfil the whole basket but can fulfil the part that is
   in stock, if dropping the missing SKUs gives a strictly better tier or ETA than the offer (or there is no offer),
   suggest it. At most two, fastest first, never an empty basket.
7. Sign the offer (below) and record metrics.

Put steps 3–6 in a pure domain class (e.g. `PromiseCalculator`) that receives already-fetched data, so it is fully
unit- and property-testable (e.g. "the chosen store can always fulfil the basket", "adding travel time never lowers
the ETA", "suggestions never contain SKUs that are in stock at that store").

### Token (ADR-0007)

- Format: `v1.<kid>.<payload>.<signature>`, both base64url without padding.
- Payload: `promiseId|storeId|tier|etaMinutes|issuedAtEpochSecond|expiresAtEpochSecond|basketHash`.
- `basketHash`: SHA-256 over the merged basket sorted by SKU, formatted `SKU:qty` joined with `,`, base64url.
- Signature: HMAC-SHA256 over `v1.<kid>.<payload>` with the key named `kid`.
- Configuration: `daqiqa.promise.token.keys.<kid>` (base64, at least 32 bytes), `daqiqa.promise.token.active-key-id`,
  `daqiqa.promise.token.validity` (default 90 s). Fail fast at startup if the active key is missing or too short.
- Verification (`PromiseTokenVerifier`): format → `MALFORMED`; unknown kid → `MALFORMED`; signature mismatch
  (constant-time `MessageDigest.isEqual`) → `TAMPERED`; `now ≥ expiresAt` → `EXPIRED`; basket hash differs →
  `CART_MISMATCH`.

### HTTP

`POST /v1/promises` per the contract. Invalid input → 400 problem (`VALIDATION_FAILED` / `INVALID_REQUEST`).

### Metrics

`daqiqa.promise.requests` (timer with percentile histogram, tags `status`, `tier`, `reason`) and
`daqiqa.promise.eta` (distribution summary in minutes). Target: p99 under 25 ms at 1,000 requests per second per
instance (measured in the load-test wave).

### Required tests

Calculator unit and property tests, token round trip and every failure reason (including key rotation: a token signed
with an old key still verifies while that key is configured), service tests with fakes for the ports, web slice,
`ArchitectureTest`.

## Implementation notes (wave 1)

### What was built

| Module | Package | Key classes |
|---|---|---|
| network | `domain` | `Store` (invariants: radius 500..10,000 m, pickers ≥ 0), `StoreRepository` (port), `TravelTimeModel`, `CellGrid` (port), `NetworkSnapshot` (immutable coverage index), `StoreReach` |
| | `application` | `NetworkView` (implements `CoverageIndex` and `StoreDirectory` from an `AtomicReference<NetworkSnapshot>`), `StoreService` (list, get, change status), `StoreStatusChanged` (application event) |
| | `adapter` | `JdbcStoreRepository`, `H3CellGrid` (resolution 9), `StoreController` + `NetworkProblemHandler` |
| | `config` | `NetworkConfiguration`, `NetworkProperties` (`daqiqa.network.*`) |
| promise | `domain` | `Basket`, `Candidate`, `Stock`, `StoreLoad` + `StoreLoadProvider` (port), `PromiseCalibration` (port), `PickTimeModel`, `EtaBreakdown`, `PromiseCalculator`, `PromiseDecision`, `Quote`, `Suggestion`, `UnavailableReason` |
| | `application` | `PromiseService`, `PromiseTokenCodec` (implements `PromiseTokenVerifier`), `SigningKeys`, `StatusBasedStoreLoad` (v1 `StoreLoadProvider`), `PromiseMetrics` |
| | `adapter`, `config` | `PromiseController` + `PromiseProblemHandler`; `PromiseConfiguration`, `PromiseProperties` (`daqiqa.promise.*`) |

Schema: `network.dark_store` (`V1_1__network_schema.sql`). The promise context stores nothing.

### Decisions and deviations

**Coverage cells are a superset (spec corrected).** A cell can contain points of the service circle while its centre is
outside it, so `H3CellGrid.cellsCovering` keeps every cell whose centre is within `radius + cellRadius`, and the lookup
applies the exact rules (accepts orders, great-circle distance ≤ radius). The answer is exact: no store beyond its
radius, none missing. The margins come from measured H3 geometry at resolution 9 (average edge 200.8 m): the
centre-to-vertex distance is at most 1.0 × the average edge in the Gulf and 1.1 × anywhere on Earth (we use 1.5 ×);
each ring of a grid disk reaches at least 1.36 × further in Dubai and 1.08 × anywhere (we assume 0.75 ×). Measured in
Dubai: a 500 m store indexes 20 cells, a 3 km store 358 cells (1.4 ms to compute) and a 10 km store 3,500 (8 ms).

**One immutable snapshot, statuses included.** The cell index covers every store whatever its status, so a snapshot
built after a status change reuses the previous cell index (only footprints — id, location, radius — decide whether it
is rebuilt). A pause is therefore an O(stores) snapshot, not a re-indexing. The index maps a cell to the positions of
its stores in the snapshot's id-ordered store list (rather than to a `List<StoreId>`), so a lookup does no hashing per
candidate. Readers take the current snapshot with one volatile read and never lock; the concurrency tests check that
readers only ever see the old or the new network.

**Refreshes reload from the database, serialised.** `NetworkView.refresh()` reads all stores and publishes a new
snapshot under a `ReentrantLock` (not `synchronized`: the section blocks on JDBC and virtual threads must not pin), so
a refresh that read older data can never overwrite one that read newer data. It runs:

- when the bean starts (`InitializingBean`), i.e. before the application serves requests; `JdbcClient` beans are
  ordered after Flyway by Spring Boot, so the schema exists;
- after every committed status change on this instance: `StoreService.changeStatus` publishes `StoreStatusChanged` and
  `NetworkView` listens with `@TransactionalEventListener(fallbackExecution = true)`, which runs after the commit and
  before the use case returns. A rolled-back change never reaches the index (tested). If that refresh fails, Spring
  logs it (after-commit listeners cannot fail a committed transaction) and the periodic refresh repairs it;
- every `daqiqa.network.coverage.refresh-interval` (30 s) when the application enables scheduling, through a
  `SchedulingConfigurer` bean. This bounds how stale an instance is about changes made through another instance (or
  stores added by a migration) until the `StoreStatusChangedV1` event arrives.

**`StoreDirectory` answers from memory; the store endpoints read the database.** The promise engine needs every
candidate's status to pick its load, and a directory lookup per candidate must not cost a round trip. Operators, on the
other hand, should see exactly what was committed, so `GET /v1/stores` reads the system of record.

**Domain types.** Each domain uses its own context's published enums (`StoreStatus`, `DeliveryTier`) rather than a
copy, so "which statuses accept orders" and the tier limits have a single source of truth (the layer rules allow it).
Other contexts' types never reach a domain: `PromiseService` translates `StoreCoverage` into `Candidate` and
`StockSnapshot` into `Stock` (a method reference, no copy).

**ETA ties are decided at minute granularity.** Two stores promising the same number of minutes are equally good for
the customer; the nearer one wins (a shorter ride is less exposed to traffic), then the smaller store id, so the answer
never depends on the order of candidates.

**Rounding.** Every breakdown part is rounded up to the second and the ETA is the sum rounded up to the minute, with a
minimum of one minute. Rounding never makes a promise optimistic, and the parts shown always add up to the ETA shown.

**Suggestions.** A suggestion drops exactly the lines the store cannot supply in full (a partly stocked line counts as
missing: `REMOVE_ITEMS` carries SKUs, not quantities), never the whole basket, and must fit a tier. "A strictly better
tier or ETA" is implemented as a strictly smaller ETA, which is equivalent (a faster tier always means fewer minutes).
Addition to the spec: a suggestion is left out when another one drops no more items and is at least as fast (it would
only offer the customer a worse deal); the two shown are therefore genuinely different options.

**Store load, v1.** `OPEN` stores get the open waits; `BUSY` stores, and conservatively any store that stopped
accepting orders since the coverage lookup or is unknown to the directory, get the busy waits.

**Tokens.** The signature is compared as its canonical base64url text with `MessageDigest.isEqual` (constant time), so
no timing side channel leaks how much of a forged signature was right, and alternative encodings of the same bytes are
rejected. Nothing is decoded before the signature is checked; tokens over 1,024 characters are rejected up front.
Issue and expiry times are whole epoch seconds (the response's `expiresAt` is exactly the token's); the validity must be
between 1 s and 1 h. Every configured key, not only the active one, must decode to at least 32 bytes, because a weak
verify-only key would still let anyone forge tokens under its id. Key ids are 1 to 32 characters of `[A-Za-z0-9_-]`.
Verification order: format and key id (`MALFORMED`), signature (`TAMPERED`), expiry (`EXPIRED`, `now ≥ expiresAt`),
basket (`CART_MISMATCH`, which also covers an empty or otherwise impossible basket).

**Validation.** Store endpoints validate the path id against the contract's pattern; Spring then validates the whole
method, so body errors on `PUT .../status` are reported the same way (`VALIDATION_FAILED`, 400). Validation problems
carry an `errors` array of `{field, message}`. An unreadable body is `INVALID_REQUEST` and names the offending JSON path
when there is one. A basket that breaks a rule the schema cannot express (more than 99 units of a SKU once duplicate
lines are merged) is `INVALID_REQUEST`. A malformed store id on `GET /v1/stores/{storeId}` is a 400 although the
contract lists only 200 and 404 there, as the catalog does for SKUs.

**Schema.** `id text COLLATE "C"`: listings are in byte order whatever the database locale, the same order the
in-memory directory uses. New stores default to `CLOSED`. `CHECK` constraints mirror every domain invariant.

### Configuration

| Property | Default | Meaning |
|---|---|---|
| `daqiqa.network.travel.road-factor` | `1.3` | road distance per metre of straight line (≥ 1) |
| `daqiqa.network.travel.speed-meters-per-second` | `7` | average riding speed (≈ 25 km/h) |
| `daqiqa.network.travel.handoff` | `60s` | parking, finding the door, handing over |
| `daqiqa.network.coverage.refresh-interval` | `30s` | periodic reload of the store network (when scheduling is enabled) |
| `daqiqa.promise.pick.base` / `per-unit` / `max` | `45s` / `8s` / `600s` | picking and packing time |
| `daqiqa.promise.load.open.queue` / `rider-wait` | `60s` / `60s` | waits at an open store |
| `daqiqa.promise.load.busy.queue` / `rider-wait` | `300s` / `180s` | waits at a busy store |
| `daqiqa.promise.buffer` | `90s` | safety margin on every ETA |
| `daqiqa.promise.token.active-key-id` | **required** | key that signs new tokens |
| `daqiqa.promise.token.keys.<id>` | **required** | base64 keys (≥ 32 bytes each, e.g. `openssl rand -base64 32`); non-active keys only verify |
| `daqiqa.promise.token.validity` | `90s` | how long a promise can be turned into an order (1 s to 1 h) |

Without a signing key the application refuses to start: *Failed to bind properties under 'daqiqa.promise' … No promise
token signing key is configured: set daqiqa.promise.token.active-key-id=&lt;id&gt; and
daqiqa.promise.token.keys.&lt;id&gt;=&lt;base64 key of at least 32 bytes&gt;*. The server application supplies a dev key;
production supplies its own (for example `DAQIQA_PROMISE_TOKEN_ACTIVE_KEY_ID` and `DAQIQA_PROMISE_TOKEN_KEYS_K1`).
Rotation: add the new key everywhere, make it active, remove the old one once its tokens have expired (validity).

### Metrics

| Meter | Type | Tags | Notes |
|---|---|---|---|
| `daqiqa.promise.requests` | timer | `status` (AVAILABLE, UNAVAILABLE), `tier`, `reason` (`none` when not applicable) | percentile histogram plus a 25 ms bucket (the p99 target); all six series registered at startup; failed requests are in `http.server.requests` |
| `daqiqa.promise.eta` | distribution summary (minutes) | | promised ETAs, percentile histogram |
| `daqiqa.network.stores` | gauge | `status` | stores by status as this instance sees them |
| `daqiqa.network.coverage.cells` | gauge | | cells covered by at least one store |
| `daqiqa.network.refresh` | timer | | time to reload the network and publish a snapshot |

### How the tests prove the guarantees

| Guarantee | Test |
|---|---|
| A point is served by exactly the stores that accept orders and whose radius contains it, fastest first | `CoverageIndexPropertiesTest` (jqwik: 1,000 points across Dubai, 1,000 points at the edge of service areas, 100 random networks × 25 points) |
| Cell covering never misses a cell the circle touches | `H3CellGridTest` (jqwik: circles in the Gulf and anywhere on Earth, 360 edge points + 200 inside each) |
| Exact distance, status filter, ordering and tie-breaks | `NetworkSnapshotTest` (a one-cell grid, so only the exact rules decide), `CoverageIndexTest` |
| A status change reaches the index at once; a rolled-back one never does; the index is built at startup | `StoreStatusChangeIntegrationTest` (Spring + PostgreSQL) |
| Readers never see a half-built network; concurrent refreshes converge on the database | `NetworkViewConcurrencyTest` |
| Status-only changes reuse the cell index; moves and radius changes rebuild it | `NetworkSnapshotTest` |
| Travel model: defaults, rounding up, monotonic | `TravelTimeModelTest` (unit + property) |
| SQL, constraints, byte-order listing, `updated_at` from the clock | `JdbcStoreRepositoryTest` (PostgreSQL) |
| Every calculator branch with worked numbers | `PromiseCalculatorTest` (20 cases) |
| Chosen store can supply the basket; none faster could; ETA = breakdown rounded up, in its tier; suggestions drop exactly what that store lacks, never everything, at most two, fastest first, faster than the offer; right unavailable reason; more travel never makes a promise sooner | `PromiseCalculatorPropertiesTest` (jqwik, 1,000 scenarios each; statistics coverage checks fail the build if the generator stops producing every outcome) |
| Token format (against an independent HMAC implementation), round trip, tampered payload, tampered signature, other secret, expiry boundary, cart mismatch incl. merged duplicates, unknown kid, malformed input, rotation, check order | `PromiseTokenCodecTest`, `SigningKeysTest`, `BasketHashTest` |
| One availability round trip for all candidates and SKUs, none without candidates; busy stores; merged duplicates; metrics | `PromiseServiceTest` (fakes for coverage, stock and the directory) |
| Fail fast on a missing, short, non-base64 or unconfigured key; defaults; overrides | `PromiseConfigurationTest` |
| Wire contract and problem codes | `StoreControllerTest`, `PromiseControllerTest` (`@WebMvcTest`) |
| A promise made over HTTP is honoured by the `PromiseTokenVerifier` bean checkout uses | `PromiseModuleTest` |
| Layering | `ArchitectureTest` in both modules |

Totals: network 80 tests (6 properties), promise 119 tests (8 properties), all green.

### Next steps

- Publish `StoreStatusChangedV1` from `StoreService` through the outbox and refresh on it, so other instances apply a
  pause within milliseconds instead of within the refresh interval.
- Replace the status-based load with live picker and rider queues, and learn the buffer per store from late deliveries
  (wave 3); both are ports already.
- Traffic by time of day in `TravelTimeModel`; polygon service areas (H3 can index polygons, the snapshot survives).
- Measure the promise path at 1,000 requests per second (load-test wave); if boxing in the cell map shows up, move to
  a primitive long map.
