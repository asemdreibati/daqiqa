# Inventory — design specification (wave 1)

> Owner: inventory engineer. Status: specification; add "Implementation notes" at the end as you build.
> Related: ADR-0005 (never oversell), ADR-0004 (SQL), ADR-0009 (events), `modules/inventory/.../api`.

## Responsibilities

1. Know how many units of each SKU every dark store has **on hand**, and how many are **reserved** for orders.
2. Answer "what can be sold where?" fast (`AvailabilityQuery`), for promises.
3. Hold stock for orders so that **a store never has more units reserved than on hand**, whatever the concurrency
   (`InventoryReservations`).
4. Return held stock to sale when an order fails, is cancelled, or its hold expires.
5. Record stock changes from store operations (deliveries, damage, counts) with an audit trail.

## Model

**Stock level** — per `(storeId, sku)`: `onHand ≥ 0`, `reserved ≥ 0`, `reserved ≤ onHand`,
`available = onHand − reserved`.

**Reservation** — `id` (UUIDv7), `storeId`, caller `reference` (e.g. the order id), lines `(sku, quantity)` with
distinct SKUs, `status`, `expiresAt`.

```
            confirm()                release()
   HELD ─────────────────▶ CONFIRMED ───────────▶ RELEASED
    │  \                                             ▲
    │   \ release()                                  │
    │    └───────────────────────────────────────────┘
    │ expiresAt passed (sweeper, or checked on confirm)
    ▼
  EXPIRED
```

- `confirm` on `HELD` whose `expiresAt` has passed must expire it (return the stock) and throw
  `ReservationNoLongerHeldException` — a late confirm must never succeed just because the sweeper has not run yet.
- `confirm` on `CONFIRMED` is a no-op; on `RELEASED`/`EXPIRED` it throws `ReservationNoLongerHeldException`.
- `release` on `HELD` or `CONFIRMED` returns the stock; on `RELEASED`/`EXPIRED` it is a no-op.
- Wave 2 adds `COMMITTED` (picked: `onHand` and `reserved` both decrease).

Put the state machine and the shortage computation in the **domain** (plain Java), unit-tested and property-tested.

## Schema (suggested; you own it)

`V3_1__inventory_schema.sql` in `db/migration/inventory/`:

- `inventory.stock_level(store_id text, sku text, on_hand int, reserved int, version bigint, updated_at timestamptz)`,
  primary key `(store_id, sku)`, `CHECK (on_hand >= 0)`, `CHECK (reserved >= 0)`, `CHECK (reserved <= on_hand)` — the
  last line of defence against overselling.
