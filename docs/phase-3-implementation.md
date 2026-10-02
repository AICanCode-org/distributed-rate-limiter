---
phase: 3
slug: phase-3-implementation
title: Implementation in milestones
duration: 14 h
start_tag: phase-2-end
end_tag: phase-3-end
---
# Phase 3 · Implementation in five milestones

## 1. Why it matters
This is where a limiter either keeps its promises or quietly lets traffic through. The tests are
already written: they describe every rule from Phase 1 (the exact token-bucket and sliding-window
maths, the headers, `Retry-After`, costs, resets, usage) and every refusal (no token, a rotated key,
a deleted tenant, a cost above the limit, a tenant id with a `*`). Working test-first, one milestone
at a time, keeps you honest and gives you a green bar to show for each step. It is also where AI
assistants are most tempting and most dangerous: they write a plausible limiter fast, and the bugs
they leave (a `GET` then `SET` around Redis, a float that rounds differently in each language, a
token compared with `==`, `KEYS rg:*` in a reset) only show up under real load.

## 2. Objectives
- Implement RateGuard in **your** track in five milestones:
  **M1** validation + security (API keys, admin token) · **M2** the two algorithms as pure functions ·
  **M3** the Redis key layout and the Redis store (your Lua scripts) · **M4** tenants, the limiter
  service and the HTTP API · **M5** usage statistics and the AI policy advisor.
- Use the pending list to work test-first.
- Keep the HTTP layer thin and the business logic in framework-free services with an injected clock.
- Review AI-generated limiter code critically and catch a planted race with a test.

## 3. Step-by-step instructions

### Step 3.0 · The loop for every milestone
1. Branch: `git switch -c feat/m1-validation-security`.
2. Delete the milestone's lines from the pending list and run the unit tests: **red**.
3. Implement until **green**: `make test`. Then `make lint`.
4. Try it by hand with `curl` (from M4 on).
5. Commit, push, PR, CI green, merge. Then tag your own progress if you like: `git tag my-m1`.

<!-- track:java -->
Pending list: `src/test/java/org/aicancode/rateguard/support/Pending.java`. Files you implement (all
marked `TODO(phase-3 Mx)` with a hint): `domain/Validation`, `security/ApiKeys`, `security/AdminAuth`,
`limiter/Algorithms`, `store/Keys`, `store/RedisStore`, `service/TenantService`,
`service/LimiterService` and in `ai/`: `UsageStats`, `RuleAdvisor`, `LlmAdvisor`, `HttpLlmClient`,
`AdvisorService`. Run one test class: `mvn -q test -Dtest=TokenBucketTest`.
<!-- /track -->

<!-- track:python -->
Pending list: `tests/conftest.py` (`PENDING`). Files you implement (all marked `TODO(phase-3 Mx)` with a
hint): `app/validation.py`, `app/security.py`, `app/algorithms.py`, `app/keys.py`, the `RedisStore`
class in `app/store.py`, `app/tenants.py`, `app/limiter.py` and `app/advisor.py`.
Run one file: `.venv/bin/pytest tests/unit/test_token_bucket.py`.
<!-- /track -->

<!-- track:typescript -->
Pending list: `test/pending.ts` (`PENDING`). Files you implement (all marked `TODO(phase-3 Mx)` with a
hint): `src/validation.ts`, `src/security.ts`, `src/algorithms.ts`, `src/keys.ts`, the `RedisStore`
class in `src/store.ts`, `src/tenants.ts`, `src/limiter.ts` and `src/advisor.ts`.
Run one file: `npx vitest run test/unit/token-bucket.test.ts`.
<!-- /track -->

The HTTP routes, error mapping, configuration, the clock and the **in-memory store** used by the unit
tests are already written (read them: they are short, and the in-memory store shows exactly what each
store method must do; it calls *your* algorithm functions). Once the services work, the API works.
The Redis store is exercised by your `curl` sessions now and by the integration tests in Phase 4. The
stubs removed the imports they no longer need; add them back as you go (your IDE or linter will tell
you which).

### Step 3.1 · M1: validation and security (FR-1, FR-2, FR-3, FR-5, FR-6, FR-9)
Un-pend **validation** and **security**. Every validation rule has an exact error message in the
tests, identical in all three tracks, because clients and the smoke test depend on them. Watch for the
traps the tests check: tenant ids are 3–32 characters of `a-z 0-9 -` with no leading or trailing
hyphen (and nothing else: no `{`, `}`, `*`, `:` or spaces, see ADR-0003); `limit`, `windowSeconds`
and `cost` must be integers and **booleans are not integers** (in Python `True` is an `int`; in
JavaScript `1.0 === 1`, so use `Number.isSafeInteger`); `?minutes=` must be ASCII digits only.

