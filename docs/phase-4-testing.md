---
phase: 4
slug: phase-4-testing
title: Testing (unit, integration, concurrency, coverage)
duration: 4 h
start_tag: phase-3-end
end_tag: phase-4-end
---
# Phase 4 · Testing: Lua parity, concurrency across instances, security, coverage gate

## 1. Why it matters
Unit tests with an in-memory store prove your maths. They cannot prove that your Lua scripts do the
**same** maths, that a check is really atomic when forty requests arrive at once from two app
instances, that keys expire, that a reset touches one tenant only, or that the store survives a
`SCRIPT FLUSH`. Integration tests against a **real** Redis do. A load test then shows the property
the whole project exists for: however requests are spread over instances, exactly `limit` pass. A
coverage gate stops the suite from quietly rotting. Together they let you change code (or accept an
AI's refactor) without fear, which is exactly what you need before CI in Phase 5.

## 2. Objectives
- Explain the test pyramid for RateGuard: unit (fast, many), integration (real Redis), load and smoke (black-box, few).
- Run the integration suite against the Compose Redis using a **separate** database (15).
- Prove that the Lua scripts and the pure functions agree step by step, and that parallel checks
  never exceed the limit, from one and from two app instances.
- Run the load test against two instances and read its latency numbers.
- Enforce ≥ 85 % line coverage locally and understand what the number does and does not mean.

## 3. Step-by-step instructions

### Step 4.1 · Know the layers you already have
| Layer | Where | Needs | Run |
|---|---|---|---|
| Unit | `…/unit/…`, in-memory store, fake clock, fake LLM | nothing | `make test` |
| Integration | `…/integration/…`, the Redis store and the real app against real Redis | `docker compose up -d redis` | `make test-integration` |
| Load | `scripts/load-test.py`, two running instances | two apps + Redis | `python3 scripts/load-test.py` |
| Smoke (black-box) | `scripts/smoke-test.sh` (Phase 6) | a running app | `scripts/smoke-test.sh` |

The integration tests use `TEST_REDIS_URL` (default `redis://localhost:6379/15`) and run `FLUSHDB`
before each test, so they **empty database 15, never your dev data in database 0**. Never point
`TEST_REDIS_URL` at a Redis that is not yours: a shared Redis on the same port would lose database 15.

### Step 4.2 · Turn the integration tests on
Delete the last line of your pending list (the integration entry), then:
```bash
docker compose up -d redis
make test-integration
```

<!-- track:java -->
Java: `RedisStoreIntegrationTest` and `ApiIntegrationTest` are tagged `@Tag("integration")` and excluded
by default; `-Pintegration` selects them (`mvn verify -Pintegration`). `ApiIntegrationTest` boots the
**whole** Spring app on a random port (`@SpringBootTest(webEnvironment = RANDOM_PORT)`) and calls it
over HTTP; `@DynamicPropertySource` points it at database 15 and sets a test-only admin token.
<!-- /track -->

<!-- track:python -->
Python: tests marked `pytest.mark.integration` are deselected by default (`addopts` in
`pyproject.toml`); `make test-integration` runs `pytest -m integration`. The store tests use a
`RedisStore` on database 15; the API tests build the real app with `create_app(Settings(...))` and
drive it with FastAPI's `TestClient`.
<!-- /track -->

<!-- track:typescript -->
TypeScript: integration tests live in `test/integration/` and run with `npm run test:integration`
(vitest). They use a real `RedisStore` on database 15 (`beforeEach` runs `FLUSHDB`) and build the real
Fastify app on it with `makeApp()`, driven with `app.inject()`.
<!-- /track -->

### Step 4.3 · Read the parity, concurrency and security tests
Open the integration file and find these cases. Each maps to a requirement or an ADR:
- **the Lua script and the pure function agree step by step** (ADR-0001), for both algorithms: the
  same sequence of `(now, cost)` pairs, with a fixed `now_ms`, must give identical decisions.
- **concurrent checks never exceed the limit** (NFR-1): many threads / promises check at once against a
  small limit; exactly `limit` are allowed.
- **two app instances share one limit** (NFR-1): two store objects (two "instances") on one Redis.
- **exactly one of several racing creates wins** (FR-2): `SADD` decides, the rest get a duplicate.
- **every key has a TTL and usage is recorded** (NFR-6, FR-13), **the usage ring reads the current
  minutes** (FR-13).
- **reset forgets one tenant only** (FR-12, ADR-0003).
- **the Redis clock is used without an override** (ADR-0002).
- **scripts are reloaded after `SCRIPT FLUSH`** (failover, restart without persistence).
- **an unreachable Redis is a store-unavailable error** (NFR-4) and **the whole API runs on Redis**,
  including parallel HTTP requests against the demo endpoint.

### Step 4.4 · Prove one test can fail
A test you have never seen fail proves nothing. Pick the concurrency test and break the code on
purpose in a scratch branch: replace the `EVALSHA` in your store's `check` with "read the hash, call
your pure function in the app, write the hash back". Run `make test-integration` (repeat a few times if
needed): it must go red, for the right reason (more allowed than the limit). Restore it. Then change
one line of `token_bucket.lua` (for example drop the `math.min` cap) and watch the parity test fail.

