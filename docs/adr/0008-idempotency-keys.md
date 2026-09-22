# ADR-0008: Idempotency keys on every call that creates a business fact

- Status: Accepted
- Date: 2026-09-22

## Context

Mobile networks drop responses. A customer who taps "Order" and sees a spinner will tap again; the app will retry.
Without protection this creates two orders, two holds and possibly two charges.

## Decision

`POST /v1/orders` requires an `Idempotency-Key` header, scoped to the customer and kept for 24 hours:

- the first request claims the key (`IN_PROGRESS`) in its own short transaction;
- when processing finishes, the key stores the final HTTP status and body, including deterministic business failures
  (out of stock, promise expired);
- a retry with the same key and the same request replays the stored response; with a different request it gets
  `422 IDEMPOTENCY_KEY_REUSED`; while the first is still running it gets `409 IDEMPOTENCY_REQUEST_IN_PROGRESS`;
- a claim abandoned by a crashed instance can be taken over after a lease expires;
- unexpected failures release the claim so the client can retry.

Inventory reservations are additionally idempotent by order reference (ADR-0005), and payments use the order id as
the provider's idempotency key.

## Consequences

- Retries are always safe, at every layer that can be retried.
- One extra small table and two short transactions per order.

## Alternatives considered

- **Client-side deduplication only**: not under our control.
- **Unique constraint on (customer, basket, minute)**: blocks legitimate repeat orders and still double-charges on
  some races.

## Amendment (2026-09-22, wave-1 review)

- **The order id is fixed when the key is first claimed** and reused as the reservation reference, the payment
  provider's idempotency key and the order's primary key. A retry that takes over an abandoned claim therefore resumes
  the same order instead of creating a second order, hold and authorization.
- **Every checkout transaction locks the key row first**, so an attempt that stalled past its lease cannot overwrite the
  outcome of a newer one.
- **Replays are byte-exact** (the response is rendered once and stored as text) and carry `Idempotent-Replayed: true`.
  `Retry-After` on an in-progress key is capped at 2 s: the first attempt usually finishes in well under a second.
- **A promise converts to at most one live order.** A partial unique index on `promise_id` for live orders is the
  guarantee; a per-promise advisory lock taken before any stock is held makes the answer deterministic when checkouts
  race, so the loser is told `PROMISE_ALREADY_USED` rather than sometimes `OUT_OF_STOCK`.
