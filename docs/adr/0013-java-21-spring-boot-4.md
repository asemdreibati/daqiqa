# ADR-0013: Java 21 and Spring Boot 4.1 with virtual threads

- Status: Accepted
- Date: 2026-09-22

## Context

The service is I/O-bound (PostgreSQL, Kafka, payment provider). The stack must be current and supported for the life
of the project.

## Decision

- Java 21 (LTS): records, sealed types, pattern matching, virtual threads.
- Spring Boot 4.1 (Spring Framework 7, Jackson 3, JUnit 6), supported until mid-2027; Spring Boot 3.5 left open-source
  support in June 2026.
- `spring.threads.virtual.enabled=true`: request handling and scheduled work run on virtual threads, so blocking JDBC
  calls do not exhaust a thread pool.
- JSpecify `@NullMarked` on every package.

## Consequences

- Plain blocking code with the scalability of asynchronous code for this workload, without reactive complexity.
- Care with `synchronized` around blocking calls (it pins carrier threads); prefer `ReentrantLock`.
- Many online examples target Boot 3; `AGENTS.md` lists the differences that matter.
