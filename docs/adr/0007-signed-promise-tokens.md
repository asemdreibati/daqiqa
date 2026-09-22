# ADR-0007: Signed, short-lived promise tokens

- Status: Accepted
- Date: 2026-09-22

## Context

A customer sees "12 minutes from DXB-MARINA" and then taps "Order". Checkout must honour exactly that promise: same
store, same basket, same ETA, and only if it was recent. Storing every promise server-side would put a write on the
hot read path (most promises are never converted) and couple checkout to promise storage.

## Decision

Return a compact, HMAC-SHA256-signed token with every promise. It carries the promise id, store, tier, ETA, issue and
expiry times, and a hash of the basket. Checkout verifies it statelessly: signature (constant-time comparison), expiry
against the injected clock, and basket hash. Tokens name the key that signed them (`kid`), so keys can be rotated
without downtime. Default validity: 90 seconds.

## Consequences

- No database writes for promises; checkout needs no lookup to know what was promised.
- Tampering with the ETA or store invalidates the signature; reusing a token for a different basket fails the hash.
- A token is not secret (it contains no personal data), only unforgeable.
- Promise analytics use the order (which carries the promise) and, later, sampled promise events, not token storage.

## Alternatives considered

- **Server-side promise records**: simple, but a write per promise and a lookup per checkout.
- **JWT library**: a standard format, but brings algorithm-negotiation pitfalls and a dependency for a payload we fully
  control. The format is small enough to implement and test exhaustively.
