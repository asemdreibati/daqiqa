# AGENTS.md — working in this repository

Instructions for anyone (human or AI agent) changing Daqiqa. Read `docs/engineering/conventions.md` first; this file
is the short operational version.

## Commands

```bash
./gradlew spotlessApply              # format (run before every commit)
./gradlew :modules:inventory:build   # one module: compile, test, architecture rules
./gradlew build                      # everything
```

Integration tests need PostgreSQL. With Docker available, Testcontainers starts one. Without Docker, point the build
at a running server: `export DAQIQA_TEST_PG_URL=jdbc:postgresql://localhost:5432/postgres` (user and password default
to `postgres`; override with `DAQIQA_TEST_PG_USER` / `DAQIQA_TEST_PG_PASSWORD`). Each test JVM gets its own database.

## Golden rules

1. Follow the module anatomy (`api` / `domain` / `application` / `adapter` / `config`). Copy patterns from
   `modules/catalog`, the reference module.
2. Other modules are reachable only through their `api` package. Never import another module's internals.
3. The domain is plain Java. Put invariants there and test them without Spring.
4. Every invariant that concurrency could break gets a concurrency test against real PostgreSQL.
5. Inject `Clock`; never read the system time directly.
6. Stay in your lane: do not edit files owned by another module, `settings.gradle.kts`, `gradle/libs.versions.toml`,
   the server's `application.yaml`, ADRs or the OpenAPI contract. If you need a change there, say so in your report.
7. Commit in small Conventional Commits. The build must be green before you report done.

## Stack notes (Spring Boot 4.1, Spring Framework 7, Jackson 3, JUnit 6, Testcontainers 2)

These trip people up because most examples online predate them:

- Starters: `spring-boot-starter-webmvc` (not `-web`), `spring-boot-starter-jdbc`, `spring-boot-starter-kafka`,
  `spring-boot-starter-flyway` (Flyway auto-configuration needs the starter, not just `flyway-core`).
- Test slices live in per-technology test starters: `spring-boot-starter-webmvc-test` provides
  `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`. Use `MockMvcTester` (AssertJ style) and
  `@MockitoBean` (`org.springframework.test.context.bean.override.mockito.MockitoBean`); `@MockBean` is gone.
- Slice tests in a library module need a `@SpringBootApplication` class in the module's test sources (see
  `CatalogTestApplication`).
- Jackson 3: packages are `tools.jackson.core` / `tools.jackson.databind` (`JsonMapper`), but annotations stay in
  `com.fasterxml.jackson.annotation`. Boot auto-configures a `JsonMapper` bean. java.time is supported out of the box
  and dates are ISO-8601 strings by default.
- Testcontainers 2: `org.testcontainers.postgresql.PostgreSQLContainer`, `org.testcontainers.kafka.KafkaContainer`.
- Kafka tests without Docker: `spring-boot-starter-kafka-test` brings `@EmbeddedKafka` (in-JVM broker).
- JdbcClient: text blocks strip indentation, so keep a space when concatenating SQL; bind collections as arrays with
  `= ANY(:param)`.
- Virtual threads are on (`spring.threads.virtual.enabled=true`). Do not use `synchronized` around blocking I/O; use
  `ReentrantLock` if you must lock.

## Where things go

| You are adding | Put it in |
|---|---|
| A table | `modules/<m>/src/main/resources/db/migration/<m>/V<major>_<n>__<what>.sql` (majors in conventions.md) |
| An HTTP endpoint | `adapter/in/web`, matching `api/openapi/daqiqa-v1.yaml` |
| An integration event | `platform/contracts` (ask first), published via `OutboxWriter` |
| Configuration | a `@ConfigurationProperties` record under `daqiqa.<module>.*`, documented in the module's design note |
| A design explanation | `docs/design/<module>.md` |
