# Architecture decision records

Each significant decision is recorded once, with the context that made it right and the alternatives that lost.
Format: a lightweight [MADR](https://adr.github.io/madr/). Superseded decisions are kept and marked, never deleted.

| # | Decision | Status |
|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted |
| [0002](0002-modular-monolith-first.md) | Start as a modular monolith; extract services only for measured needs | Accepted |
| [0003](0003-hexagonal-modules-enforced-by-tests.md) | Hexagonal modules with boundaries enforced by ArchUnit | Accepted |
| [0004](0004-postgresql-plain-sql-flyway.md) | PostgreSQL, plain SQL via JdbcClient, Flyway with per-module major versions | Accepted |
| [0005](0005-never-oversell-inventory-reservations.md) | Never oversell: locked check-then-update reservations with expiring holds | Accepted (amended 2026-09-22) |
| [0006](0006-h3-for-coverage.md) | H3 hexagonal cells for service areas | Accepted (amended 2026-09-22) |
| [0007](0007-signed-promise-tokens.md) | Signed, short-lived promise tokens | Accepted (amended 2026-09-22) |
| [0008](0008-idempotency-keys.md) | Idempotency keys on every call that creates a business fact | Accepted (amended 2026-09-22) |
| [0009](0009-transactional-outbox-and-kafka.md) | Transactional outbox to Kafka, partitioned by store, single relay leader | Accepted (amended 2026-09-22) |
| [0010](0010-uuidv7-identifiers.md) | UUIDv7 identifiers; human-readable store ids | Accepted |
| [0011](0011-injected-clock.md) | One injected clock | Accepted |
| [0012](0012-testing-strategy.md) | Testing strategy: real PostgreSQL, properties, concurrency, architecture | Accepted |
| [0013](0013-java-21-spring-boot-4.md) | Java 21 and Spring Boot 4.1 with virtual threads | Accepted |
| [0014](0014-background-jobs-on-a-platform-thread-scheduler.md) | Background jobs run on a platform-thread scheduler | Accepted |
