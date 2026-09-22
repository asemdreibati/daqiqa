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

- H3 resolution 9 (`com.uber:h3`). For each store accepting orders or not, precompute the cells whose centre lies
  within `serviceRadiusMeters` (grid disk around the store's cell, filtered by distance).
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
