---
phase: 7
slug: phase-7-observability
title: Observability, game day and resilience
duration: 3 h
start_tag: phase-6-end
end_tag: phase-7-end
release: v1.1.0
---
# Phase 7 · Observability, game day and resilience (v1.1.0)

## 1. Why it matters
Shipping v1.0.0 is the start, not the end. In production you have to answer "is it working right now,
and if not, why?" without attaching a debugger. That needs three things: **logs** you can search,
**metrics** you can graph, and a **request ID** that ties a tenant's complaint ("we got 503s at 19:42")
to log lines. RateGuard sits in front of other people's traffic and depends on one thing it does not
control, Redis, so you will run a **game day** where Redis freezes, measure what tenants feel, and
discover that "fail closed" (ADR-0004) hurts more than the ADR predicted. You fix it with a **circuit
breaker** and your first **maintenance change**, driven by what tenants told you: a per-tenant
`failMode`, released backward compatibly as v1.1.0.

## 2. Objectives
- Give every request an `X-Request-ID` (reuse a safe incoming one, or generate a UUID) and put it in every log line.
- Emit structured **JSON logs**: one object per line, one access-log line per request.
- Expose `/metrics` in Prometheus text format with eight counters that are identical in every track.
- Run a **game day**: freeze Redis with `docker pause`, measure checks and `/health`, write a short blameless post-mortem.
- Add a Redis **circuit breaker** (3 failures in a row → open for `REDIS_BREAKER_COOLDOWN_MS` → one half-open trial) and reuse it for the LLM.
- Ship a backward-compatible change: `policy.failMode` (`CLOSED` | `OPEN` | `LOCAL`), degraded decisions,
  `/health` `DEGRADED`, OpenAPI 1.1.0, ADR-0006, CHANGELOG, tag `v1.1.0`.
- Review and merge one Dependabot PR.

## 3. Step-by-step instructions

### Step 7.1 · Request IDs
Rule (shared by all tracks): if the incoming `X-Request-ID` matches `^[A-Za-z0-9._-]{1,64}$`, echo it;
otherwise generate a UUID. Never echo arbitrary input: a header containing a newline could inject
fake log lines or headers.

<!-- track:java -->
A `OncePerRequestFilter` (`web/RequestIdFilter.java`) sets the response header, puts `requestId` in
the SLF4J **MDC** (so every log line in that request carries it) and logs one `rateguard.access` line
with `method`, `path`, `status` and `durationMs` using the fluent API
`LOG.atInfo().addKeyValue("status", …).log()`. `@Order(Ordered.HIGHEST_PRECEDENCE)` makes it the first
filter, so even a `401` or a `404` gets an ID.
<!-- /track -->

<!-- track:python -->
A Starlette middleware (`app/observability.py`) stores the ID in `request.state`, sets the response
header and logs one `rateguard.access` record with `extra={"fields": {...}}`. The middleware wraps the
whole app, so error responses (`400`, `401`, `404`, `503`) carry the header too.
<!-- /track -->

<!-- track:typescript -->
Fastify has request IDs built in: `genReqId` (calling `incomingOrNewId()` from `src/observability.ts`)
with `requestIdHeader: false`, so we validate the incoming value ourselves, and a `LogController` with `requestIdLogLabel: "requestId"`.
An `onSend` hook copies `request.id` into the `X-Request-ID` response header.
<!-- /track -->

### Step 7.2 · JSON logs
<!-- track:java -->
One property: `logging.structured.format.console=logstash` in `application.properties`. MDC values
and key/value pairs become JSON fields.
<!-- /track -->

<!-- track:python -->
A small `logging.Formatter` subclass (`JsonFormatter`) that `json.dumps` `ts`, `level`, `logger`, `msg`
and the `fields`; installed by `configure_logging()` at startup.
<!-- /track -->

<!-- track:typescript -->
Nothing to add: Fastify's logger (pino) already writes JSON, one line per event (`incoming request`,
`request completed` with `responseTime`).
<!-- /track -->

**Never log secrets.** API keys, the admin token, the `Authorization` and `X-API-Key` headers and the
LLM API key must not appear in any log line. Grep for them after the smoke test
(`docker compose logs app-<track> | grep -E 'rg_[0-9a-f]{32}|Bearer'` must print nothing).

Check it:
```bash
docker compose --profile <track> up -d --build --wait
curl -s -H 'X-Request-ID: demo-123' localhost:8080/health -o /dev/null -D - | grep -i x-request-id
docker compose logs app-<track> | grep demo-123
```

### Step 7.3 · `/metrics`
Eight counters, same names in all tracks (see `GET /metrics` in `spec/openapi.yaml`):

