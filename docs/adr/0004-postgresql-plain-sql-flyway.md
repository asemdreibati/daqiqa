# ADR-0004: PostgreSQL, plain SQL via JdbcClient, Flyway with per-module major versions

- Status: Accepted
- Date: 2026-09-22

## Context

The core guarantees of Daqiqa (never oversell, never lose an order, idempotent checkout) are all about precise
control of transactions, row locks and constraints. An ORM hides exactly the statements whose shape matters.

## Decision

- PostgreSQL is the system of record, one schema per module.
- Data access uses Spring's `JdbcClient` with hand-written SQL: explicit column lists, conditional updates,
  `FOR UPDATE` / `SKIP LOCKED` where needed, `CHECK` constraints for invariants.
- Schema changes are Flyway migrations stored with the module that owns the schema. Each module owns one major
  version (network 1, catalog 2, inventory 3, promise 4, ordering 5, messaging 6, demo data 9), so modules can add
  migrations in parallel without version collisions; Flyway runs with `outOfOrder=true`.

## Consequences

- Every statement that matters for correctness is visible in code review and testable against real PostgreSQL.
- More code than an ORM for simple CRUD. Accepted; there is little simple CRUD in this system.
- The database enforces the last line of defence (for example `reserved <= on_hand`) even if application code is
  wrong.

## Alternatives considered

- **JPA/Hibernate**: rejected for the reasons above (implicit flushes, hidden locking, N+1 risk).
- **jOOQ**: type-safe and excellent, but needs code generation from a live schema in the build. Worth revisiting if
  the SQL surface grows large.
- **Timestamp-based migration versions**: collision-free in practice but not by construction; per-module majors are.
