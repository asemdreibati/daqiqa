# ADR-0003: Hexagonal modules with boundaries enforced by ArchUnit

- Status: Accepted
- Date: 2026-09-22

## Context

Boundaries that exist only in a diagram erode within weeks. Business rules mixed into controllers or SQL are hard to
test and hard to reason about under concurrency.

## Decision

Each module follows the same anatomy (`api`, `domain`, `application`, `adapter`, `config`; see
`docs/engineering/conventions.md`). The domain is plain Java. `ArchitectureRules` in `platform/testing` turns the
rules into tests:

- each module's `ArchitectureTest` checks that its domain is framework-free and its layers depend inwards;
- the server's `ArchitectureTest` checks that modules use each other only through `api` packages and that there are
  no cycles between modules.

## Consequences

- Invariants live in code that can be unit-tested in milliseconds without Spring or a database.
- A boundary violation is a red build with an explanation, not a code-review debate.
- Some mapping code between API records, domain objects and DTOs. Accepted: it is what keeps the wire format, the
  public API and the model free to evolve separately.

## Alternatives considered

- **Spring Modulith**: provides similar verification. Rejected for now to keep the rules explicit and independent of
  the Spring release train; the conventions are compatible with adopting it later.
- **Separate `-api` Gradle modules**: stricter (compile-time) but doubles the module count. The ArchUnit rules give
  the same guarantee at test time.
