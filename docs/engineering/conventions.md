# Engineering conventions

These rules keep a codebase written by several people (and agents) in parallel looking like one person wrote it.
Most of them are enforced by the build: formatting (Spotless), architecture (ArchUnit), migrations (Flyway), tests.

## Repository layout

```
build-logic/            Gradle convention plugins (java, spring module, spring boot application)
gradle/libs.versions.toml   every dependency version, in one place
platform/
  shared-kernel/        value objects every context shares (ids, Money, GeoPoint, SkuQuantity)
  contracts/            integration events: the published language (JDK types only)
  messaging/            transactional outbox and its relay to Kafka
  testing/              PostgresTestDatabase, ArchitectureRules
modules/<context>/      one bounded context per module: network, catalog, inventory, promise, ordering
apps/daqiqa-server/     composition root: all modules deployed together
api/openapi/            the HTTP contract (daqiqa-v1.yaml)
deploy/compose/         local infrastructure (PostgreSQL, Kafka)
docs/                   product, architecture, ADRs, design notes, runbooks
```

## Module anatomy

Every bounded context has the same shape. The catalog module is the reference implementation.

```
io.daqiqa.<context>
  api/                  public surface: interfaces + records other modules may use. Nothing else is visible.
  domain/               the model: entities, value objects, policies, domain ports (repository interfaces).
                        Plain Java only: no Spring, no JDBC, no Jackson, no Micrometer.
  application/          use cases: orchestration, transactions (@Transactional), implementations of api
                        interfaces, outbound ports that are not domain concepts.
  adapter/in/web/       HTTP controllers, request/response DTOs, problem-detail mapping.
  adapter/out/...       persistence (JdbcClient), messaging, external services.
  config/               Spring wiring and @ConfigurationProperties, when component scanning is not enough.
```

Dependency rules (ArchUnit, `ArchitectureRules`):

- `domain` depends on nothing but the kernel and the JDK.
- `application` may use `domain` and `api`; `adapter` may use `application`, `domain`, `api`; `config` may use all.
- The `api` package never exposes domain types.
- Other modules may use a module only through its `api` package. No cycles between modules.

Visibility: classes are package-private unless another package needs them. Controllers, repositories and DTOs are
package-private.

## Java style

- Java 21. Records for values and DTOs, sealed interfaces for closed result types, `switch` pattern matching.
- Constructor injection only. No field injection, no Lombok.
- Null-safety: every package has a `package-info.java` with `@NullMarked` (JSpecify). Mark the exceptions with
  `@Nullable`. Prefer `Optional` for "may be absent" return values.
- Validate in constructors of value objects and entities; fail fast with `IllegalArgumentException`.
- Time: inject `java.time.Clock`. Never call `Instant.now()`/`LocalDateTime.now()` without a clock (ADR-0011).
  Store and transmit `Instant` (UTC).
- Money: `Money` in minor units. Never `double`.
- Identifiers: `XxxId.newId()` (UUIDv7) for things we create; store ids are human-readable (`DXB-MARINA`).
- Formatting: palantir-java-format via Spotless. Run `./gradlew spotlessApply` before committing.
- Comments explain *why*, not *what*. Every non-obvious index, lock or ordering decision gets one line.

## Persistence

- PostgreSQL is the system of record. One schema per module (`inventory`, `ordering`, ...). A module never reads or
  writes another module's schema; no cross-schema foreign keys or joins.
- Plain SQL with Spring's `JdbcClient`. Explicit column lists, `snake_case`, named constraints, `CHECK` constraints
  for invariants the database can enforce.
- Watch out: text blocks strip leading indentation. When concatenating SQL, keep an explicit space
  (`SELECT + " " + """ WHERE ...`) — we lost a build to `catalog.productWHERE`.
