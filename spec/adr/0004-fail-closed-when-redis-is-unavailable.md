# ADR-0004: Fail closed (503) when Redis is unavailable

* **Status:** accepted (extended by ADR-0006 in v1.1)
* **Date:** 2026-10-02

## Context
Every check needs Redis. When Redis is down or slow, RateGuard must still answer, and the answer
is a business decision: admitting everything protects the customer's availability but removes the
protection RateGuard exists for (a scraper or a retry storm then hits the backend unthrottled);
refusing everything protects the backend but turns a Redis outage into a full outage.

## Options considered
1. **Fail open**: admit all requests while Redis is unavailable.
2. **Fail closed**: answer `503 limiter-unavailable` with `Retry-After: 1`.
3. **A local in-memory limiter per instance**: keeps some protection, but each instance allows the
   full limit, so the effective limit becomes N times larger, and it is more code to get right.

## Decision
Option 2 for v1.0: a Redis error or a command slower than `REDIS_TIMEOUT_MS` (1000 ms) becomes
`503 limiter-unavailable` with `Retry-After: 1`. `/health` reports `redis: DOWN` and answers `503`
so a load balancer can take the instance out. Clients must treat 503 as "retry later", never as
"allowed". The timeout is short on purpose: a limiter that adds seconds of latency is worse than no limiter.

## Consequences
* The protection never silently disappears.
* A Redis outage is a RateGuard outage for every tenant. We accept this for v1.0 and will revisit
  it after measuring a real Redis failure (planned game day in Phase 7).
* Every request still waits up to `REDIS_TIMEOUT_MS` while Redis hangs; nothing stops the app from
  retrying a Redis that is known to be down. Watch this in the game day.