Security is five small functions:
* `new_api_key()` → `rg_` + 32 lowercase hex characters from a **CSPRNG** (`SecureRandom`,
  `secrets`, `crypto.randomBytes`), never `Math.random` or `random`.
* `hash_api_key(key)` → SHA-256 hex; `key_prefix(key)` → the first 9 characters.
* `looks_like_api_key(value)` → the format check, done before any Redis lookup.
* `constant_time_equals(a, b)` and `AdminAuth.require(header)`: `Bearer <token>` compared in
  **constant time**; anything else is `401` with `WWW-Authenticate: Bearer`.

<!-- track:java -->
```java
byte[] expected = token.getBytes(StandardCharsets.UTF_8);
// MessageDigest.isEqual takes the same time however many leading bytes match; String.equals does not.
return MessageDigest.isEqual(expected, given.getBytes(StandardCharsets.UTF_8));
```
<!-- /track -->

<!-- track:python -->
```python
# hmac.compare_digest takes the same time however many leading characters match; == does not.
return hmac.compare_digest(a.encode("utf-8"), b.encode("utf-8"))
```
<!-- /track -->

<!-- track:typescript -->
```ts
const x = Buffer.from(a, "utf8");
const y = Buffer.from(b, "utf8");
// timingSafeEqual throws on different lengths, so check that first (the length is not a secret).
return x.length === y.length && timingSafeEqual(x, y);
```
<!-- /track -->

### Step 3.2 · M2: the algorithms as pure functions (FR-7, FR-8)
Un-pend **token-bucket**, **sliding-window** and **memory-store**. Each algorithm is one function:
`(state or null, limit, window_ms, cost, now) → (new state, decision)`. No Redis, no clock, no I/O,
which makes them perfect for test-first work. They are the same maths as your Lua scripts from
Phase 2, line by line:
* **Token bucket:** a new bucket starts full; refill `(now - ts) × limit / window_ms`, capped at
  `limit`, only when `now > ts` (a clock that goes backwards refills nothing); allowed when
  `tokens ≥ cost`. `retryAfterMs = ceil((cost - tokens) × window_ms / limit)`,
  `resetAfterMs = ceil((limit - tokens) × window_ms / limit)`, `remaining = floor(tokens)`.
* **Sliding window counter:** window index `now // window_ms`; move `cur` to `prev` when one window
  has passed, drop both after two; then, **in integers**,
  `weighted = prev × (window_ms - elapsed) + cur × window_ms` and allow when
  `weighted + cost × window_ms ≤ limit × window_ms`. The tests pin down the two `retryAfterMs` cases
  (fits once the old window slides out / only fits in the next window).

The in-memory store calls these functions, so once they are green, every API test that follows runs
on your maths. Compare your numbers with Step 2.4 of Phase 2: they must be identical.

### Step 3.3 · M3: Redis keys and the Redis store (FR-12, FR-13, ADR-0001 … 0003)
Un-pend **keys**. `keys` builds every Redis key in one place: `rg:tenants`, `rg:tenant:<id>`,
`rg:key:<hash>`, `rg:{<id>}:tb:<resource>`, `rg:{<id>}:sw:<resource>`, `rg:{<id>}:usage`, the
`SCAN` pattern `rg:{<id>}:*` and the usage slot (`minute % 180`).

Then the **Redis store** (no unit tests: the integration tests in Phase 4 cover it, and your `curl`
session in M4 is the first check). The in-memory store is your specification:
1. **Load the Lua scripts** from `LUA_DIR` at start-up and call them by SHA (`EVALSHA`); reload on
   `NOSCRIPT` (your client library may do this for you: find out how).
2. `check` passes `KEYS` = limiter key + usage key and `ARGV` = limit, window_ms, cost (+ `now_ms` only
   when a test gives one) and maps the reply `[allowed, remaining, retryAfterMs, resetAfterMs]`.
3. `create_tenant` decides races with `SADD rg:tenants <id>` (`0` = someone else won → duplicate),
   then writes the hash and the key lookup in one `MULTI`/`EXEC`.
4. `save_tenant` (policy change, rotation) and `delete_tenant` use `MULTI`/`EXEC`; rotation deletes the
   old `rg:key:<hash>` in the same transaction.
