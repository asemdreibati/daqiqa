# ADR-0014: Background jobs run on a platform-thread scheduler

- Status: Accepted
- Date: 2026-09-22

## Context

Daqiqa runs several periodic jobs: the outbox relay (every 250 ms), the reservation expiry sweeper (5 s), the
abandoned-order sweeper, the network refresh and two purges. They must be independent: a relay waiting up to its send
timeout on an unreachable broker must not stop reservations from expiring, because expired holds that are not swept
keep stock off sale.

With virtual threads enabled (ADR-0013), Spring Boot 4.1 configures a `SimpleAsyncTaskScheduler`, which triggers every
fixed-delay task from a single scheduling thread. One slow job then delays all the others.

## Decision

The server defines its own `ThreadPoolTaskScheduler` bean (`taskScheduler`) with a small pool of platform threads
(`daqiqa.scheduler.pool-size`, default 4). Each job keeps fixed-delay semantics (no overlapping runs of the same job),
and jobs no longer queue behind each other. Modules only register tasks; they never choose the scheduler.

## Consequences

- A stuck relay costs one scheduler thread, not every background job.
- Jobs are also safe to run on several replicas at once by design (`SKIP LOCKED`, advisory locks), so the pool size is a
  per-instance throughput knob, not a correctness one.
- Request handling still runs on virtual threads; only the handful of scheduled jobs use platform threads.

## Alternatives considered

- **`fixedRate` everywhere**: dispatches each run to its own virtual thread, but allows overlapping runs of the same job
  and changes semantics module by module.
- **One `@Scheduled(scheduler = ...)` per job**: works, but spreads an infrastructure decision across every module.