| Counter | Incremented when |
|---|---|
| `rateguard_decisions_allowed_total` | a check was allowed |
| `rateguard_decisions_denied_total` | a check was denied (a spike here is a tenant hitting its limit) |
| `rateguard_degraded_decisions_total` | a check was decided by the tenant's `failMode` because Redis was unavailable (Step 7.6) |
| `rateguard_store_errors_total` | a Redis call failed (refused, timed out, broken connection) |
| `rateguard_store_short_circuits_total` | the circuit breaker skipped a Redis call (Step 7.5) |
| `rateguard_tenants_created_total` | `POST /admin/tenants` created a tenant |
| `rateguard_ai_fallback_total` | an advice request fell back to the offline rules |
| `rateguard_llm_short_circuits_total` | the circuit breaker skipped an LLM call |

They are hand-rolled on purpose: the Prometheus text format is just
```text
# HELP rateguard_store_errors_total Redis calls that failed (refused, timed out, broken connection).
# TYPE rateguard_store_errors_total counter
rateguard_store_errors_total 3
```
In a bigger service you would use Micrometer (Java), `prometheus-client` (Python) or `prom-client` (Node).
`/metrics` is public in this course project; in production you would restrict it to the scraper's network.

### Step 7.4 · Game day: Redis freezes
A dead Redis is easy: the connection is refused and the error comes back in a millisecond. A
**frozen** Redis is worse: the kernel still accepts connections, but nothing answers, so every command
waits for the full `REDIS_TIMEOUT_MS` (1 s). That is what a Redis stuck in a slow command, a full disk,
a long fork for a snapshot or a network partition looks like. `docker pause` produces exactly that.
1. Start the stack at v1.0.0: `git switch --detach v1.0.0`, `docker compose --profile <track> up -d --build --wait`.
2. Get the script from the reference: `git show phase-7-end:scripts/game-day.sh > /tmp/game-day.sh && chmod +x /tmp/game-day.sh`,
   then `/tmp/game-day.sh <track>`. It creates three tenants with a limit of 5 per 60 s and the fail
   modes `CLOSED`, `OPEN` and `LOCAL` (v1.0 ignores the field: every tenant is effectively `CLOSED`),
   then runs phases of 10 requests per tenant: **baseline**, Redis **frozen**, `/health` while 40
   requests are in flight, Redis **back** immediately, and Redis back **after 6 s**.
3. Write down for each phase: status codes, average and maximum latency, how many decisions were
   degraded, `/health`'s status and latency, the warnings in the logs, and (from v1.1) the counters.
   What did tenants get? What did the load balancer get from `/health`, and what would it do with it?
4. Write `docs/postmortem-<date>-redis-freeze.md`: summary, impact, timeline, root cause, what went
   well, what to improve, action items. Blameless: describe systems, not people. Read the reference
   post-mortem **after** writing yours (`git show phase-7-end:docs/postmortem-2026-10-02-redis-freeze.md`).

### Step 7.5 · Fix: a circuit breaker in front of Redis
Wrap the store in a `GuardedStore` that sends every call through a `CircuitBreaker`:
- **CLOSED:** calls go through. 3 store failures **in a row** open the circuit.
- **OPEN:** calls fail immediately with "store unavailable" for `REDIS_BREAKER_COOLDOWN_MS` (5 s), and
  `rateguard_store_short_circuits_total` is incremented. No request waits for a timeout any more.
- **HALF-OPEN:** after the cooldown, exactly **one** trial call goes through. Success closes the circuit;
  failure re-opens it for another cooldown.

Only store failures count: a validation error or a duplicate tenant id is not a Redis problem and must
not open the circuit. Test it with a fake clock and a fake store (no sleeping in tests): 3 failures →
the 4th call does not reach the store; advance the clock 5 s → one trial; a concurrent second call
during the trial is short-circuited. The same breaker class (cooldown 30 s) wraps the LLM client of
the advisor.

<!-- track:java -->
`resilience/CircuitBreaker.java` is generic (`call(Call<T>)` with an `isFailure` predicate and an
`onShortCircuit` callback); `store/GuardedStore.java` implements `Store`, and `AppConfig` exposes it as the
`@Primary` store bean, so every service gets the guarded one. State changes are `synchronized`; the slow
Redis call happens **outside** the lock.
<!-- /track -->

<!-- track:python -->
`CircuitBreaker` in `app/breaker.py` has a `call(fn)` method, a `threading.Lock` around state changes
and `time.monotonic` as the default clock (wall-clock time can jump). `GuardedStore` in `app/store.py`
has the same methods as `RedisStore`; `guard()` in `app/main.py` wires it.
<!-- /track -->