5. `reset` and `delete` find limiter keys with **`SCAN MATCH rg:{<id>}:*`**, never `KEYS` (it blocks
   Redis for everyone while it walks the whole key space).
6. Every Redis error and timeout becomes `StoreUnavailable` → `503 limiter-unavailable`.

### Step 3.4 · M4: tenants, the limiter and the API (FR-1 … FR-12, FR-15, FR-16)
Un-pend **tenants**, **limiter**, **api-admin** and **api-limits**.
* `TenantService`: create (new key, hash, prefix, `409` on duplicate), list, get (`404`), set_policy,
  rotate_key, delete, reset and `authenticate(X-API-Key)`: format check, hash, look up, `401
  invalid-api-key` for anything that fails. The plain key leaves the service only in the create and
  rotate results.
* `LimiterService`: validate `{resource?, cost?}`, refuse `cost > limit` with `422` (it could never
  pass, and the client would retry forever), call the store, and build the headers:
  `X-RateLimit-Reset` and `Retry-After` are whole seconds **rounded up** (`Retry-After: 0` would make
  clients retry at once). `enforce` turns a denial into `429 rate-limited` for the demo endpoint.

```bash
docker compose up -d redis && make run      # in tracks/<track>
ADMIN='Authorization: Bearer local-admin-token-change-me-0123456789abcdef'
curl -s -X POST localhost:8080/api/v1/admin/tenants -H "$ADMIN" -H 'Content-Type: application/json' \
     -d '{"id":"acme","name":"Acme Corp","policy":{"algorithm":"TOKEN_BUCKET","limit":5,"windowSeconds":60}}'
# 201 ... "apiKey":"rg_…"   <- shown once; copy it
KEY=rg_paste_it_here
for i in 1 2 3 4 5 6; do curl -s -o /dev/null -w '%{http_code} ' localhost:8080/api/v1/demo/hello -H "X-API-Key: $KEY"; done
# 200 200 200 200 200 429
curl -si -X POST localhost:8080/api/v1/limits/check -H "X-API-Key: $KEY" -H 'Content-Type: application/json' -d '{}'
docker compose exec redis redis-cli --scan --pattern 'rg:*'    # look at your keys; note the {acme} tag
docker compose exec redis redis-cli HGETALL 'rg:{acme}:tb:demo'
```
Now start a **second instance** on port 8081 (`PORT=8081 make run` in another terminal) and repeat the
loop half on each port after a reset (`POST /admin/tenants/acme/reset`): still five `200`s in total.
That is NFR-1 working.

### Step 3.5 · M5: usage statistics and the AI policy advisor (FR-13, FR-14, NFR-7)
Un-pend **advisor** and **api-advice**. Three pieces:
1. **Usage statistics** (`summarize`): total, allowed, denied, denied percent (integer, rounded down),
   active minutes, peak minute and its denials, median and 90th percentile (nearest rank) of the
   minutes that had traffic, and the capacity per minute of the policy (`limit × 60 / windowSeconds`). The tests fix every definition (median of an even count,
   percentile index), because the advisor's texts quote these numbers.
2. **The rule advisor**: five rules in a fixed order: no traffic → `KEEP`; a spike (peak ≥ 5 × median,
   most denials in that minute) → `INVESTIGATE` (a retry storm or abuse is not growth); ≥ 10 % denied →
   `RAISE_LIMIT` to the 90th percentile + 20 %, at most × 10; far below capacity for 10+ minutes →
   `LOWER_LIMIT` to twice the peak; otherwise `KEEP`. The summary texts are checked word for word.
3. **The LLM advisor**: send only the policy and the statistics (numbers, never keys or names) and ask
   for **only** a JSON object. `parse_advice` validates the answer **like untrusted user input**: strip
   a Markdown fence, known action only, a limit for the limit actions only, a raise above the current
   limit and at most × 10, a lower below it, a summary of at most 400 characters. Any failure throws,
   and `AdvisorService` falls back to the rules with `fallback: true`. Advice is returned, never applied.

```bash
curl -s -X POST localhost:8080/api/v1/admin/tenants/acme/advice -H "$ADMIN"
curl -s 'localhost:8080/api/v1/admin/tenants/acme/usage?minutes=5' -H "$ADMIN"
```
Optional, with a local model: `docker compose --profile ollama up -d`, pull `llama3.2:1b`, run your track
with `AI_PROVIDER=llm LLM_BASE_URL=http://localhost:11434/v1` and compare the answers. Then stop Ollama
and call it again: `provider` becomes `rules` and `fallback` `true`, with a `200`.

