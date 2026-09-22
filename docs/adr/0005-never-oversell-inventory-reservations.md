# ADR-0005: Never oversell: locked check-then-update reservations with expiring holds

- Status: Accepted
- Date: 2026-09-22

## Context

A 15-minute promise is worthless if the item is gone when the picker reaches the shelf. At peak, many customers order
the same popular items from the same store at the same moment, and baskets contain several items. We need:

1. no store ever has more units reserved than on hand, whatever the concurrency;
2. a basket is held all-or-nothing;
3. no deadlocks between concurrent baskets that share items;
4. held stock returns to sale if the order never completes (crash, abandoned payment);
5. retries do not hold stock twice.

## Decision

- `inventory.stock_level(store_id, sku, on_hand, reserved)` with `CHECK (reserved <= on_hand)` and non-negative
  checks: the database refuses an oversell even if the code is wrong.
- Reserving a basket, inside one transaction:
  1. lock the basket's rows with `SELECT ... WHERE store_id = ? AND sku = ANY(?) ORDER BY sku FOR UPDATE` — always in
     SKU order, so two baskets sharing items lock them in the same order and cannot deadlock;
  2. compute shortages from the locked rows; if any, return `Rejected` having written nothing;
  3. otherwise increment `reserved` for every line and insert the reservation with `expires_at = now + hold`.
- Reservations are idempotent by the caller's `reference` (a partial unique index over active reservations).
- A sweeper releases expired holds in batches with `FOR UPDATE SKIP LOCKED`, so several instances can sweep in parallel
  without blocking each other or releasing twice.
- `reserve` joins the caller's transaction, so an order and its reservation commit or roll back together.

## Consequences

- Correctness is guaranteed by the database under any concurrency, and verified by concurrency tests.
- Contention is per (store, SKU) row, held for the duration of a short transaction. At quick-commerce volumes (tens of
  orders per second per city) this is far from a bottleneck. If a single item ever becomes a flash-sale hot spot, a
  Redis pre-reservation layer in front of the same guarantee is the next step.
- Availability for promises is a separate, cheaper read (`AvailabilityQuery`) that may be slightly stale.

## Alternatives considered

- **Optimistic locking with retries**: degrades badly under contention on hot items (retry storms).
- **One conditional `UPDATE ... WHERE on_hand - reserved >= ?` per line**: correct per line, but a basket failing on
  its third line must undo the first two, and unordered updates can deadlock. Kept as defence in depth, not as the
  mechanism.
- **Serializable isolation**: correct but turns contention into serialization failures that every caller must retry.
- **Redis as the source of truth**: fast, but loses the atomic commit with the order and the durability guarantees.
