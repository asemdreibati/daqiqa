# ADR-0012: Testing strategy: real PostgreSQL, properties, concurrency, architecture

- Status: Accepted
- Date: 2026-09-22

## Context

The promises Daqiqa makes (no oversell, no lost order, safe retries) fail only under concurrency and only against a
real database. Mock-based tests of SQL prove nothing about locks.

## Decision

- Domain logic: fast unit tests, plus jqwik property tests for invariants over many generated inputs.
- SQL: integration tests against real PostgreSQL through `PostgresTestDatabase` (Testcontainers, or an existing server
  when Docker is unavailable).
- Concurrency: for each invariant concurrency could break, a test that runs many threads against the real database
  and checks the invariant afterwards.
- HTTP: `@WebMvcTest` slices for the wire contract; full `@SpringBootTest` over real HTTP for end-to-end paths.
- Architecture: ArchUnit rules (ADR-0003).
- Performance and resilience: load and chaos scenarios run against the simulator (see the roadmap).

## Consequences

- The build proves the guarantees, not just the happy paths.
- Integration tests need PostgreSQL; the dual-mode test database keeps that cheap everywhere.
