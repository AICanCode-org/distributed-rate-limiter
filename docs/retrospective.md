# RateGuard retrospective (reference solution, v1.0.0 → v1.1.0)

Format: *What went well / What didn't / What I'd change*, plus one technical, one process and one AI
lesson, as asked in Phase 8. Written by the maintainers who built the reference solution. Yours will
(and should) look different.

## What went well
- **One contract, three implementations, one set of Lua scripts.** `spec/openapi.yaml`,
  `spec/lua/*.lua` and `scripts/smoke-test.sh` are shared. All three tracks pass the same 64-check
  smoke test against their Docker images (53 at v1.0.0). Because the keys and the scripts are the same,
  any track can serve the same Redis and the same clients.
- **Atomicity designed in, not tested in.** Each decision is one Lua script (`EVALSHA`) that reads the
  state, refills with Redis `TIME`, decides and writes back. There is no read-then-write in application
  code, so two instances cannot both spend the last token. `scripts/load-test.py` (400 checks spread over two
  instances against a limit of 100) gets exactly 100 allowed, every run, for both algorithms.
- **Pure-function twins.** The token bucket and the sliding window exist twice: as pure functions with a
  fake clock (fast unit tests for every edge case) and as Lua scripts. The integration tests replay the
  same sequences through both and compare every answer, so the scripts are tested as thoroughly as the
  functions.
- **Tests before code with a pending list.** Turning on one test group at a time kept every commit green
  and made "done" objective. Every phase tag builds and passes its tests in all three tracks
  (`scripts/verify-tags.sh`).
- **AI off the decision path.** The policy advisor only *suggests* (`KEEP`, `RAISE_LIMIT`,
  `LOWER_LIMIT`, `INVESTIGATE`); its output is validated like user input and never applied
  automatically, and `/limits/check` never calls the LLM. Without an LLM the rules give the same kind of
  advice.

## What didn't go well
- **Failure tests were too kind.** Every "Redis is down" test failed fast (a fake store that raises, or a
  closed port). The Phase 7 game day *froze* Redis instead: in v1.0.0 every check waited the full 1 s
  timeout before its `503`, and `/health` answered `503` too, which would have taken every instance out of
  a load balancer (`docs/postmortem-2026-10-02-redis-freeze.md`). Correct behaviour, unacceptable latency.
- **One fail mode for everybody.** ADR-0004 decided "fail closed" for all tenants in Phase 2. Nobody asked
  the tenants. v1.1.0 made it a per-tenant choice (ADR-0006), but the question belonged in Phase 1.
- **A destructive test default.** The integration tests `FLUSHDB` database 15 of `TEST_REDIS_URL`, by
  default on `localhost:6379`. On a machine where port 6379 belonged to another project, one run without
  the variable emptied that project's database 15 (see the Phase 4 entry in `AI_LOG.md`).
- **Version churn.** Framework majors (Spring Boot 4 / Jackson 3, Fastify 5) and client libraries
  (Lettuce, ioredis, redis-py) moved APIs between "what the assistant remembered" and "what the docs say
  today". Several snippets had to be rewritten against current documentation.

## What I'd change next time
1. Write a **degraded-mode latency budget** next to NFR-3 on day one, and test it with a frozen
   dependency, not only a refused one.
2. Ask **"what should happen when we cannot decide?"** per tenant in Phase 1, not as an architecture
   default in Phase 2.
3. Put the **game day into CI** (`docker pause` inside the Docker smoke job, assert latency).
4. Make destructive test setup **opt-in**: no default `TEST_REDIS_URL` on the standard port, and refuse to
   run against a database that contains keys the tests did not create.
5. Rate-limit RateGuard's own admin API and alert on `rateguard_degraded_decisions_total`. Today a
   degraded `OPEN` tenant runs unthrottled without a single error.

## Three lessons
- **Technical:** concurrency bugs are designed out, not tested out. One atomic script per decision and one
  clock (Redis `TIME`) are simpler and stronger than any locking in application code. The parity and
  concurrency tests then prove it.
- **Process:** a pending-test list plus small milestone commits made progress visible and reviews small.
  The catch-up tags made it safe to fall behind.
- **AI:** assistants were excellent at boilerplate (Dockerfiles, workflow skeletons, test scaffolding,
  the game-day script) and needed the strictest review exactly where it matters most: the Lua maths
  (integer rounding of refills, the sliding-window weight), anything that reads a Redis key and writes
  it back, key comparison, and anything version-specific. Every accepted suggestion is in `AI_LOG.md`
  with how it was verified; tests, `actionlint` and the smoke test caught what reading did not.

## Evidence
| | Java | Python | TypeScript |
|---|---|---|---|
| Unit + integration tests (v1.1.0) | 153 + 18 | 192 + 18 | 156 + 18 |
| Line coverage (gate ≥ 85 %) | 97 % (JaCoCo) | 99 % (coverage.py) | 99.5 % (V8) |
| Docker smoke test v1.1.0 (LLM unreachable) | 64/64 (64/64) | 64/64 (64/64) | 64/64 (64/64) |
| 10 checks of a CLOSED tenant while Redis is frozen, v1.0.0 → v1.1.0 (avg) | 1 006 → 305 ms | 1 005 → 303 ms | 1 003 → 302 ms |
| `/health` while Redis is frozen, v1.0.0 → v1.1.0 | `503` in 1.00 s → `200 DEGRADED` in 4 ms | `503` in 1.48 s → `200 DEGRADED` in 2 ms | `503` in 1.00 s → `200 DEGRADED` in 2 ms |
