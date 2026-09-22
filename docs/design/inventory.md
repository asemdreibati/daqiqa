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

> Built on branch `wave1/inventory` and revised after a staff-level review. The specification above is unchanged.
> Where the code differs from it, this section says so and why. One correction to the specification is flagged below
> (expiry batches).

### What was built

| Layer | Package (`io.daqiqa.inventory.*`) | Main types |
|---|---|---|
| API (the contract) | `api` | `InventoryReservations` (now documents the rules callers follow), `AvailabilityQuery`, results |
| Domain (plain Java) | `domain` | `StockLevel` (`0 ≤ reserved ≤ onHand`, `adjust`), `StoreStock` (a basket's shortfalls), `Reservation` + `ReservationStatus` + `EndReason` (immutable state machine whose changes are `Transition`s, returned inside `ConfirmOutcome` / `ReleaseOutcome`), `StockKey`, `HeldUnits`, `StockAdjustment`; ports `StockLevelRepository`, `ReservationRepository`, `StockAdjustmentLog` |
| Application | `application` | `ReservationService` (implements `InventoryReservations`), `ReservationExpirySweeper`, `AvailabilityService` (implements `AvailabilityQuery`), `StockService`, `InventoryMetrics`, `ExpiryBacklogMetrics` |
| Persistence | `adapter.out.persistence` | `JdbcStockLevelRepository`, `JdbcReservationRepository`, `JdbcStockAdjustmentLog` (`JdbcClient`; arrays bound with `ANY` / `unnest`) |
| HTTP | `adapter.in.web` | `StockController`, `StockProblemHandler`, `StockMessages` (DTOs), `Identifiers`, `@NonZero` |
| Wiring | `config` | `InventoryProperties`, `InventoryConfiguration` (sweeper bean and its schedule) |
| Schema | `db/migration/inventory/V3_1__inventory_schema.sql` | `stock_level`, `reservation`, `reservation_line`, `stock_adjustment` |

The domain decides; the database applies the decision with guarded statements and re-checks it with constraints.

### How each guarantee is kept

**Never oversell (three independent layers).** (1) The domain computes shortfalls from rows locked with
`... ORDER BY sku FOR UPDATE`. (2) The reserve statement re-checks every line (`AND on_hand - reserved >= quantity`)
and its row count is asserted: a line it refuses throws and rolls the transaction back, so even a caller that skipped
the lock could not oversell. (3) `CHECK (reserved <= on_hand)` refuses the write if both were wrong.

**No stock is returned twice.** Every change of a reservation's status is a `Transition` (from, to). The repository
applies it with `AND status = from_status` and asserts the row count. A release or expiry applied twice therefore
fails the whole transaction instead of lowering `reserved` a second time; no CHECK constraint could catch that, and
the next checkout would oversell.

**No deadlocks.** One lock order for the module. Reserving locks only stock rows, sorted, before inserting its new
reservation. Confirming, releasing, expiring and replaying a run-out hold lock the reservation row first and its
stock rows second. Every statement that locks several stock rows sorts them by store, then SKU. `EXPLAIN` shows
`LockRows` above the `Sort`, so rows are locked in sorted order. Returning stock locks first
(`SELECT ... ORDER BY store_id, sku FOR UPDATE`) and updates second, because an `UPDATE ... FROM unnest(...)` locks
rows in whatever order its plan visits them. Store ids and SKUs are `COLLATE "C"`: the sort is byte order on every
server, and an operating-system collation upgrade cannot reorder (and so corrupt) the indexes.

**Hot rows are locked briefly.** Ending reservations (a sweep batch, a late confirm, a release) runs in this order:

1. the guarded status update, on reservation rows the transaction already holds;
2. the events, appended to the outbox, an append-only table nobody else waits on;
3. the stock release, last.

The stock rows every checkout needs therefore stay locked only for the final statements before commit.

**All or nothing, and nothing written on `Rejected`.** A rejection only took row locks, which end with the
transaction. The multi-line increment is one statement with an asserted row count. There is one exception: a retry
whose reference's hold had run out also commits that hold's expiry, which the sweeper would have done anyway.

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

**A replay never reports a hold whose time has run out.** If the fast path finds a hold whose time ran out but which
the sweeper has not reached yet, the retry does the following:

1. locks the hold's reservation row;
2. locks the hold's stock rows and the new basket's rows together, in one sorted statement, across stores if
   necessary (locking them in two steps could deadlock with a concurrent basket);
3. expires the hold: `ReservationExpiredV1` is appended, and counted as `trigger=replay`;
4. reserves the basket afresh, or rejects it if the stock is now short.

A confirmed reservation is replayed however late.

**Joins the caller's transaction.** All three methods use propagation `REQUIRED`. Tests show that a caller's rollback
undoes a reservation, and that other connections do not see it before the caller commits. The rules callers must
follow are in the Javadoc of `InventoryReservations`:

- READ COMMITTED isolation;
- one basket reserved and one reservation released per transaction;
- keep the transaction short and reserve last, because locks are held until the caller commits, even after
  `Rejected`.

**A late confirm never succeeds.** Expired means `now ≥ expiresAt`, the same boundary the sweeper's
`expires_at <= :now` uses. `confirm` on such a hold expires it (status, event, stock) and throws
`ReservationNoLongerHeldException`. The method is annotated `@Transactional(noRollbackFor = ...)`. The exception
therefore does not mark the caller's transaction rollback-only: ordering can catch it, abandon its order and commit,
and the expiry commits with it. The API Javadoc states this; a test runs exactly that caller pattern.

**Holds expire exactly once, and one bad hold cannot stall the rest.** The sweeper takes batches with
`FOR UPDATE SKIP LOCKED` (a CTE, so that `LIMIT` counts reservations, not lines) through the partial index on
`expires_at WHERE status = 'HELD'`. Each batch is its own transaction. Concurrent sweepers take disjoint batches and
never wait for each other. The queue is ordered across all stores. So when a batch fails, the sweeper first reads what
kind of failure it was:

- **Contention** (a checkout holds a stock row of the batch): counted on `daqiqa.inventory.expiry.contention`, logged
  at INFO, and the batch is left to the next round, seconds away. Nothing is retried hold by hold.
- **Anything else** (one hold's stock rows are inconsistent, say): counted on
  `daqiqa.inventory.expiry.failures{scope=batch}`, then the same holds are retried one per transaction, each locked
  with `SKIP LOCKED`. A hold that still fails is counted (`scope=hold`), logged at ERROR and stays held; the healthy
  ones expire.

Contention is recognised by walking the cause chain for the SQL states `55P03` (lock timeout), `40P01` (deadlock) and
`40001` (serialization failure): Spring leaves a lock timeout as an `UncategorizedSQLException`, so the state is the
only reliable signal. The loop ends when a batch is not full, expires nothing, or ran into contention, so a sweep
always terminates. The gauge `daqiqa.inventory.expiry.oldest.overdue` reads the longest-overdue hold from the database
at each scrape. It keeps rising whatever stalls expiry, a sweeper that stopped running included.

**What the sweeper waits for.** ADR-0005 and the server's `application.yaml` say scheduled jobs "never wait". That is
true of the reservations a sweep picks (`FOR UPDATE SKIP LOCKED` skips the ones another transaction holds), but not of
their stock rows: returning stock waits for a checkout holding the same SKU. So every sweeping transaction bounds its
waits with `SET LOCAL lock_timeout` (`daqiqa.inventory.expiry.lock-timeout`, default 500 ms, well under the
application-wide 3 s). A sweep waits at most that long, reads the timeout as contention, and retries next round; it
never holds a scheduler thread or a pooled connection on a hot row. `SET LOCAL` ends with the transaction, so the
shorter timeout reaches nothing else on that connection. The ADR wording is the architect's to correct.

**Adjustments.** The steps, all in one transaction:

1. check the store (`StoreDirectory`, 404), then the SKU (`ProductCatalog`, 422);
2. create the level row if it is missing (`INSERT ... ON CONFLICT DO NOTHING`), so there is always a row to lock, and
   lock it;
3. let the domain refuse a result below `reserved` (409);
4. apply the change with a guarded update;
5. append the audit entry and `StockAdjustedV1`.

A refused change writes nothing.

### Decisions and deviations

1. **Expiry batches return their stock together, in (store, SKU) order. This corrects the specification.** The
   specification says to "return each one's stock (lines in SKU order)". Within one batch transaction, that order is
   only per reservation. Reservation 1 could lock `B` and reservation 2 then `A`, which can deadlock with a basket
   `[A, B]`. The sweeper sums the batch's lines per (store, SKU) (`HeldUnits.heldBy`) and returns them with one locked,
   sorted pair of statements.
2. **Ending order: statuses, then events, then stock.** The review suggested events, then stock, then statuses. This
   order puts the status update first instead of last. The update touches only reservation rows the transaction
   already holds, so doing it first keeps the hot stock rows locked for even less time. It also lets a stale
   transition fail before any other work.
3. **A late confirm and a run-out replay also append `ReservationExpiredV1`**: the specification says every expiry is
   announced. They are counted under `trigger=late_confirm` and `trigger=replay`.
4. **Counters of changes count after commit; counters of answers count at once.**
   - Changes: holds placed (`reservations{outcome=reserved}`), released and expired count only once their
     transaction commits. A change the caller rolls back never happened.
   - Answers: `rejected` and `replayed` count immediately. The caller was given that answer whatever became of its
     transaction; a rejection whose caller rolls back its empty transaction must still show.
5. **Scheduling uses a `SchedulingConfigurer` bean instead of `@Scheduled`.**
   - The behaviour is the same: a fixed delay of `daqiqa.inventory.expiry.interval`.
   - The interval comes from the bound `InventoryProperties`, so the default lives in one place.
   - The task registers only where `@EnableScheduling` is on (the server); tests call `sweep()` directly.
6. **Adjustments lock, then let the domain decide (`StockLevel.adjust`), instead of using one upsert statement.**
   - The rule is unit- and property-tested in one place.
   - Two first deliveries of a SKU queue on the same row; a test checks that 20 of them all count.
   - A change that would leave a negative quantity on hand is also `STOCK_BELOW_RESERVED`: on hand below zero is on
     hand below reserved. The problem carries `sku`, `onHand`, `reserved` and `delta`.
7. **`StockAdjustedV1.eventId` is the audit entry's id**: one fact, one id.
8. **Times are truncated to microseconds** (`Timestamps`), the precision PostgreSQL stores. A replayed reservation
   therefore reports exactly the `expiresAt` the first call reported.
9. **`AvailabilityQuery` does not open a transaction.** A single statement is consistent on its own, and the hot path
   saves the `COMMIT` round trip. It still joins a caller's transaction if there is one.
10. **Unknown reservation ids** in `confirm` / `release` throw `IllegalArgumentException`: the API has no "not found"
    outcome, and an id `reserve` never returned is a caller bug.
11. **A replay returns the existing reservation unchanged even if the basket differs**, as the API contract says.
    Ordering's idempotency key (with its request hash) catches key reuse upstream. The only exception is a hold whose
    time has run out (see above).
12. **`release` appends no event.** Only expiries and adjustments are announced. The reason is recorded on the
    reservation (`EndReason`, column `release_reason`) and counted. The API's `ReleaseReason` maps to `EndReason`
    through an exhaustive switch: a reason added to the API does not compile until the domain knows it.
13. **Schema, compared with the suggested one:**
    - `stock_level.version` is dropped: nothing read it. Tests tell written rows from untouched ones by `updated_at`.
    - Added: CHECKs on the status and reason values.
    - Added: `reservation_reason_iff_ended`, which the domain mirrors: a reason is present exactly when the
      reservation has ended.
    - Added: an append-only trigger on `stock_adjustment`.
    - Added: the index `(store_id, sku, created_at DESC)` for audit look-ups.
14. **HTTP details.**
    - `GET` removes repeated SKUs and keeps first-occurrence order.
    - `VALIDATION_FAILED` covers bean validation, a missing `sku` and more than 100 SKUs.
    - `INVALID_REQUEST` covers unreadable JSON, unknown enum values and malformed store ids or SKUs.
    - Identifiers are parsed from the path, query and body through `Identifiers`. Only that parse failure maps to
      400; any other `IllegalArgumentException` stays a server error and does not echo its message.
    - Problems carry the offending values: `storeId` on `STORE_NOT_FOUND`, `sku` on `UNKNOWN_SKU`, and `sku`,
      `onHand`, `reserved`, `delta` on `STOCK_BELOW_RESERVED`. RFC 9457 allows these extension members. The OpenAPI
      `Problem` schema could list them.
    - The problem handler is scoped to the stock controller and ordered first, so it wins over the server's
      application-wide problem-details handler. The slice test enables that handler to prove it.

### Configuration

| Property | Default | Meaning |
|---|---|---|
| `daqiqa.inventory.expiry.interval` | `5s` | Pause between the end of one sweep and the start of the next |
| `daqiqa.inventory.expiry.batch-size` | `100` | Holds expired per transaction |
| `daqiqa.inventory.expiry.lock-timeout` | `500ms` | How long a sweeping transaction waits for a stock row a checkout holds, before giving up and leaving those holds to the next round |

Invalid values (zero or negative) fail the startup. The module needs a `java.time.Clock` bean, an `OutboxWriter`, a
`StoreDirectory` and a `ProductCatalog` from the application around it.

### Metrics

| Meter | Type | Tags | Meaning |
|---|---|---|---|
| `daqiqa.inventory.reservations` | counter | `outcome` = `reserved` \| `rejected` \| `replayed` | Reservation attempts (`reserved` after commit) |
| `daqiqa.inventory.releases` | counter | `reason` = lower-case `ReleaseReason` | Reservations released on request (after commit) |
| `daqiqa.inventory.expirations` | counter | `trigger` = `sweeper` \| `late_confirm` \| `replay` | Holds that expired unconfirmed (after commit; the tag is an addition) |
| `daqiqa.inventory.expiry.failures` | counter | `scope` = `batch` \| `hold` | Failed attempts to expire holds: alert on `hold` |
| `daqiqa.inventory.expiry.contention` | counter | | Sweeps that gave up waiting for a row lock and left those holds to the next round: ordinary under load, not an alert |
| `daqiqa.inventory.expiry.oldest.overdue` | gauge (seconds) | | How long ago the longest-overdue hold should have expired: alert above a few sweep intervals |
| `daqiqa.inventory.reserve` | timer (percentile histogram) | | Deciding and applying a reservation, including waiting for row locks |

### Tests and what they prove

147 tests; `./gradlew :modules:inventory:build` is green. Every PostgreSQL test runs against a real server.

| Kind | Tests | Proves |
|---|---|---|
| Domain unit | 35 | Invariants of `StockLevel`, shortfalls, every state-machine transition (including the expiry boundary and the end-reason invariant) |
| Property (jqwik, 1,000 tries each) | 2 | Random sequences of reserve, confirm, release, sweep, clock and adjust keep `0 ≤ reserved ≤ onHand` and `reserved = Σ active holds`, with every transition leaving the current state; a basket has no shortfall exactly when every line is covered |
| Application unit | 21 | The race-only branches of `reserve` (including a run-out hold swept while the call waited for its lock), played deterministically; the sweeper's batching, fallback, contention handling and termination; telling contention from failure by SQL state, including a driver that reports none; guards |
| Persistence (PostgreSQL) | 29 | Every repository statement; the SQL refusing a double release and a re-expiry; a bounded lock wait that applies to its transaction only; locks proven with `lock_timeout` (SQLState 55P03) and `pg_stat_activity` (polled on its own connection) instead of sleeps; `SKIP LOCKED`; the unique-index wait on commit and on rollback; CHECK constraints; the append-only trigger |
| Application integration (Spring + PostgreSQL) | 37 | Reserve, reject-writes-nothing, idempotency, replay after run-out (fresh, rejected, other store, confirmed), a caller's rollback and visibility, confirm, late confirm (also inside a caller that catches and commits), release, sweeper (including a poison hold, contention with a stock row really held by another connection, the gauge, and stock rows free while events are appended), adjustments (404, 422, 409, audit entry, event), availability, metrics (including after-commit counting) |
| Concurrency (PostgreSQL) | 7 | 200 threads × 1 of 50 units → exactly 50 held, 150 rejected, no exceptions; opposite-order baskets → no deadlock, reserved equals the successful baskets; a mix of every operation → the books balance; simultaneous first calls with one reference → one hold, on both race paths; 6 sweepers → each of 300 holds expired once; retries of run-out holds racing checkouts and sweepers → each expired once, no deadlock |
| Web slice | 12 | Both endpoints: JSON shape, status codes, problem `type`, `code` and properties, precedence over the global handler, and that a server-side `IllegalArgumentException` is not blamed on the client |
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
- The status guard is removed from transitions.
- Stock is released before the events are appended.
- Lock contention is treated as a hold that cannot expire.

### Notes for the other modules

- **ordering**
  - Call `reserve` inside T1, as its last step.
  - Call `confirm` inside T2 and catch `ReservationNoLongerHeldException` there: the expiry commits with T2.
  - Release reservations one per transaction. For example, the abandoned-order sweeper should release each order in
    its own transaction: releasing several in one transaction locks their stock rows out of order and can deadlock
    with checkouts.
- **promise:** `AvailabilityQuery` costs one statement for all candidate stores × SKUs. Missing entries mean zero.
- **server:** Scheduling must be enabled for holds to expire in the background. `confirm` and a replay enforce expiry
  on their own either way.

### Known gaps, risks and next steps

- **Stock adjustments are not idempotent.** A retried `RECEIVED +24` counts twice. The contract is unchanged for now.
  Next: an `Idempotency-Key` header, as for orders (ADR-0008), or a client adjustment id with a unique constraint on
  `stock_adjustment`, so a retry returns the first result.
- **Unbounded lock waits.** A transaction that holds a hot stock row and stalls blocks every checkout of that SKU,
  and each waiter holds a pooled connection. Next: `SET LOCAL lock_timeout` in `reserve` (or a pool-wide
  `statement_timeout`) and a retryable error.
- **Hot items.** One row per (store, SKU) serialises reservations of that item. Measure in the load-test wave. ADR-0005
  names the next step: a Redis pre-reservation layer in front of the same guarantee.
- **A poison hold stays held** until someone repairs its stock rows; the failure counter and the overdue gauge make
  it visible. Next: a runbook, and parking such holds so each sweep does not retry them.
- **Sustained contention delays expiry.** Holds of a SKU contended for longer than a sweep interval wait for a later
  round. The contention counter and the overdue gauge show it; the stock is still returned, just later.
- **Same-reference reserve during that reference's release** can form a lock cycle. This needs a caller bug.
  PostgreSQL detects the cycle within `deadlock_timeout`, one call fails and can retry.
- **Growth.** Ended reservations are kept forever. Next: retention or archival, possibly partitioning by
  `created_at`.
- **Wave 2 and later:**
  - the `COMMITTED` status (picked: `onHand` and `reserved` both decrease);
  - sign rules per adjustment reason (`RECEIVED` > 0, `DAMAGED` < 0);
  - an endpoint to read the audit trail.
