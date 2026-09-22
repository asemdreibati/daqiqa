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
