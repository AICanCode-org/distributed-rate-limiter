# ADR-0006: Per-tenant fail modes behind a Redis circuit breaker

* **Status:** accepted (extends ADR-0004)
* **Date:** 2026-10-02

## Context
The game day of 2026-10-02 (`docs/postmortem-2026-10-02-redis-freeze.md`) froze Redis for 30
seconds. v1.0 behaved as ADR-0004 promised, but worse than we had thought: **every** request waited
the full 1 s timeout before its 503, `/health` itself took 1 s, and the app kept hammering a Redis
that was known to be gone. Two tenants told us a 503 storm hurts them more than a short unthrottled
period; a third (a paid API reselling quota) must never over-admit.

## Options considered
1. **Keep fail closed, lower the timeout**: faster 503s, still a full outage, and false timeouts
   under normal load spikes.
2. **Global switch to fail open**: one setting cannot serve tenants with opposite needs.
3. **A circuit breaker in front of Redis plus a per-tenant `failMode`**: after 3 consecutive failures
   the breaker opens for `REDIS_BREAKER_COOLDOWN_MS` (5000 ms) and calls fail at once; then one trial
   call decides whether to close it. Meanwhile each tenant's policy decides: `CLOSED` (503, the v1.0
   behaviour), `OPEN` (admit, marked degraded) or `LOCAL` (an in-memory limiter per instance using
   the same pure functions as ADR-0001, marked degraded).

## Decision
Option 3. `failMode` is optional in the API and missing in records written by v1.0, which read as
`CLOSED`, so the change is backwards compatible both ways. Degraded decisions carry
`X-RateLimit-Degraded: true` and `"degraded": true`. Tenant records are cached (last known good) so
`OPEN`/`LOCAL` tenants can still be authenticated while Redis is away; a key that was never seen
before gets `503`. `/health` stays `200` with `status: DEGRADED`, because restarting app instances
cannot fix Redis. The LLM client of the advisor gets the same breaker.

## Consequences
* A Redis freeze costs at most 3 slow requests per instance, then answers in milliseconds.
* `LOCAL` admits up to N × limit across N instances during an outage; documented in the API.
* Rotated or deleted keys may still work from the cache during an outage. Accepted: the cache only
  serves while Redis is unreachable.
* New metrics (`rateguard_degraded_decisions_total`, `rateguard_store_errors_total`,
  `rateguard_store_short_circuits_total`) are needed
  to see any of this: alert on degraded decisions, not on 503s alone.