### Step 3.6 · Review the AI's code (exercise)
An assistant produced this "simpler" token bucket for the Redis store (pseudo-code). Find the bugs
**before** reading on, then point to the test that catches them.
```text
state = redis.hgetall("bucket:" + tenantId + ":" + resource)
tokens = min(limit, state.tokens + (System.now() - state.ts) / 1000.0 * (limit / windowSeconds))
if tokens >= cost:
    redis.hset("bucket:" + tenantId + ":" + resource, {tokens: tokens - cost, ts: System.now()})
    return ALLOWED
return DENIED
```
Four bugs: read-decide-write from the app is **not atomic** (two instances both spend the last token);
the **app clock** is used (drift between instances); `limit / windowSeconds` is **integer division**
in Java when both are `int` (5 / 60 = 0: the bucket never refills); and the key
has **no hash tag and no TTL**, and a resource containing `:` collides with another tenant's key. The
unit tests *"refills continuously"* fail against the maths, and in Phase 4 the integration test
*"concurrent checks never exceed the limit"* shows the race. Note in `AI_LOG.md` how you found it.

### Step 3.7 · Finish
All unit tests green, lint clean, and only the **integration** entry left in the pending list (that is
Phase 4). Compare your diff with the reference: `git diff phase-2-end phase-3-end -- tracks/<track>`.

## 4. AI-assist prompts

### Prompt 1: Implement against the tests, not against a description
```text
Here is a failing unit test file and the stub I must implement: <paste test file> <paste stub>.
Write the smallest implementation that makes these tests pass, in <Java 21 | Python 3.12 | TypeScript
5 (ESM)>. Keep the existing signatures. The functions are pure: no I/O, no clock, no randomness
except where the stub says so. Keep the sliding-window maths in integers. Do not change the tests.
Explain any test you think is wrong instead of working around it.
```
**Verify the output by:** running the tests (`make test`). Read every line before you accept it, and
reject anything that touches the tests, catches all exceptions silently, reads the system clock inside
an algorithm, uses floats in the sliding window, or adds dependencies.

### Prompt 2: Attack my limiter
```text
Act as a penetration tester and a chaos engineer. Here is my limiter code: <paste LimiterService,
TenantService.authenticate, the Redis store's check/reset/rotate and both Lua scripts>. List concrete
ways to get more requests than the policy allows or to affect another tenant: parallel requests from
several instances, negative or huge costs, resources or ids with special characters, a rotated or
deleted key, clock changes, Redis restarts, a reset racing a check. For each, give a one-line test that
would prove the attack fails.
```
**Verify the output by:** writing each suggested test (temporarily, in your branch) and running it.
Keep the ones that expose a real gap and fix the code; discard claims that the existing tests already
disprove. Never "fix" a test to make an attack pass.

### Prompt 3: Explain the Lua before you trust it
```text
Explain step by step what this Redis Lua script does, how it behaves when two clients call it at the
same moment for the same key with 1 token left, what happens when ARGV[4] is missing, and why every
key gets a PEXPIRE: <paste spec/lua/token_bucket.lua>
```
**Verify the output by:** running the script twice in a row with `redis-cli EVAL` and a fixed `now_ms`
(the second call is denied), checking `TTL` on both keys, and running `redis-cli MONITOR` in another
terminal while you do a `curl` check: you must see exactly one `EVALSHA` per check, nothing that reads
the bucket from the app.

### Prompt 4: LLM prompt design for the policy advisor
```text
I send a rate-limit policy and per-minute usage statistics (numbers only) to a small local model
(llama3.2:1b) and ask for ONLY a JSON object {action, limit, summary} with action one of KEEP,
RAISE_LIMIT, LOWER_LIMIT, INVESTIGATE. Suggest a system prompt that keeps the answer short and
grounded in the numbers, and five adversarial or broken answers my parser must reject (e.g. a
limit of 10000000, a negative limit, an unknown action, JSON inside a Markdown fence with extra text,
a summary of 2000 characters).
```
**Verify the output by:** feeding each broken answer to your `parse_advice` in a unit test and checking
that it throws (and that the fenced-but-valid one is accepted). Then call the advice endpoint with
Ollama running and check `provider: "llm"`; record any surprising but valid advice in `AI_LOG.md`.

