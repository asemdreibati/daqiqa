# ADR-0001: Record architecture decisions

- Status: Accepted
- Date: 2026-09-22

## Context

Decisions made in a chat or a meeting are forgotten, then re-argued. New contributors (and reviewers) need to know not
only *what* the system does but *why* it is shaped this way and what was rejected.

## Decision

Record every decision that is expensive to reverse, or that a reviewer would question, as a short ADR in `docs/adr/`.
Keep ADRs immutable once accepted; a change of mind is a new ADR that supersedes the old one.

## Consequences

- Reviews can point to a decision instead of re-deriving it.
- Writing the "alternatives considered" section forces the decision to be justified, not just made.