<!-- track:typescript -->
`CircuitBreaker` in `src/breaker.ts` wraps any async call. Node is single-threaded, but `await` still
interleaves requests, so the trial flag matters exactly as it does in Java and Python. `GuardedStore`
in `src/store.ts` implements `Store`; `guard()` in `src/wiring.ts` wires it.
<!-- /track -->

### Step 7.6 · Maintenance change: per-tenant fail modes (v1.1.0)
The breaker makes failures fast, but a fast `503` is still an outage. After the game day, tenants
disagreed about what they want during one (see ADR-0006). v1.1 lets each policy decide:
1. `policy.failMode`: `CLOSED` (the v1.0 behaviour: `503 limiter-unavailable`), `OPEN` (admit, marked
   degraded) or `LOCAL` (an in-memory limiter per instance, using **the same pure functions** from
   M2, marked degraded). Optional in requests; validation message
   `policy.failMode must be CLOSED, OPEN or LOCAL`.
2. **Backward compatible both ways:** the field is stored in the tenant hash; a hash written by v1.0
   has no `failMode` and reads as `CLOSED`. v1.0 ignores the extra field. A rolling deploy can mix versions.
3. **Last known good tenants:** `authenticate` caches each tenant it has seen, and uses the cache only
   when Redis is unavailable, so `OPEN`/`LOCAL` tenants can still be recognised. A key never seen
   before is refused.
4. **Degraded decisions** carry `X-RateLimit-Degraded: true` and `"degraded": true`; the counter
   `rateguard_degraded_decisions_total` counts them.
5. `/health` answers **`200`** with `status: DEGRADED` while Redis is down: restarting app instances
   cannot fix Redis, and a load balancer that removes every instance turns a degraded service into none.
6. `spec/openapi.yaml` → `version: 1.1.0` (`failMode`, `degraded`, `DEGRADED`, `/metrics`, `X-Request-ID`);
   `spec/adr/0006-…`; `CHANGELOG.md` → `## [1.1.0]`; bump the track's version.
