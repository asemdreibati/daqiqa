# ADR-0011: One injected clock

- Status: Accepted
- Date: 2026-09-22

## Context

Promises expire, holds expire, SLAs are measured in minutes, and the simulator must replay a whole day in minutes.
Code that reads the system time directly cannot be tested deterministically or simulated faster than real time.

## Decision

Every component that needs the time receives a `java.time.Clock` bean. Production uses `Clock.systemUTC()`; tests
use fixed or manually advanced clocks; the simulator will use an accelerated clock.

## Consequences

- Time-dependent behaviour (expiry, lateness) is tested in milliseconds, without `sleep`.
- A code review rule: `Instant.now()` without a clock argument is a defect.
