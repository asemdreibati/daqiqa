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
4. Among candidates that can fulfil the **whole** basket, choose the smallest ETA, compared to the second (ties:
   shorter distance, then store id). None can → `UNAVAILABLE / OUT_OF_STOCK`. *(Clarified after review: comparing
   whole minutes let a store with up to 59 s less slack win.)*
5. `tier = DeliveryTier.forEta(etaMinutes)`; none → `UNAVAILABLE / NO_CAPACITY`.
6. **Suggestions** (`REMOVE_ITEMS`): for each set of SKUs some candidate lacks (never the whole basket), promise the rest
   of the basket again against **every** candidate, whole-basket stores included. Suggest the winner if it lacks
   exactly those SKUs and beats the offer (a better tier or fewer minutes; any tier when there is no offer), reporting
   the winner's store, ETA and tier. At most two, fastest first. *(Corrected after review: comparing the lacking store's
   ETA for the smaller basket with the full-basket offer made a smaller pick alone look faster.)*
7. Sign the offer (below) and record metrics.

Put steps 3–6 in a pure domain class (e.g. `PromiseCalculator`) that receives already-fetched data, so it is fully
unit- and property-testable (e.g. "the chosen store can always fulfil the basket", "adding travel time never lowers
the ETA", "suggestions never contain SKUs that are in stock at that store").

### Token (ADR-0007)

- Format: `v1.<kid>.<payload>.<signature>`, both base64url without padding.
- Payload: `promiseId|storeId|tier|etaMinutes|issuedAtEpochSecond|expiresAtEpochSecond|basketHash|latE6|lngE6`, where
  `latE6`/`lngE6` are the location the promise was computed for, as integer micro-degrees (`Math.round(lat × 10⁶)`).
  *(Amended after review with the `PromiseTokenVerifier` contract change.)*
- `basketHash`: SHA-256 over the merged basket sorted by SKU, formatted `SKU:qty` joined with `,`, base64url.
- Signature: HMAC-SHA256 over `v1.<kid>.<payload>` with the key named `kid`.
- Configuration: `daqiqa.promise.token.keys.<kid>` (base64, at least 32 bytes), `daqiqa.promise.token.active-key-id`,
  `daqiqa.promise.token.validity` (default 90 s), `daqiqa.promise.token.location-tolerance` (metres, default 100). Fail
  fast at startup if the active key is missing or too short.
- Verification (`PromiseTokenVerifier.verify(token, lines, deliveryLocation)`): format → `MALFORMED`; unknown kid →
  `MALFORMED`; signature mismatch (constant-time `MessageDigest.isEqual`) → `TAMPERED`; claims we never issue (valid
  for longer than the validity, or issued more than 30 s in the future) → `MALFORMED`; `now ≥ expiresAt` →
  `EXPIRED`; basket hash differs → `CART_MISMATCH`; delivery location farther than the tolerance from the promised one
  → `LOCATION_MISMATCH`; promised store no longer accepts orders → `STORE_UNAVAILABLE`. *(Issuance checks amended
  after the principal review.)*

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

## Implementation notes (wave 1, revised after review)

### What was built

| Module | Package | Key classes |
|---|---|---|
| network | `domain` | `Store` (invariants: radius 500..10,000 m, pickers ≥ 0; a row `version`), `StoreRepository` (port), `TravelTimeModel`, `CellGrid` (port), `NetworkSnapshot` (immutable, versioned coverage index), `StoreReach` |
| | `application` | `NetworkView` (implements `CoverageIndex` and `StoreDirectory` from an `AtomicReference<NetworkSnapshot>`), `StoreService` (list, get, change status), `StoreStatusChanged` (application event carrying the committed row) |
| | `adapter` | `JdbcStoreRepository`, `H3CellGrid` (resolution 9), `StoreController` + `NetworkProblemHandler` |
| | `config` | `NetworkConfiguration`, `NetworkProperties` (`daqiqa.network.*`) |
| promise | `domain` | `Basket`, `Candidate`, `Stock`, `StoreLoad` + `StoreLoadProvider` (port), `PromiseCalibration` (port), `PickTimeModel`, `EtaBreakdown`, `PromiseCalculator`, `PromiseDecision`, `Quote`, `Suggestion`, `UnavailableReason` |
| | `application` | `PromiseService`, `PromiseTokenCodec` (token format and cryptography), `CheckoutPromiseVerifier` (the `PromiseTokenVerifier`), `SigningKeys`, `StatusBasedStoreLoad` (v1 `StoreLoadProvider`), `PromiseMetrics` |
| | `adapter`, `config` | `PromiseController` + `PromiseProblemHandler`; `PromiseConfiguration`, `PromiseProperties` (`daqiqa.promise.*`) |

Schema: `network.dark_store` (`V1_1__network_schema.sql`). The promise context stores nothing.

### Decisions and deviations

**Coverage cells are a superset (spec corrected).** A cell can contain points of the service circle while its centre is
outside it, so `H3CellGrid.cellsCovering` keeps every cell whose centre is within `radius + cellRadius`, and the lookup
applies the exact rules (accepts orders, great-circle distance ≤ radius). The answer is exact: no store beyond its
radius, none missing. The margins come from H3 geometry at resolution 9 (average edge 200.8 m), measured over hundreds
of thousands of cells and thousands of ring origins: the centre-to-vertex distance is at most 1.03 × the average edge
in the Gulf and 1.10 × anywhere on Earth (we use 1.5 ×); each ring of a grid disk reaches at least 1.29 × further in the
Gulf and 1.06 × anywhere (we assume 0.75 ×). Measured in Dubai: a 500 m store indexes 20 cells, a 3 km store 358 cells
(1.4 ms to compute) and a 10 km store 3,500 (8 ms).

**One immutable snapshot, statuses included.** The cell index covers every store whatever its status, so a snapshot
built after a status change reuses the previous cell index (only footprints, i.e. id, location and radius, decide whether
it is rebuilt). A pause is therefore an O(stores) snapshot, not a re-indexing. The index maps a cell to the positions of
its stores in the snapshot's id-ordered store list, so a lookup does no hashing per candidate. Readers take the current
snapshot with one volatile read and never lock; they only ever see the old or the new network.

**Status changes are applied in memory, in version order (changed after review).** The first version reloaded the
network from the database in the after-commit callback. That held the request's connection while it waited on a
lock, and a failure was only logged. Now `dark_store.version` is incremented by every change, and
`UPDATE … RETURNING` hands the committed row to `StoreStatusChanged`. `NetworkView` applies it after commit
(`@TransactionalEventListener(fallbackExecution = true)`, before the use case returns) as a pure function of the
current snapshot: no I/O, no lock, nothing that can fail, and a rolled-back change never becomes visible. A snapshot only
ever replaces a store with a newer version of it, so late callbacks of concurrent transactions cannot move a store back.
The database reload remains, as reconciliation: when the bean starts (`InitializingBean`, before the application
serves requests; `JdbcClient` beans are ordered after Flyway) and every `daqiqa.network.coverage.refresh-interval`
(30 s) when the application enables scheduling (a `SchedulingConfigurer` bean). It drops stores the database no longer
lists, adds new ones and keeps whichever version is newer, so a reload that read the database just before a commit
cannot undo it. With versions, neither path needs a lock. Other instances see a change within the refresh interval,
until status changes are published as `StoreStatusChangedV1`.

**`StoreDirectory` answers from memory; the store endpoints read the database.** The promise engine and the checkout
verifier need a store's status without a round trip. Operators, on the other hand, should see exactly what was
committed, so `GET /v1/stores` reads the system of record.

**Domain types.** Each domain uses its own context's published enums (`StoreStatus`, `DeliveryTier`) rather than a
copy, so "which statuses accept orders" and the tier limits have a single source of truth (the layer rules allow it).
Other contexts' types never reach a domain: `PromiseService` translates `StoreCoverage` into `Candidate` and
`StockSnapshot` into `Stock` (a method reference, no copy).

**Offers are ranked to the second (changed after review).** Among stores able to supply the whole basket, the smallest
total in seconds wins, then the nearer store, then the smaller id. Comparing whole minutes let a store with up to 59 s
less slack win.

**Rounding.** Every breakdown part is rounded up to the second and the ETA is the sum rounded up to the minute, with a
minimum of one minute. Rounding never makes a promise optimistic, and the parts shown always add up to the ETA shown.

**Suggestions re-promise the rest of the basket (changed after review).** The first version compared a lacking
store's ETA for the smaller basket with the full-basket offer. A smaller pick alone could then look faster: with 1 milk
and 60 waters, it suggested "drop the water, 17 min from a farther, busy store", although without the water the nearby
store delivers in 10. Now, for every set of items some store lacks (never the whole basket), the rest of the basket is
promised again against every candidate, whole-basket stores included. The winner is suggested only if:

- it lacks exactly those items. This is a refinement of the review's "cannot supply the full basket" rule, which alone
  would still suggest dropping items the suggested store has, only to shorten the pick;
- it fits a tier;
- it beats the offer by at least one minute. The spec's "strictly better tier or ETA" amounts to that: a faster tier
  always means fewer minutes, and seconds within the same minute change nothing the customer sees.

A partly stocked line counts as missing: `REMOVE_ITEMS` carries SKUs, not quantities. A suggestion is left out when
another drops no more items and is at least as fast. Order: minutes, then fewer items dropped, then the offer order,
then the SKUs, which makes the result independent of the candidates' order. By construction, promising the basket minus
a suggestion's SKUs yields exactly its store, ETA and tier (a property test).

**Store load, v1.** `OPEN` stores get the open waits. `BUSY` stores get the busy waits, and so, conservatively, does any
store that stopped accepting orders since the coverage lookup or that the directory does not know.

**Tokens (location and store status added after review).** `PromiseTokenCodec` owns the format and the cryptography:
it issues tokens and authenticates them, failing only with `MALFORMED` or `TAMPERED` (a fuzz property shows nothing
else escapes). The payload now signs the location the promise was computed for, as integer micro-degrees (about 11 cm;
integers have one text form on every JVM and in every locale). The signature is compared as its canonical base64url text
with `MessageDigest.isEqual` (constant time), so no timing side channel leaks how much of a forged signature was right,
and alternative encodings of the same bytes are rejected. Nothing is decoded before the signature is checked; tokens
over 1,024 characters are rejected up front. Issue and expiry times are whole epoch seconds (the response's
`expiresAt` is exactly the token's); the validity must be between 1 s and 1 h. Every configured key, not only the
active one, must decode to at least 32 bytes, because a weak verify-only key would still let anyone forge tokens under
its id. Key ids are 1 to 32 characters of `[A-Za-z0-9_-]`. The token format is still `v1`: no token was ever issued in
the earlier layout.

`CheckoutPromiseVerifier`, the `PromiseTokenVerifier` bean, checks in this order:

1. format and key id (`MALFORMED`), then signature (`TAMPERED`);
2. claims we issue (`MALFORMED` when the token is valid for longer than `validity`, or expires no later than it is
   issued, or was issued more than 30 s ahead of the injected clock);
3. expiry (`EXPIRED`, `now ≥ expiresAt`);
4. basket (`CART_MISMATCH`; this also covers an empty or otherwise impossible basket);
5. location (`LOCATION_MISMATCH` when the delivery point is farther than `location-tolerance` from the promised one,
   default 100 m: enough for geocoding and GPS noise, not for a change of address);
6. store status (`STORE_UNAVAILABLE` when the network's in-memory view says the store no longer accepts orders; `BUSY`
   still does).

The status check is immediate for changes made through this instance and up to the refresh interval (30 s by default)
late for changes made through another. Exception messages give distances, never coordinates, because they end up in
logs.

**Claims we never issue are refused (added after the principal review).** A valid signature proves that a key we trust
signed the token, not that we issued it: whoever holds a key can sign any claims. The server's default configuration
used to carry the published development key, and the reviewer signed a one-minute ETA valid for 24 hours with it;
checkout accepted it. Besides removing that key from every configuration but the `dev` profile (the server now refuses
to start with it otherwise), the verifier refuses, as `MALFORMED` ("not a token we issued"), any token whose lifetime
(`expiresAt − issuedAt`) is not between 1 s and the configured validity, and any token issued more than 30 s after
now on the injected clock. Every token we issue is valid for exactly the validity from its issue time, and the
instances' clocks agree far more closely than 30 s, so no genuine token is refused; a forged one can no longer outlive
the validity window, nor be dated into the future to stretch it. These checks run right after the signature, before
expiry, so a forgery is reported as one even when it has also expired. Issue and expiry times outside `Instant`'s range
fail to decode (`MALFORMED`) instead of escaping as an exception. Lowering `validity` makes tokens issued under the old
value fail during the rollout (for at most one old validity window); raising it does not.

**Validation.** Store endpoints validate the path id against the contract's pattern; Spring then validates the whole
method, so body errors on `PUT .../status` are reported the same way (`VALIDATION_FAILED`, 400). Validation problems
carry an `errors` array of `{field, message}`. An unreadable body is `INVALID_REQUEST` and names the offending JSON path
when there is one. A basket that breaks a rule the schema cannot express (more than 99 units of a SKU once duplicate
lines are merged) is `INVALID_REQUEST`. A malformed store id on `GET /v1/stores/{storeId}` is a 400 although the
contract lists only 200 and 404 there, as the catalog does for SKUs.

**Schema.** `id text COLLATE "C"`: listings are in byte order whatever the database locale, the same order the
in-memory directory uses. New stores default to `CLOSED`. `version` starts at 0. `CHECK` constraints mirror every
domain invariant.

### Configuration

| Property | Default | Meaning |
|---|---|---|
| `daqiqa.network.travel.road-factor` | `1.3` | road distance per metre of straight line (≥ 1) |
| `daqiqa.network.travel.speed-meters-per-second` | `7` | average riding speed (≈ 25 km/h) |
| `daqiqa.network.travel.handoff` | `60s` | parking, finding the door, handing over |
| `daqiqa.network.coverage.refresh-interval` | `30s` | reconciliation with the database (when scheduling is enabled) |
| `daqiqa.promise.pick.base` / `per-unit` / `max` | `45s` / `8s` / `600s` | picking and packing time |
| `daqiqa.promise.load.open.queue` / `rider-wait` | `60s` / `60s` | waits at an open store |
| `daqiqa.promise.load.busy.queue` / `rider-wait` | `300s` / `180s` | waits at a busy store |
| `daqiqa.promise.buffer` | `90s` | safety margin on every ETA |
| `daqiqa.promise.token.active-key-id` | **required** | key that signs new tokens |
| `daqiqa.promise.token.keys.<id>` | **required** | base64 keys (≥ 32 bytes each, e.g. `openssl rand -base64 32`); non-active keys only verify |
| `daqiqa.promise.token.validity` | `90s` | how long a promise can be turned into an order (1 s to 1 h) |
| `daqiqa.promise.token.location-tolerance` | `100` | metres the delivery point may be from the promised one (positive) |

This module ships no signing key. The server application must configure `daqiqa.promise.token.*`; the architect is
adding a dev key, overridable by environment variables, at integration. Without a key the application refuses to
start: *Failed to bind properties under 'daqiqa.promise' … No promise token signing key is configured: set
daqiqa.promise.token.active-key-id=&lt;id&gt; and daqiqa.promise.token.keys.&lt;id&gt;=&lt;base64 key of at least 32
bytes&gt;*. Rotation: add the new key everywhere, make it active, remove the old one once its tokens have expired.

### Metrics

| Meter | Type | Tags | Notes |
|---|---|---|---|
| `daqiqa.promise.requests` | timer | `status` (AVAILABLE, UNAVAILABLE), `tier`, `reason` (`none` when not applicable) | percentile histogram plus a 25 ms bucket (the p99 target); all six series registered at startup; failed requests are in `http.server.requests` |
| `daqiqa.promise.eta` | distribution summary (minutes) | | promised ETAs, percentile histogram |
| `daqiqa.network.stores` | gauge | `status` | stores by status as this instance sees them |
| `daqiqa.network.coverage.cells` | gauge | | cells covered by at least one store |
| `daqiqa.network.refresh` | timer | | time to reload the network and reconcile the snapshot |

### How the tests prove the guarantees

| Guarantee | Test |
|---|---|
| A point is served by exactly the stores that accept orders and whose radius contains it, fastest first | `CoverageIndexPropertiesTest` (jqwik: 1,000 points across Dubai, 1,000 at the edge of service areas, 100 random networks × 25 points) |
| Cell covering never misses a cell the circle touches | `H3CellGridTest` (jqwik: circles in the Gulf and anywhere on Earth, 360 edge points and 200 inside each) |
| Exact distance, status filter, ordering and tie-breaks | `NetworkSnapshotTest` (a one-cell grid, so only the exact rules decide), `CoverageIndexTest` |
| A committed change reaches the index at once without reading the database; a rolled-back one never does; late or stale updates never move a store back; the index is built at startup | `CoverageIndexTest`, `NetworkSnapshotTest`, `StoreStatusChangeIntegrationTest` (Spring and PostgreSQL) |
| Readers never see a half-built network; concurrent changes and reloads converge on the database | `NetworkViewConcurrencyTest` (start latch, readers reading before the first change), `StoreStatusChangeIntegrationTest` (6 threads calling `changeStatus` on one store against the periodic reload, on PostgreSQL; it fails when the version guard is removed) |
| Travel model: defaults, rounding up, monotonic | `TravelTimeModelTest` (unit and property) |
| SQL, constraints, versions, byte-order listing, `updated_at` from the clock | `JdbcStoreRepositoryTest` (PostgreSQL) |
| Every calculator branch with worked numbers, including the review's milk-and-water case | `PromiseCalculatorTest` (24 cases) |
| Chosen store can supply the basket; none faster to the second could; ETA = breakdown rounded up, in its tier; a suggestion drops exactly what its store lacks and never everything; promising the rest gives exactly the suggestion; at most two, fastest first, faster than the offer; order of candidates irrelevant; right unavailable reason; more travel never makes a promise sooner | `PromiseCalculatorPropertiesTest` (jqwik, 1,000 scenarios each, big baskets included; statistics coverage checks fail the build if the generator stops producing an outcome, capped picks or pick-dominated ETAs) |
| Token format (checked against an independent HMAC implementation), tampering, other secret, unknown kid, malformed input, rotation | `PromiseTokenCodecTest`, `SigningKeysTest`, `BasketHashTest` |
| Expiry boundary, cart mismatch including merged duplicates, location tolerance at ±1 cm of the boundary, store paused, closed or unknown, busy accepted, check order; correctly signed tokens we never issue (valid a day, a second longer than the validity, expiring before issue, issued 31 s ahead) refused, one second inside each limit accepted | `CheckoutPromiseVerifierTest` |
| The location round-trips to within 11 cm anywhere on Earth; random or mutated tokens only ever fail as `MALFORMED` or `TAMPERED` | `PromiseTokenLocationTest`, `PromiseTokenFuzzTest` (jqwik) |
| One availability round trip for all candidates and SKUs, none without candidates; busy stores; merged duplicates; metrics | `PromiseServiceTest` (fakes for coverage, stock and the directory) |
| Fail fast on a missing, short, non-base64 or unconfigured key and a non-positive tolerance; defaults; overrides | `PromiseConfigurationTest` |
| Wire contract and problem codes | `StoreControllerTest`, `PromiseControllerTest` (`@WebMvcTest`) |
| A promise made over HTTP is honoured by the `PromiseTokenVerifier` bean checkout uses | `PromiseModuleTest` |
| Layering | `ArchitectureTest` in both modules |

Totals: network 89 tests (6 properties), promise 148 tests (13 properties), all green.

### Next steps

- Publish `StoreStatusChangedV1` from `StoreService` through the outbox and apply it on every instance, so a pause
  reaches all instances (and the checkout status check) within milliseconds instead of the refresh interval.
- Replace the status-based load with live picker and rider queues, and learn the buffer per store from late deliveries
  (wave 3); both are ports already.
- Traffic by time of day in `TravelTimeModel`; polygon service areas (H3 can index polygons, the snapshot survives).
- Measure the promise path at 1,000 requests per second (load-test wave); if boxing in the cell map shows up, move to
  a primitive long map.