### Step 4.5 · Load test: two instances, one limit
```bash
# terminal 1 and 2 (next to docker compose up -d redis), in tracks/<track>:
PORT=8081 make run
PORT=8082 make run
# terminal 3, from the repository root:
python3 scripts/load-test.py http://localhost:8081 http://localhost:8082
# 400 checks, 40 at a time, over 2 instance(s) ... allowed 100, denied 300, errors 0 ... p50 / p95 / max
```
Run it with `LOAD_ALGORITHM=SLIDING_WINDOW` too, and try one instance of **another track** as the
second URL: the Lua scripts are shared, so a Java and a Python instance enforce one limit together.
Write the p95 latency into your PR: it is your evidence for NFR-3.

### Step 4.6 · Coverage gate
```bash
make coverage        # unit + integration, fails below 85 % line coverage
```

<!-- track:java -->
JaCoCo: `mvn verify -Pcoverage`; HTML report in `target/site/jacoco/index.html`; the rule is in the
`coverage` profile of `pom.xml`.
<!-- /track -->

<!-- track:python -->
pytest-cov: `--cov-fail-under=85` in the Makefile; `coverage.xml` is written for CI; add
`--cov-report=html` for a browsable report.
<!-- /track -->

<!-- track:typescript -->
@vitest/coverage-v8: `thresholds: { lines: 85 }` in `vitest.config.ts`; report in `coverage/`.
<!-- /track -->

Look at the uncovered lines. Are they untested **behaviour** (write a test) or genuinely unreachable
glue (fine)? 100 % is not the goal. Tested behaviour is. In a limiter, an uncovered `if` that
refuses something (a key, a cost, an id) is worth a test every time.

### Step 4.7 · Commit
```bash
git switch -c test/phase-4-integration
git add -A
git commit -m "test: enable integration suite, load test and coverage gate"
```

## 4. AI-assist prompts

### Prompt 1: Missing integration scenarios
```text
These are my integration tests for a multi-tenant rate limiter backed by Redis Lua scripts: <paste
test names>. These are my requirements and threats: <paste FR/NFR/T>. Which requirements are only
covered by unit tests with an in-memory store, where the fake could hide a real bug (Lua maths,
atomicity, TTLs, key patterns, script loading, timeouts)? Propose up to 3 new integration tests with
their exact assertions.
```
**Verify the output by:** writing one of the proposed tests, making it fail on purpose (break the Lua
or the store), and confirming it fails for the right reason before you restore the code.

### Prompt 2: Is this test flaky?
```text
This test sometimes fails in CI but passes locally: <paste test + failure output>. List the likely
sources of non-determinism (the Redis clock crossing a window or minute boundary, TTLs, ordering of a
SET, shared state between tests, async, concurrency) and how to make the test deterministic without
adding sleeps.
```
**Verify the output by:** running the test 20 times in a loop
(`for i in $(seq 20); do make test-integration || break; done`) before and after the change. Prefer a
fixed `now_ms` over waiting for "a safe moment".

