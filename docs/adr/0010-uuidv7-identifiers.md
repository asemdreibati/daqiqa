# ADR-0010: UUIDv7 identifiers; human-readable store ids

- Status: Accepted
- Date: 2026-09-22

## Context

Orders, reservations and promises are created on many instances without coordination, so ids must be generated
client-side. Random UUIDv4 keys scatter inserts across B-tree pages and bloat indexes.

## Decision

- Use RFC 9562 UUIDv7 (time-ordered) for everything we create, through `Ids.newTimeOrderedId()`.
- Stores use human-readable ids (`DXB-MARINA`): there are few of them and people read them all day in logs,
  dashboards and Kafka keys.

## Consequences

- Inserts append to the right edge of indexes; ids sort roughly by creation time, which helps debugging.
- UUIDv7 leaks creation time; acceptable for these entities.