- Pass collections as arrays: `WHERE sku = ANY(:skus)` with a `String[]`.
- Migrations: Flyway, `src/main/resources/db/migration/<module>/`. Each module owns a **major version**, so parallel
  work never collides:

  | Module | Major | Example |
  |---|---|---|
  | network | 1 | `V1_1__network_schema.sql` |
  | catalog | 2 | `V2_1__catalog_schema.sql` |
  | inventory | 3 | `V3_1__inventory_schema.sql` |
  | promise | 4 | `V4_1__promise_schema.sql` |
  | ordering | 5 | `V5_1__ordering_schema.sql` |
  | messaging | 6 | `V6_1__messaging_schema.sql` |
  | demo data (server app) | 9 | `db/demo/V9_1__demo_stores.sql` |

  Flyway runs with `outOfOrder=true`. Never edit a migration that has been merged; add a new one.

## HTTP API

- Contract first: `api/openapi/daqiqa-v1.yaml`. Implementations follow it; changes to it are reviewed.
- Paths `/v1/...`, JSON `camelCase`, enums as `UPPER_SNAKE` strings.
- Errors: RFC 9457 `ProblemDetail` with `type = urn:daqiqa:problem:<code-in-kebab-case>` and a stable
  `code` property (`OUT_OF_STOCK`). Each module maps its own exceptions in a `@RestControllerAdvice` scoped to its
  web package.
- Status codes: 400 malformed/invalid input, 404 unknown resource, 409 conflict with current state, 422 well-formed
  but violates a business rule, 402 payment declined.
- Creating business facts (`POST /v1/orders`) requires `Idempotency-Key` (ADR-0008).
- Unbounded lists use keyset pagination (`cursor`, `limit`), never `OFFSET`.

## Events

- Integration events live in `platform/contracts` as records named `SomethingHappenedV1`, with a versioned `type`,
  a topic, and a partition key (the store id for store-scoped facts).
- Publish only through `OutboxWriter`, inside the transaction that changes the state (ADR-0009).
- Consumers are idempotent on `eventId`.

## Testing

| Kind | Where | Tools |
|---|---|---|
| Domain unit tests | `src/test/.../domain` | JUnit 6, AssertJ; no Spring |
| Property tests for invariants | next to unit tests | jqwik `@Property` |
| Persistence tests | `src/test/.../adapter/out/persistence` | `PostgresTestDatabase` (real PostgreSQL), `@Tag("integration")` |
| Concurrency tests | where the invariant lives | real PostgreSQL, many threads, assert invariants afterwards |
| Web slice tests | `src/test/.../adapter/in/web` | `@WebMvcTest`, `MockMvcTester`, `@MockitoBean` |
| Architecture tests | `ArchitectureTest` per module + server | `ArchitectureRules` |
| End-to-end | `apps/daqiqa-server` tests | `@SpringBootTest`, real HTTP |

Test names are sentences about behaviour: `rejectsAReservationThatWouldOversell()`. A bug fix starts with a failing
test. Tests must not depend on execution order or wall-clock time (use a fixed or mutable `Clock`).

## Git

- Conventional Commits: `feat(inventory): hold stock with ordered row locks`, `fix(catalog): ...`, `docs(adr): ...`,
  `test(...)`, `refactor(...)`, `build(...)`.
- Small, focused commits. The build is green on every commit to `main`.

## Definition of done

- [ ] Behaviour matches the design note and the OpenAPI contract (or the contract was updated in the same change).
- [ ] Unit tests for the domain; integration tests against real PostgreSQL for SQL; a concurrency test for any
      invariant that concurrent requests could break.
- [ ] `./gradlew build` is green (format, compile without warnings, tests, architecture rules).
- [ ] Metrics for the things an operator would ask about; logs without personal data.
- [ ] The design note in `docs/design/` explains the decisions a reviewer would question.

## Running the build locally

```bash
./gradlew build                      # uses Testcontainers for PostgreSQL (needs Docker)
DAQIQA_TEST_PG_URL=jdbc:postgresql://localhost:5432/postgres ./gradlew build   # or an existing PostgreSQL
```
