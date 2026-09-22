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

## Amendment (2026-09-22, wave-1 review)

The review found two ways a valid token could still be honoured wrongly, and both are now closed:

- **The token binds the location.** Travel time — and therefore the promise — depends on where the order goes. The
  payload now carries the promised location (`latE6|lngE6`), and checkout refuses a delivery location more than
  `daqiqa.promise.token.location-tolerance` (100 m) away with `LOCATION_MISMATCH`, so an address edited after the quote
  triggers a new quote instead of an impossible promise.
- **A paused store stops honouring its tokens.** Verification also checks that the promised store still accepts orders
  (`STORE_UNAVAILABLE`), from the in-memory network view. On other replicas that view can be up to 30 s stale until
  status changes travel as events (roadmap).

Tokens are still stateless and reusable within their 90 s window; a promise converts to at most one *live* order,
which ordering enforces (ADR-0008).

Two consequences of carrying the location:

- The payload is no longer free of personal data: it holds the delivery point to metre precision, which is close to an
  address. Tokens are therefore never logged, and problem details never echo them.
- A token whose lifetime is not within the configured validity, or that claims to have been issued more than 30 seconds
  in the future, is rejected as `MALFORMED` even when its signature checks out. A leaked key can then not mint
  long-lived promises, and the development key is refused outside the `dev` profile (the server will not start with it).
