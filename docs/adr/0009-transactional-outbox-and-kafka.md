# ADR-0009: Transactional outbox to Kafka, partitioned by store, single relay leader

- Status: Accepted
- Date: 2026-09-22

## Context

When an order is confirmed, dispatch, fulfilment and analytics must hear about it. Writing to PostgreSQL and then
sending to Kafka is a dual write: a crash in between loses the event; sending first can announce an order that then
rolls back.

## Decision

- Modules append integration events to `messaging.outbox_event` through `OutboxWriter`, in the same transaction as the
  state change.
- A relay publishes committed events to Kafka in creation order and marks them published. Only one instance relays at
  a time: it holds a PostgreSQL advisory lock (leader election without extra infrastructure), which keeps per-key
  ordering intact.
- Events are keyed by store id for store-scoped facts, so each store's events land in one partition, in order.
- The producer is idempotent (`enable.idempotence=true`, `acks=all`). Delivery is at-least-once; consumers deduplicate
  on `eventId`.
- Payloads are JSON with CloudEvents-style headers (`ce_id`, `ce_type`, `ce_source`, `ce_time`); the schema is the
  record in `platform/contracts`.

## Consequences

- No lost or phantom events, whatever crashes when.
- Events are delayed by the relay interval (sub-second by default).
- The outbox table must be kept small: published rows are deleted after a retention period.

## Alternatives considered

- **Debezium CDC on the outbox table**: removes polling latency and scales further; needs Kafka Connect. A natural next
  step, and compatible with this table design.
- **Spring Modulith event publication registry**: similar guarantees; rejected to keep the mechanism explicit (see
  ADR-0003).
- **Avro/Protobuf with a schema registry**: stronger schema evolution tooling; JSON keeps the system easy to inspect
  while the event set is small. Revisit when external consumers appear.

## Amendment (2026-09-22, wave-1 review)

- **Kafka is the default publisher.** The logging publisher, which marks events published without sending them, is an
  explicit opt-in for local runs, so a missing setting in production can never drop events silently.
- **Producer settings are checked at startup** (`acks=all`, explicit `enable.idempotence=true`, at most five requests
  in flight, `max.block.ms` no longer than the relay's send timeout).
- **A failing event backs off** exponentially (1 s doubling to 5 min, `next_attempt_at`) with throttled logs; events
  after it wait, preserving per-key order. Alert on `daqiqa.outbox.failures` and on the backlog gauge.
- **Marking events published uses the partial index** (`AND published_at IS NULL`): 0.6 ms instead of 30 ms at 300,000
  retained rows.