## 5. Deliverables
- [ ] Pending list contains only the integration entry; `make lint test` green.
- [ ] A `curl` session in the PR showing: create a tenant → five allowed and one `429` on the demo
  endpoint → the `X-RateLimit-*` headers → rotate the key (old key `401`) → reset → the same limit held
  across two instances → usage and advice.
- [ ] The advisor works offline and falls back with an unreachable LLM (`fallback: true`).
- [ ] `AI_LOG.md`: at least one accepted and one rejected AI suggestion, plus the code-review exercise.

## 6. Self-check quiz

### Q1. Why do the algorithm functions take `now` as a parameter instead of reading the clock?
- [ ] It is faster than the system clock
- [x] Tests can replay exact timelines (refill after 12 s, a clock going backwards) without sleeping, and the same function serves Lua parity tests and the in-memory store
- [ ] Redis requires it
- [ ] To support time zones
> **Answer:** Pure functions are deterministic: same inputs, same output. Code that calls `now()` inside can only be tested by waiting.

### Q2. Why is `cost > limit` a `422` instead of a normal denial?
- [ ] Redis cannot store large numbers
- [x] Such a request can never pass, so a denial with a `Retry-After` would make a well-behaved client retry forever
- [ ] Costs above the limit are a security attack
- [ ] The contract does not allow `allowed: false`
> **Answer:** A denial says "later". A request that can never succeed needs a different, permanent answer the client can act on.

### Q3. Why compare the admin token with `hmac.compare_digest` / `MessageDigest.isEqual` / `timingSafeEqual` instead of `==`?
- [ ] It is shorter to write
- [x] `==` stops at the first different character, so response times reveal how much of a guessed token was right
- [ ] `==` does not work on long strings
- [ ] The constant-time functions also check the `Bearer` prefix
> **Answer:** Timing attacks recover a secret value byte by byte. Constant-time comparison removes the signal.

### Q4. Why does `reset` use `SCAN MATCH rg:{acme}:*` instead of `KEYS rg:{acme}:*`?
- [ ] `KEYS` does not support patterns
- [x] `KEYS` walks the whole key space in one blocking command, stalling every other client; `SCAN` walks it in small steps
- [ ] `SCAN` is atomic
- [ ] `KEYS` cannot read hash tags
> **Answer:** Redis is single-threaded: one slow command delays every limiter check of every tenant. `SCAN` trades a few round trips for never blocking.

### Q5. `Retry-After` for a `retryAfterMs` of 1 200 ms. What is the header?
- [ ] `1`
- [x] `2`
- [ ] `1.2`
- [ ] `0`
> **Answer:** Whole seconds, rounded **up**. A client that waits 1 s retries too early and is denied again; `0` would mean "retry now".

### Q6. What must `POST /admin/tenants/{id}/advice` do when the LLM endpoint is unreachable?
- [ ] Return `502 Bad Gateway`
- [ ] Return `KEEP` without explanation
- [x] Answer with the rule advisor, `200`, `provider: "rules"` and `fallback: true`
- [ ] Return `503` until the LLM is back
> **Answer:** The AI is an optional layer on a deterministic baseline (NFR-7). Admins always get advice, and `fallback` makes the degradation visible.

### Q7. In the AI-generated token bucket, which bug lets two instances admit more than the limit even with perfect single-instance tests?
- [ ] The missing TTL
- [x] Reading the bucket and writing it back in separate commands from the app
- [ ] Using `HGETALL`
- [ ] Returning `DENIED` without a retry time
> **Answer:** Two instances both read 1 token and both write 0. Only an atomic script (or a transaction that retries) closes the race; the Lua script does it in one round trip.

## 7. What you learned
- Test-first development with a pending list: red → green → refactor, one milestone at a time.
- Two rate-limiting algorithms as pure functions, with integer maths that three languages agree on.
- API keys from a CSPRNG stored as hashes, and constant-time comparison of secrets.
- A Redis store built on atomic Lua scripts, `MULTI`/`EXEC` for multi-key writes, `SADD` as a race judge and `SCAN` instead of `KEYS`.
- Standard rate-limit headers and why `Retry-After` is rounded up.
- Integrating an LLM behind an interface with a timeout, a strict parser and a deterministic fallback.

## 8. Catch-up git commands
```bash
git fetch upstream --tags
git switch -c my-phase-4 phase-3-end                       # everything implemented, integration pending
git diff phase-2-end phase-3-end --stat
git checkout phase-3-end -- tracks/<track>/src             # (Java, TypeScript) take one track's reference code
git checkout phase-3-end -- tracks/<track>/app             # (Python)
```
