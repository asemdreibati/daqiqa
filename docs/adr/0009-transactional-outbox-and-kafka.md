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