7. Tests first: the breaker tests, resilience tests per fail mode (with a store that fails on demand and
   the breaker's fake clock), an integration test that a v1.0 hash reads as `CLOSED`, and one that a dead
   Redis costs three timeouts and then answers fast.
8. Re-run the game day on your branch, then release:
   `git tag -a v1.1.0 -m "RateGuard v1.1.0" && git push origin main --follow-tags`.

In the reference run (all three tracks gave the same pattern): v1.0 answered **every** check during the
freeze with `503` after ~1 s and `/health` with `503`; v1.1 answered `OPEN` tenants with `200` in 1–4 ms,
`LOCAL` tenants with 5 × `200` + 5 × `429`, `CLOSED` tenants with `503` after only three slow calls,
and `/health` with `200 DEGRADED` in a few milliseconds while 40 requests were in flight.

### Step 7.7 · Merge a Dependabot PR
Pick one grouped update. Read its release notes, check that CI is green **including integration tests**,
run it locally if it is a major version, then merge. Write down in `AI_LOG.md` or the PR what you checked.

## 4. AI-assist prompts

### Prompt 1: Structured logging setup
```text
I run a <Spring Boot 4 | FastAPI | Fastify 5> service. Show me the smallest change that makes every log
line a single JSON object with ts, level, logger, msg and a requestId taken from a validated
X-Request-ID header (regex ^[A-Za-z0-9._-]{1,64}$, else a new UUID), plus one access-log line per
request with method, path, status and durationMs. No new dependencies unless unavoidable.
```
**Verify the output by:** running the smoke test and piping the logs through `jq -c .` (every line must
parse), sending `X-Request-ID: $(printf 'a\nb')` and checking a UUID comes back instead, and grepping the
logs for `rg_` and `Bearer` (no matches).

### Prompt 2: Review my circuit breaker
```text
Here is my circuit breaker for Redis calls: <paste>. Requirements: 3 consecutive store failures open
it; while open, calls fail immediately for REDIS_BREAKER_COOLDOWN_MS; then exactly one trial call; a
success closes it, a failure re-opens it; only store errors count as failures (not validation errors or
duplicates); the slow Redis call must not run while holding a lock. Find bugs, especially races between
concurrent requests in the half-open state, and give a failing test for each.
```
**Verify the output by:** turning each claimed race into a unit test with a fake clock and two
interleaved calls. Keep only findings whose test fails before your fix and passes after it. Reject
suggestions to add `sleep` to tests.

### Prompt 3: Is this change backward compatible?
```text
v1.0 of my service stores tenants as Redis hashes without a failMode field; v1.1 adds failMode (CLOSED,
OPEN, LOCAL; missing means CLOSED) and a degraded flag in responses. During a rolling deploy, v1.0 and
v1.1 instances share one Redis. List every way this can go wrong: a v1.1 instance reading a v1.0 hash, a
v1.0 instance reading or rewriting a v1.1 hash (does it drop the field?), clients that parse responses
strictly, and what a rollback to v1.0 does.
```
**Verify the output by:** testing the claims: create a tenant with v1.1 and `failMode: LOCAL`, change its
policy with the v1.0 image against the same Redis, then read it with v1.1 again (does `failMode` survive?);
create one with v1.0 and read it with v1.1 (`CLOSED`). Write down what you found in the PR; it decides
whether a rollback is safe.

## 5. Deliverables
- [ ] `X-Request-ID` on every response (including errors) and in every log line; logs are JSON; no secrets in logs.
- [ ] `/metrics` with the eight counters.
- [ ] Game-day notes and `docs/postmortem-<date>-redis-freeze.md` with numbers for v1.0 and your fix.
- [ ] The Redis circuit breaker with unit tests (fake clock, no sleeps); the LLM uses the same breaker.
- [ ] Fail modes: `failMode` in the API, degraded decisions with the header and flag, `/health` `DEGRADED`, ADR-0006.
- [ ] OpenAPI 1.1.0, CHANGELOG `[1.1.0]`, tag `v1.1.0`, images published.
- [ ] One Dependabot PR reviewed and merged.

## 6. Self-check quiz

### Q1. Why not simply echo any incoming `X-Request-ID`?
- [ ] It would be too long for the log
- [x] Unvalidated input could inject newlines or fake fields into logs and headers
- [ ] Request IDs must always be UUIDs
- [ ] Browsers do not send it
> **Answer:** Treat headers as user input. A strict allow-list regex keeps the useful case (a caller propagating its ID) and rejects the dangerous one.

### Q2. Why is a frozen Redis worse than a stopped one?
- [ ] A frozen Redis loses data
- [x] A stopped Redis refuses connections in a millisecond; a frozen one accepts them and never answers, so every call waits for the full timeout
- [ ] A stopped Redis is restarted automatically
- [ ] There is no difference with a timeout
> **Answer:** Fast failures are cheap; slow failures pile up requests, threads and connections. That is why the game day uses `docker pause`, not `docker stop`.

### Q3. In v1.0, `/health` returned `503` during the freeze. Why is that dangerous behind a load balancer?
- [ ] The load balancer caches the 503
- [x] Every instance reports unhealthy at once, so the load balancer removes all of them and a Redis problem becomes a total outage, although restarting instances cannot fix Redis
- [ ] `503` is not a valid health status
- [ ] The health check takes too little time
> **Answer:** Health checks should report what restarting or removing *this* instance would fix. v1.1 answers `200 DEGRADED` and exposes the dependency state for monitoring instead.

### Q4. What does the circuit breaker change for a `CLOSED` tenant during the freeze?
- [ ] Its requests are now allowed
- [ ] Nothing at all
- [x] It still gets `503`, but after three slow calls the rest fail immediately instead of each waiting a second
- [ ] It gets `429` instead of `503`
> **Answer:** The breaker does not change the decision, only its cost. The decision is the tenant's `failMode`.

### Q5. Why does a tenant hash written by v1.0 read as `failMode: CLOSED` in v1.1?
- [ ] v1.1 rewrites all hashes at start-up
- [x] A missing field means the v1.0 behaviour, so old data keeps its meaning and no migration is needed
- [ ] Redis adds default fields automatically
- [ ] `CLOSED` is the safest mode for new tenants only
> **Answer:** Additive, optional fields with a default equal to the old behaviour are the cheapest way to stay compatible during a rolling deploy and a rollback.

### Q6. A `LOCAL` tenant has a limit of 5 per minute and 3 app instances. During a Redis outage, how many requests per minute can it get?
- [ ] 5
- [ ] 0
- [x] Up to 15: each instance enforces 5 on its own
- [ ] Unlimited
> **Answer:** A local fallback keeps *some* protection, not the exact limit. That trade-off is written down in ADR-0006 and in the API description.

## 7. What you learned
- Request IDs, structured JSON logs and Prometheus counters as the minimum observability kit.
- Running a game day with a realistic fault, measuring before and after, and writing a blameless post-mortem.
- The circuit breaker pattern (closed, open, half-open) with deterministic tests.
- Fail open vs fail closed vs local fallback as a per-tenant business decision.
- Health checks that do not amplify a dependency failure.
- Backward-compatible data changes in a key-value store and a semver minor release.

## 8. Catch-up git commands
```bash
git fetch upstream --tags
git switch -c my-phase-8 phase-7-end          # == v1.1.0
git diff v1.0.0 v1.1.0 --stat                 # the whole maintenance change
git show phase-7-end:docs/postmortem-2026-10-02-redis-freeze.md
```
