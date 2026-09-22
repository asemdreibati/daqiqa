# ADR-0002: Start as a modular monolith; extract services only for measured needs

- Status: Accepted
- Date: 2026-09-22

## Context

Daqiqa has five bounded contexts (network, catalog, inventory, promise, ordering) and will grow dispatch, fulfilment
and tracking. The checkout path must hold stock and create an order atomically. Traffic is modest per city (a busy
city is thousands of orders per hour, tens per second at peak); promise reads are the hot path (hundreds to
thousands per second).

Microservices from day one would turn the checkout into a distributed transaction (a saga across inventory and
ordering) before there is any evidence that the two need to scale or deploy independently, and would multiply the
operational surface (deployments, network failures, tracing, data consistency) for a small team.

## Decision

Deploy all contexts as one Spring Boot application (`apps/daqiqa-server`), but build them as independent modules:

- one Gradle module per context, public surface limited to its `api` package (enforced by ArchUnit, ADR-0003);
- one PostgreSQL schema per context, no cross-schema joins or foreign keys;
- cross-context facts travel as integration events through the outbox (ADR-0009), as they would between services.

A context is extracted into its own service only when a measurement says so: a different scaling profile (e.g.
high-rate rider location ingestion), a different availability requirement, or a team boundary.

## Consequences

- Checkout is a single local transaction today: simpler, faster and strictly consistent.
- Because inventory reservations already expire on their own (ADR-0005), extracting inventory later turns the call
  into a saga without changing the business rules.
- Horizontal scaling is by running more identical instances behind a load balancer; state lives in PostgreSQL and
  Kafka.
- Discipline is required to keep boundaries clean; the architecture tests make violations fail the build.

## Alternatives considered

- **Microservices per context**: rejected for now (see context). The module boundaries keep the option open.
- **Unstructured monolith**: rejected; it cannot be split later and makes parallel development collide.
