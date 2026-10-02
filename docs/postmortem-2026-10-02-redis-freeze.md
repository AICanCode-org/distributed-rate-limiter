# Post-mortem: Redis freezes (game day, 2026-10-02)

| | |
|---|---|
| Type | Planned game day (fault injection), local Docker Compose stack, v1.0.0 and the v1.1.0 release candidate |
| Fault | Redis accepts connections but never answers (`scripts/game-day.sh`: `docker pause` on the Redis container) |
| Tracks | Python, TypeScript, Java (one at a time, one Compose project per tag, `REDIS_TIMEOUT_MS=1000`, the default) |
| Status | Resolved. Redis circuit breaker and per-tenant fail modes shipped in v1.1.0, four follow-ups open |
| Format | Blameless: we describe systems and decisions, not people |

Times are relative to the moment the fault was injected (T+0). Numbers come from
`scripts/game-day.sh <track>`: three tenants (limit 5 per 60 s, token bucket) with `failMode` `CLOSED`,
`OPEN` and `LOCAL` (v1.0 has no `failMode`, so all three behave as `CLOSED`); per phase 10 checks per
tenant one after the other (`POST /api/v1/limits/check`), client-side `curl` timings, then 40 concurrent
checks while timing `/health`. Reproduce it yourself, but expect your own numbers.

## Summary
With Redis frozen, v1.0.0 did what ADR-0004 promised: it **failed closed**, no tenant was ever admitted
without a decision, and every check answered `503 limiter-unavailable` with `Retry-After: 1`. But
**every** check waited the full 1 s timeout before its `503`, `/health` itself took 1.0–1.5 s and answered
`503 DOWN` (a load balancer would have taken *every* instance out of rotation at once, although restarting
them could not fix Redis), and the app kept sending commands to a Redis it knew was gone. v1.1.0 puts a
circuit breaker in front of Redis: after 3 failed store calls it stops calling Redis for 5 s, so only
the first two requests are slow. And each tenant now chooses what an outage means for it: `CLOSED`
(still `503`, now in milliseconds), `OPEN` (admitted, marked degraded) or `LOCAL` (limited by an
in-memory limiter on each instance, marked degraded).

## Impact (v1.0.0, before the fix)
| | Python | TypeScript | Java |
|---|---|---|---|
| Baseline, Redis up (avg of 10 checks) | 2–3 ms | 2–3 ms | 5–12 ms |
| **Redis frozen**: every tenant, avg / max of 10 | **1 005 / 1 005 ms** | **1 003 / 1 004 ms** | **1 006 / 1 007 ms** |
| Status codes during the fault (all three tenants) | 10 × `503` | 10 × `503` | 10 × `503` |
| `GET /health` while 40 checks are in flight | `503 DOWN` in **1.48 s** | `503 DOWN` in 1.00 s | `503 DOWN` in 1.00 s |
| Duration of the frozen phase in the script | ≈ 32 s | ≈ 32 s | ≈ 32 s |
| Right after unpause | `200` | `200` | `200` |

No tenant was over-admitted and no state was lost: the buckets in Redis were exactly where they were
before the freeze. For the APIs behind RateGuard, though, a limiter that thinks for a second and then
says "no" is an outage of *their* API, and two tenants told us they would rather run unthrottled for a
minute than return errors to all of their users.

## Timeline (Python track)
| Time | Event | Evidence |
|---|---|---|
| T−1 s | Tenants created; baseline: 5 × `200` then 5 × `429` per tenant, avg 2–3 ms | script output |
| T+0 | **Fault injected:** `docker pause` on the Redis container | |
| T+0–T+31 s | 30 checks, each ≈ 1 005 ms, all `503 limiter-unavailable` | `curl -w %{time_total}` |
| T+31 s | 40 concurrent checks; `GET /health` answers `503 {"status":"DOWN"}` after **1.48 s** | script output |
| T+31 s | Logs: one plain-text line per request, `store unavailable: Timeout reading from socket` (no request ID) | `docker compose logs app-python` |
| T+32 s | **Recovery:** `docker unpause`; the next checks answer `200` in 3 ms | script output |
| T+38 s | 1 × `200`, 1 × `429` per tenant: the buckets refilled ≈ 2.7 tokens during the 32 s | script output |
| later | Same game day on TypeScript and Java: ≈ 1 s per check, all `503`, `/health` `503` in ≈ 1.0 s | script output |
| v1.1 T+0 | v1.1.0 (breaker, fail modes) Python: fault injected again | |
| v1.1 T+0–T+1 s | CLOSED: 10 × `503`, avg **303 ms**, max 2 007 ms (the first check ≈ 2 s, the second ≈ 1 s, then ms); log line `redis circuit opened after 3 failure(s)` | script output, JSON logs |
| v1.1 T+1 s | OPEN: 10 × `200` degraded, avg 2 ms. LOCAL: 5 × `200` + 5 × `429` degraded, avg 2 ms | `X-RateLimit-Degraded: true` |
| v1.1 T+1 s | `rateguard_store_errors_total 3`, `rateguard_store_short_circuits_total 57`, `rateguard_degraded_decisions_total 20` | `/metrics` |
| v1.1 T+3 s | 40 concurrent checks; `/health` → `200 {"status":"DEGRADED"}` in **2 ms**; short-circuits 138, degraded 60 | `/metrics` |
| v1.1 T+4 s | **Recovery:** `docker unpause`. CLOSED still gets 2 × `503` in 2 ms (the breaker is inside its 5 s cooldown); OPEN `200` degraded; LOCAL `429` degraded | script output |
| v1.1 T+10 s | After the cooldown the half-open trial succeeds: all tenants `429`, not degraded (their buckets in Redis were still spent from the baseline) | script output |