### Prompt 3: Write a race test
```text
I want an integration test proving that when 50 checks run concurrently against a token bucket with a
limit of 10, exactly 10 are allowed, split over two store instances that share one Redis. Here is my
test setup: <paste>. Write the test in <pytest | Vitest | JUnit 5> using real concurrency (threads or
Promise.all), and explain why it would fail if the check were a read in the app followed by a write.
```
**Verify the output by:** replacing the script call with read-then-write and checking that the test
fails at least once in 10 runs, then restoring it and checking it passes 20 times in a row. A race test
that never fails without the fix is not testing the race.

## 5. Deliverables
- [ ] Pending list empty; `make test-integration` passes against the Compose Redis.
- [ ] `make coverage` passes (≥ 85 %), with a note in the PR on what the uncovered lines are.
- [ ] Evidence in the PR that the concurrency test fails when the script call is replaced by read-then-write.
- [ ] The load-test output for two instances (`allowed` = the limit, p95 latency).
- [ ] `AI_LOG.md`: one entry about a test an assistant suggested and how you validated it.

## 6. Self-check quiz

### Q1. Which bug can ONLY an integration test catch here?
- [ ] A wrong error message for a too-long tenant name
- [x] A typo in `sliding_window.lua` that makes it disagree with the pure function at a window boundary
- [ ] A wrong rounding of `Retry-After`
- [ ] A missing admin-token check in an endpoint
> **Answer:** Unit tests use the in-memory store, which calls the pure functions; the Lua never runs there. The other bugs live in code the unit tests already exercise.

### Q2. Why does the parity test pass a fixed `now_ms` to the script?
- [ ] Redis `TIME` is not precise enough
- [x] So the script and the pure function see exactly the same timeline and any difference is a maths bug, not timing noise
- [ ] Production also passes `now_ms`
- [ ] Lua cannot read the clock in tests
> **Answer:** Determinism makes failures meaningful. A separate test checks that, without the override, the script really uses the Redis clock.

### Q3. Why does the concurrency test need real parallel requests?
- [ ] Sequential requests are too slow
- [x] Over-admission only appears when two checks read the same state before either writes
- [ ] Redis rejects sequential requests
- [ ] To test the connection pool size
> **Answer:** Sequentially, the second check already sees the lower token count. Atomic execution of the script is what makes the parallel case safe, and only a parallel test shows it.

### Q4. Your coverage is 93 %. What does that prove?
- [ ] The code has no concurrency bugs
- [ ] All requirements are implemented
- [x] 93 % of lines executed during the tests. It says nothing about whether the assertions are meaningful
- [ ] The integration tests are not needed
> **Answer:** Coverage finds *untested* code; it cannot find *wrong* tests. Use it as a floor, not a target.

### Q5. Why must `TEST_REDIS_URL` never point at a Redis you do not own?
- [ ] The tests would be slower
- [x] The tests run `FLUSHDB` on database 15 before every test, deleting whatever someone else stored there
- [ ] Redis allows only one client
- [ ] The tests change the Redis configuration
> **Answer:** Destructive test setup is fine on an isolated database and an incident anywhere else. Check the port before you run the suite on a machine with other projects.

### Q6. Why break the code on purpose and watch a test fail?
- [ ] To practise git
- [x] A test that cannot fail gives false confidence; seeing it go red proves it checks the behaviour
- [ ] The coverage tool requires it
- [ ] To make CI slower
> **Answer:** It is a cheap form of mutation testing. Assistants often write tests that pass against any implementation.

## 7. What you learned
- The test pyramid and what each layer can and cannot prove.
- Parity tests: two implementations of the same maths kept in step by one shared timeline.
- Real-Redis integration tests with an isolated database and clean state per test.
- Testing atomicity where it matters: parallel requests from several instances against one Redis.
- Load testing for a property (the limit holds) and a number (p95 latency).
- Seeing a test fail on purpose, and coverage gates as a floor, not a goal.

## 8. Catch-up git commands
```bash
git fetch upstream --tags
git switch -c my-phase-5 phase-4-end
git diff phase-3-end phase-4-end --stat    # the pending-list change + scripts/load-test.py: the tests were there all along
```