- `inventory.reservation(id uuid pk, store_id, reference, status, expires_at, release_reason, created_at, updated_at)`
  with a **partial unique index** on `reference WHERE status IN ('HELD','CONFIRMED')` (idempotency by reference,
  enforced by the database) and a partial index on `expires_at WHERE status = 'HELD'` (the sweeper's scan).
- `inventory.reservation_line(reservation_id uuid references inventory.reservation, sku, quantity > 0)`,
  primary key `(reservation_id, sku)`.
- `inventory.stock_adjustment(id uuid pk, store_id, sku, delta, reason, on_hand_after, created_at)`: append-only
  audit trail.

## Algorithms

### Reserve (joins the caller's transaction)

1. If an active (`HELD`/`CONFIRMED`) reservation exists for `reference`, return it (`Reserved`), writing nothing.
2. Lock the basket's rows **in SKU order**:
   `SELECT sku, on_hand, reserved FROM inventory.stock_level WHERE store_id = :store AND sku = ANY(:skus) ORDER BY sku FOR UPDATE`.
   Missing rows mean zero stock. Consistent lock order means two baskets sharing items cannot deadlock.
3. Compute shortages from the locked rows (domain code). If any: return `Rejected(shortages)` having written nothing.
4. Otherwise increment `reserved` for each line (a single statement, e.g. `UPDATE ... FROM unnest(:skus, :qtys)`, with
   `AND on_hand - reserved >= qty` as defence in depth; assert the updated row count), insert the reservation and its
   lines, `expiresAt = clock.instant() + holdFor`.
5. Two concurrent first-time calls with the same reference: the partial unique index makes one of them fail; handle it
   (e.g. `INSERT ... ON CONFLICT DO NOTHING` on the reservation *before* touching stock, or catch the violation and
   return the winner's reservation). Document what you chose.

### Expiry sweeper

Scheduled (`daqiqa.inventory.expiry.interval`, default 5 s). In a transaction, take a batch
(`daqiqa.inventory.expiry.batch-size`, default 100):
`SELECT id FROM inventory.reservation WHERE status = 'HELD' AND expires_at <= :now ORDER BY expires_at LIMIT :n FOR UPDATE SKIP LOCKED`,
return each one's stock (lines in SKU order), mark `EXPIRED`, and append `ReservationExpiredV1` to the outbox. Several
instances can sweep concurrently without double-releasing. Expose the sweep as a method tests can call directly.

### Availability (hot path)

`SELECT store_id, sku, on_hand - reserved FROM inventory.stock_level WHERE store_id = ANY(:stores) AND sku = ANY(:skus)`:
one round trip for all candidate stores, served by the primary key. (A cached read model can come later; measure
first.)

### Stock adjustment

`adjust(storeId, sku, delta, reason)`: the store must exist (`StoreDirectory` from network's API → 404
`STORE_NOT_FOUND`), the SKU must exist (`ProductCatalog` → 422 `UNKNOWN_SKU`). Upsert the level
(`INSERT ... ON CONFLICT (store_id, sku) DO UPDATE`), refusing the change if it would leave `on_hand < reserved` (409
`STOCK_BELOW_RESERVED`) or `on_hand < 0`. Insert an audit row and append `StockAdjustedV1`, all in one transaction.

## HTTP (contract: `api/openapi/daqiqa-v1.yaml`)

- `GET /v1/stores/{storeId}/stock?sku=A&sku=B` → `StockLevels` (zeros for SKUs not stocked; 1–100 SKUs; 404 for an
  unknown store).
- `POST /v1/stores/{storeId}/stock/adjustments` → `StockLevel`.

## Events

- `StockAdjustedV1` on every adjustment. `ReservationExpiredV1` on every expiry. Both through `OutboxWriter`, inside
  the transaction.

## Metrics (Micrometer)

`daqiqa.inventory.reservations` (counter, tag `outcome=reserved|rejected|replayed`), `daqiqa.inventory.releases`
(tag `reason`), `daqiqa.inventory.expirations`, `daqiqa.inventory.reserve` (timer).

## Required tests

- Domain: state machine transitions (property test: no sequence of operations breaks `0 ≤ reserved ≤ onHand`),
  shortage computation.
- Persistence, against real PostgreSQL: reserve / idempotent reference / confirm / confirm-after-expiry / release /
  sweeper / adjustments / `STOCK_BELOW_RESERVED`.
- **Concurrency** (the headline tests):
  1. 200 threads each reserve 1 unit of a SKU with `onHand = 50` → exactly 50 `Reserved`, 150 `Rejected`,
     `reserved = 50` afterwards, no exceptions.
  2. Many threads reserving multi-line baskets that share SKUs in opposite orders (`[A,B]` and `[B,A]`) → no deadlock
     errors, invariants hold, total reserved equals the sum of successful reservations.
  3. Concurrent reserve/release/sweep mix → invariants hold at the end.
- Web slice tests for both endpoints (status codes, problem codes, JSON shape).
- `ArchitectureTest`.

## Implementation notes (wave 1)

> Built on branch `wave1/inventory`. The specification above is unchanged; where the code differs, this section says
> so and why. One correction to the specification is flagged below (expiry batches).

### What was built

| Layer | Package (`io.daqiqa.inventory.*`) | Main types |
|---|---|---|
| Domain (plain Java) | `domain` | `StockLevel` (`0 ≤ reserved ≤ onHand`, `adjust`), `StoreStock` (a basket's shortfalls), `Reservation` + `ReservationStatus` (immutable state machine returning `ConfirmOutcome` / `ReleaseOutcome`), `HeldUnits`, `StockAdjustment`, `AdjustmentReason`; ports `StockLevelRepository`, `ReservationRepository`, `StockAdjustmentLog` |
| Application | `application` | `ReservationService` (implements `InventoryReservations`), `ReservationExpirySweeper`, `AvailabilityService` (implements `AvailabilityQuery`), `StockService` (levels and adjustments), `InventoryMetrics` |
| Persistence | `adapter.out.persistence` | `JdbcStockLevelRepository`, `JdbcReservationRepository`, `JdbcStockAdjustmentLog` (`JdbcClient`, arrays bound with `ANY` / `unnest`) |
| HTTP | `adapter.in.web` | `StockController`, `StockProblemHandler`, `StockMessages` (DTOs), `@NonZero` |
| Wiring | `config` | `InventoryProperties`, `InventoryConfiguration` (sweeper bean and its schedule) |
| Schema | `db/migration/inventory/V3_1__inventory_schema.sql` | `stock_level`, `reservation`, `reservation_line`, `stock_adjustment` |

The domain decides; the database applies the decision with guarded statements and re-checks it with constraints.

### How each guarantee is kept

**Never oversell (three independent layers).** (1) The domain computes shortfalls from rows locked with
`... ORDER BY sku FOR UPDATE`. (2) The reserve statement re-checks every line (`AND on_hand - reserved >= quantity`)
and its row count is asserted: a line it refuses throws and rolls the transaction back, so even a caller that skipped
the lock could not oversell. (3) `CHECK (reserved <= on_hand)` refuses the write if both were wrong.

**No deadlocks.** One lock order for the module. Reserving locks only stock rows, sorted, before inserting its new
reservation. Confirming, releasing and expiring lock the reservation row first and its stock rows second. Every
statement that locks several stock rows sorts them by store, then SKU. `EXPLAIN` shows `LockRows` above the `Sort`,
so rows are locked in sorted order. Returning stock locks first (`SELECT ... ORDER BY store_id, sku FOR UPDATE`) and
updates second, because an `UPDATE ... FROM unnest(...)` locks rows in whatever order its plan visits them. Store ids
and SKUs are `COLLATE "C"`: the sort is byte order on every server, and an operating-system collation upgrade cannot
reorder (and so corrupt) the indexes.

**All or nothing, and nothing written on `Rejected`.** A rejection only took row locks, which end with the
transaction. The multi-line increment is one statement with an asserted row count.

**Idempotent by reference, including two simultaneous first calls.** Step 1 (the fast path) returns an active
reservation before taking any lock. Two first calls can both miss it. Each case is handled:

- *Their baskets share stock rows.* The second call waits for the lock, then sees the first call's units as
  reserved: a shortfall that is really its own reservation. So a shortfall looks the reference up again before
  rejecting, and returns the reservation if it finds one.
- *Their baskets share no rows.* The reservation insert is `INSERT ... ON CONFLICT (reference) WHERE status IN
  ('HELD','CONFIRMED') DO NOTHING`. PostgreSQL makes the second insert wait on the partial unique index until the first
  transaction ends. If the first commits, the second call returns the first call's reservation. If it rolls back, the
  second insert goes ahead.

Why `ON CONFLICT` rather than catching the unique violation: in PostgreSQL an error aborts the whole transaction, and
`reserve` runs in the caller's transaction. If the winning reservation has already ended by the time it is read back
(a caller bug on top of a race), the call fails with `IllegalStateException` so the caller retries.

**Joins the caller's transaction.** All three methods use propagation `REQUIRED`. Tests show that a caller's rollback
undoes a reservation, and that other connections do not see it before the caller commits.

**A late confirm never succeeds.** Expired means `now ≥ expiresAt`, the same boundary the sweeper's
`expires_at <= :now` uses. `confirm` on such a hold returns the stock, marks it `EXPIRED`, appends
`ReservationExpiredV1` and throws `ReservationNoLongerHeldException`. The method is annotated
`@Transactional(noRollbackFor = ReservationNoLongerHeldException.class)`. Without that, the exception would mark
ordering's transaction rollback-only: ordering catches the exception to abandon its order, and its commit would then
fail with `UnexpectedRollbackException` and undo the expiry. A test runs exactly that caller pattern.

**Holds expire exactly once.** The sweeper takes batches with `FOR UPDATE SKIP LOCKED` (a CTE so that `LIMIT` counts
reservations, not lines) through the partial index on `expires_at WHERE status = 'HELD'`. Each batch is its own
transaction. Concurrent sweepers take disjoint batches and never wait for each other. A hold that is being confirmed
right now is skipped, not double-handled.

**Adjustments.** The store is checked first (`StoreDirectory`, 404), then the SKU (`ProductCatalog`, 422). Then the
level row is created if missing (`INSERT ... ON CONFLICT DO NOTHING`), so there is always a row to lock, and locked.
The domain refuses a result below `reserved` (409). The change is applied with a guarded update. The audit entry and
`StockAdjustedV1` are appended in the same transaction. A refused change writes nothing.

### Decisions and deviations

1. **Expiry batches return their stock together, in (store, SKU) order. This corrects the specification.** The
   specification says to "return each one's stock (lines in SKU order)". Within one batch transaction, that order is
   only per reservation. Reservation 1 could lock `B` and reservation 2 then `A`, which can deadlock with a basket
   `[A, B]`. The sweeper sums the batch's lines per (store, SKU) (`HeldUnits.heldBy`) and returns them with one locked,
   sorted pair of statements.
2. **A late confirm also appends `ReservationExpiredV1`** (the specification says every expiry is announced). It is
   counted under `trigger=late_confirm`.
3. **Scheduling uses a `SchedulingConfigurer` bean instead of `@Scheduled`.** The behaviour is the same (fixed delay
   `daqiqa.inventory.expiry.interval`), but the interval comes from the bound `InventoryProperties`, so the default
   lives in one place. The task registers only where `@EnableScheduling` is on (the server), and tests call
   `sweep()` directly. Each run drains every hold elapsed at its start, `batch-size` at a time, and always ends.
4. **Adjustments lock, then let the domain decide (`StockLevel.adjust`), instead of using one upsert statement.** The
   rule is then unit- and property-tested in one place. Two first deliveries of a SKU queue on the same row, and a
   test checks that 20 of them all count. An adjustment that would leave a negative quantity on hand is also
   `STOCK_BELOW_RESERVED`: since `reserved ≥ 0`, on hand below zero is on hand below reserved. The detail message
   gives the numbers.
5. **`StockAdjustedV1.eventId` is the audit entry's id**: one fact, one id.
6. **Times are truncated to microseconds** (`Timestamps`), the precision PostgreSQL stores. A replayed reservation
   therefore reports exactly the `expiresAt` the first call reported.
7. **`AvailabilityQuery` does not open a transaction.** A single statement is consistent on its own, and the hot path
   saves the `COMMIT` round trip. It still joins a caller's transaction if there is one.
8. **Unknown reservation ids** in `confirm` / `release` throw `IllegalArgumentException`: the API has no "not found"
   outcome, and an id `reserve` never returned is a caller bug.
9. **A replay returns the existing reservation unchanged even if the basket differs**, as the API contract says.
   Ordering's idempotency key (with its request hash) catches key reuse upstream.
10. **`release` appends no event.** Only expiries and adjustments are announced. The reason is stored in
    `release_reason` and counted.
11. **Schema additions:** CHECKs on status and reason values; `reservation_reason_iff_ended` (a reason exactly when
    the reservation ended); an append-only trigger on `stock_adjustment`; the index `(store_id, sku, created_at DESC)`
    for audit look-ups.
12. **HTTP details.** `GET` removes repeated SKUs and keeps first-occurrence order. `VALIDATION_FAILED` covers bean
    validation, a missing `sku` and more than 100 SKUs. `INVALID_REQUEST` covers unreadable JSON, unknown enum values
    and malformed store ids or SKUs (rejected by the value objects). `@NonZero` implements the contract's
    `not: {const: 0}`. The problem handler is scoped to the stock controller and ordered first, so it wins over the
    server's application-wide problem-details handler. The slice test enables that handler to prove it.

### Configuration

| Property | Default | Meaning |
|---|---|---|
| `daqiqa.inventory.expiry.interval` | `5s` | Pause between the end of one sweep and the start of the next |
| `daqiqa.inventory.expiry.batch-size` | `100` | Holds expired per transaction |

Invalid values (zero or negative) fail the startup. The module needs a `java.time.Clock` bean, an `OutboxWriter`, a
`StoreDirectory` and a `ProductCatalog` from the application around it.

### Metrics

| Meter | Type | Tags | Meaning |
|---|---|---|---|
| `daqiqa.inventory.reservations` | counter | `outcome` = `reserved` \| `rejected` \| `replayed` | Reservation attempts |
| `daqiqa.inventory.releases` | counter | `reason` = lower-case `ReleaseReason` | Reservations released on request |
| `daqiqa.inventory.expirations` | counter | `trigger` = `sweeper` \| `late_confirm` | Holds that expired unconfirmed (a tag added to the specification) |
| `daqiqa.inventory.reserve` | timer (percentile histogram) | | Deciding and applying a reservation, including waiting for row locks |

### Tests and what they prove

121 tests; `./gradlew :modules:inventory:build` is green. Every PostgreSQL test runs against a real server.

| Kind | Tests | Proves |
|---|---|---|
| Domain unit | 34 | Invariants of `StockLevel`, shortfalls, every state-machine transition including the expiry boundary |
| Property (jqwik, 1,000 tries each) | 2 | Random sequences of reserve, confirm, release, sweep, clock and adjust keep `0 ≤ reserved ≤ onHand` and `reserved = Σ active holds`; a basket has no shortfall exactly when every line is covered |
| Application unit | 12 | The race-only branches of `reserve`, played deterministically; the sweeper's batching loop; guards |
| Persistence (PostgreSQL) | 24 | Every repository statement; locks proven with `lock_timeout` (SQLState 55P03) and `pg_stat_activity` instead of sleeps; `SKIP LOCKED`; the unique-index wait on commit and on rollback; CHECK constraints; the append-only trigger |
| Application integration (Spring + PostgreSQL) | 28 | Reserve, reject-writes-nothing, idempotency, a caller's rollback and visibility, confirm, late confirm (also inside a caller that catches and commits), release, sweeper, adjustments (404, 422, 409, audit entry, event), availability, metrics |
| Concurrency (PostgreSQL) | 6 | 200 threads × 1 of 50 units → exactly 50 held, 150 rejected, no exceptions; opposite-order baskets → no deadlock, reserved equals the successful baskets; a mix of every operation → the books balance; simultaneous first calls with one reference → one hold, on both race paths; 6 sweepers → each of 300 holds expired once |
| Web slice | 11 | Both endpoints: JSON shape, status codes, problem `type` and `code`, precedence over the global handler |
| Configuration | 3 | Defaults, binding, validation; the schedule, checked without starting a thread |
| Architecture | 1 | `ArchitectureRules.moduleRules("inventory")` |

The tests were checked against deliberate regressions: each of the following, when injected, makes at least one test
fail.

- Shortfalls ignore reserved units.
- An expired hold's stock is returned twice.
- Adjustments are allowed below reserved.
- The reference re-check after a shortfall is removed.
- Rows are locked one by one in basket order (PostgreSQL reports `deadlock detected`).
- Elapsed holds are taken without row locks.
- The problem handler loses its `@Order`.

### Notes for the other modules

- **ordering**
  - Call `reserve` inside T1.
  - Call `confirm` inside T2 and catch `ReservationNoLongerHeldException` there: the expiry commits with T2.
  - Release reservations one per transaction. For example, the abandoned-order sweeper should release each order in
    its own transaction: releasing several in one transaction locks their stock rows out of order and can deadlock
    with checkouts.
- **promise:** `AvailabilityQuery` costs one statement for all candidate stores × SKUs. Missing entries mean zero.
- **server:** Scheduling must be enabled for holds to expire in the background. `confirm` enforces expiry on its own
  either way.

### Known gaps, risks and next steps

- **Unbounded lock waits.** A transaction that holds a hot stock row and stalls blocks every checkout of that SKU,
  and each waiter holds a pooled connection. Next: `SET LOCAL lock_timeout` in `reserve` (or a pool-wide
  `statement_timeout`) and a retryable error.
- **Hot items.** One row per (store, SKU) serialises reservations of that item. Measure in the load-test wave. ADR-0005
  names the next step: a Redis pre-reservation layer in front of the same guarantee.
- **A poison batch stalls expiry.** If the books of some row are inconsistent (a manual edit, say), its batch fails
  every run. Next: on a batch failure, retry reservations one by one, park the offender and alert.
- **Same-reference reserve during that reference's release** can form a lock cycle. This needs a caller bug.
  PostgreSQL detects the cycle within `deadlock_timeout`, one call fails and can retry.
- **Growth.** Ended reservations are kept forever. Next: retention or archival, possibly partitioning by
  `created_at`.
- **Wave 2 and later:**
  - the `COMMITTED` status (picked: `onHand` and `reserved` both decrease);
  - sign rules per adjustment reason (`RECEIVED` > 0, `DAMAGED` < 0);
  - an endpoint to read the audit trail.
- **Repository hygiene.** The root `.gitignore` rule `out/` also ignores hexagonal `adapter/out/` packages. This module
  re-includes them with `modules/inventory/.gitignore`. The root fix is one line after `out/`: `!**/src/**/out/`.