v1.1.0 on the other tracks: TypeScript CLOSED avg 302 ms (max 2 004 ms), OPEN 1 ms, LOCAL 1 ms,
`/health` `200 DEGRADED` in 2 ms; Java CLOSED avg 305 ms (max 2 008 ms), OPEN 4 ms, LOCAL 4 ms, `/health`
in 4 ms. All three tracks counted exactly 3 store errors and the same 57 → 138 → 150 short-circuits.

## Root cause
1. **The limiter's store is on every request path and had no memory.** Each check calls Redis with a
   1 s timeout. v1.0.0 handled each failure correctly but independently, so a frozen Redis cost every
   request the full timeout, and the app kept adding load to a server that was already stuck.
2. **One fail mode for everybody.** ADR-0004 chose fail closed for all tenants, because over-admitting
   is the worst outcome for a paid quota. It is not the worst outcome for every tenant: for an internal
   API protecting itself from bursts, a `503` storm is worse than a short unthrottled period.
3. **`/health` mixed "this instance is broken" with "a dependency is broken".** Answering `503` when
   Redis is down tells a load balancer or orchestrator to replace instances that are fine, and it took
   a second to say so.
4. **Our failure tests simulated the easy failure.** The unit tests make the store fail *immediately*
   (a fake store that raises) and the integration tests use a closed port (connection refused in
   milliseconds). That proves the `503` is *correct*, not that it is *fast*. A Redis stuck in a long
   command, a fork for a snapshot or a network partition does not refuse connections; it just does not answer.

## What went well
- Fail closed held in all tracks: not a single request was admitted without a decision in v1.0.0, and in
  v1.1.0 the `CLOSED` tenant (the default) still got only `503`s.
- No limiter state was lost or corrupted: one atomic Lua script per decision (ADR-0001) means a request
  either ran completely or not at all.
- The same game day across three stacks showed it was a design problem, not a track problem: all three
  behaved identically to within a few milliseconds.
- `docker pause` made the realistic failure (accepts, never answers) reproducible in one command, with no
  extra tooling.
- After the fix, `/metrics` made the breaker visible (`rateguard_store_errors_total`,
  `rateguard_store_short_circuits_total`, `rateguard_degraded_decisions_total`), and the JSON logs gave one
  searchable line when the breaker opened, with the request ID of the request that opened it.

## What went wrong
- No latency budget for the failure case was written down (NFR-3 only covers Redis being healthy), so
  nothing tested for it.
- v1.0.0 had no metrics, so during the fault the only evidence was client-side timings and plain-text log
  lines without request IDs.
- Our first explanation of the v1.1 numbers ("the breaker opens after 3 requests") was wrong: a check
  makes two store calls (key lookup, then `EVALSHA`), so the breaker opens during the *second* request.
  See the AI log.

## Action items
| # | Action | Type | Status |
|---|---|---|---|
| 1 | Circuit breaker around all Redis calls (3 failures → open `REDIS_BREAKER_COOLDOWN_MS` → one half-open trial), per-tenant `failMode` (`CLOSED`/`OPEN`/`LOCAL`, ADR-0006), `/health` `DEGRADED`, metrics, fake-clock unit tests in every track | fix | **Done in v1.1.0** |
| 2 | Add NFR-9: *"With Redis unavailable or hanging, p95 check latency stays < 50 ms, excluding at most 3 store calls per instance per cooldown"* | requirement | Open |
| 3 | Run `scripts/game-day.sh` in the CI Docker job with a latency assertion | test | Open |
| 4 | Alert on `rateguard_degraded_decisions_total` and on the breaker opening, not only on `503`s; a degraded `OPEN` tenant produces no errors at all | ops | Open |
| 5 | Tell tenants when their decisions are degraded (a dashboard line or a webhook), so an `OPEN` tenant knows it ran unthrottled | product | Open |
| 6 | Lower `REDIS_TIMEOUT_MS` to 100 ms. Rejected for now: with the breaker, the timeout is paid at most three times per instance, and a low timeout turns normal latency spikes into false failures | idea | Won't do |

## Lessons
- **"Down" is easy, "frozen" is hard.** A refused connection fails in milliseconds; a frozen server costs the full timeout, every time.
- **Fail closed is a policy, not a law.** The right answer to "the limiter cannot decide" depends on what the tenant protects; let the tenant choose, and default to the safe one.
- **Health is about the instance.** A health check that fails because of a shared dependency turns one broken Redis into zero healthy instances.
