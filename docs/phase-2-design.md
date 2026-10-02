---
phase: 2
slug: phase-2-design
title: Design (algorithms, Lua scripts, key layout, failure policy, API contract)
duration: 3.5 h
start_tag: phase-1-end
end_tag: phase-2-end
---
# Phase 2 · Design: algorithms, Lua scripts, key layout, failure policy, API contract

## 1. Why it matters
Design is where you make the expensive decisions on purpose: which algorithm defines "100 per
minute", where the decision is made so that three instances cannot over-admit, whose clock counts,
how keys are laid out so the data can move to a cluster later, how credentials are stored, and what
the service does when Redis is not there. Writing them down as diagrams and **Architecture Decision
Records (ADRs)** lets a reviewer follow your reasoning in minutes. The two **Lua scripts** are the one
artefact shared by all three tracks, so a mistake here costs three times, and a read-then-write
outside a script is an over-admission bug waiting for a traffic spike.

## 2. Objectives
- Draw context, container and check-sequence diagrams and a Redis key table in Mermaid / Markdown.
- Compare token bucket, sliding window log, sliding window counter and fixed window, and pick two.
- Write five ADRs: atomic Lua scripts with pure-function twins, Redis `TIME` as the clock, hash tags in
  keys, fail closed, hashed API keys + constant-time admin token.
- Write `spec/lua/token_bucket.lua` and `spec/lua/sliding_window.lua` and run them by hand with `redis-cli`.
- Read the given OpenAPI contract and map each endpoint to a requirement.

## 3. Step-by-step instructions

### Step 2.1 · Diagrams in `spec/architecture.md`
Mermaid is text, so it diffs and reviews like code. Start with a container diagram:
```text
flowchart TB
    lb["Load balancer"] --> a1["RateGuard (Java)"] & a2["RateGuard (Python)"] & a3["RateGuard (TS)"]
    a1 & a2 & a3 -- "EVALSHA (atomic)" --> redis[("Redis 7")]
    a1 & a2 & a3 -. "advice only, timeout + fallback" .-> llm[("LLM endpoint")]
```
Wrap it in a code fence with the language `mermaid` and preview it on GitHub or in VS Code (extension
"Markdown Preview Mermaid Support"). Then draw:
1. **The check sequence**: key lookup, tenant lookup, one script call, the headers; then two instances
   racing for the last token. Where exactly is the race decided?
2. **A key table**: every Redis key, its type, its TTL and who writes it.
3. **Failure modes**: Redis refused, Redis frozen, Redis restarted, scripts flushed, the LLM down.
   For each, what does the client see?

### Step 2.2 · Choose the algorithms
Work these four by hand for a limit of 3 per 60 s and requests at 0:59, 0:59, 0:59, 1:01:

| Algorithm | State | 4th request at 1:01? | Memory |
|---|---|---|---|
| Fixed window (`INCR` + `EXPIRE`) | one counter per window | allowed: a new window started (6 in 2 s!) | tiny |
| Sliding window log | a sorted set of every timestamp | denied | one entry per request |
| Sliding window counter | `cur`, `prev`, window index | denied, retry in 19 s | 3 numbers |
| Token bucket | `tokens`, `ts` | allowed only if a token refilled | 2 numbers |

RateGuard offers the **token bucket** (allows bursts after idle time, the most common API policy) and
the **sliding window counter** (smooth, never more than the limit in a window, constant memory), per
tenant. Write the exact maths into `architecture.md`. Two details decide whether three languages
agree: the sliding window keeps its maths in **integers** by multiplying everything by `window_ms`,
and both algorithms must say what happens when the clock goes **backwards**.

### Step 2.3 · ADRs
Copy `spec/adr/0000-template.md` five times:
1. `0001-atomic-lua-scripts-with-pure-function-twins.md`: read-decide-write from the app vs
   `WATCH`/`MULTI` vs `INCR` vs a Lua script. Why also keep the same maths as pure functions?
2. `0002-redis-time-as-the-only-clock.md`: what goes wrong when instance clocks differ by 300 ms?
   Why does the script still accept an optional `now_ms`?
3. `0003-hash-tags-in-limiter-keys.md`: what is `CROSSSLOT`, and how does `rg:{acme}:tb:export`
   avoid it? What must tenant ids and resources never contain?
4. `0004-fail-closed-when-redis-is-unavailable.md`: fail open vs fail closed vs a local fallback. Who
   pays for each choice?
5. `0005-hashed-api-keys-and-constant-time-admin-token.md`: clear text vs bcrypt vs SHA-256 of a
   128-bit random key. Why is a fast hash fine here when it is wrong for passwords?

Each ADR is one page: context, options with pros/cons, decision, consequences.

### Step 2.4 · Write the Lua scripts → `spec/lua/`
Read `spec/lua/README.md`: it fixes the calling convention every track will use (`KEYS[1]` limiter
state, `KEYS[2]` usage ring, `ARGV` limit, window_ms, cost and an optional `now_ms`; the result
`{allowed, remaining, retry_after_ms, reset_after_ms}`). Each script must read, decide and write in
one call, use `redis.call('TIME')` unless `now_ms` is given, set a TTL on every key it writes, and
count the request in a ring of 180 one-minute slots (`m<slot>`, `a<slot>`, `d<slot>` with
`slot = minute % 180`).

Run them by hand with fixed clocks (database 9, so nothing else is touched):
```bash
R="docker compose exec -T redis redis-cli -n 9"
for t in 1000 1000 1000 1000 1000 1000 13000; do
  $R EVAL "$(cat spec/lua/token_bucket.lua)" 2 'rg:{demo}:tb:default' 'rg:{demo}:usage' 5 60000 1 $t | tr '\n' ' '; echo
done
# 1 4 0 12000 / 1 3 0 24000 / 1 2 0 36000 / 1 1 0 48000 / 1 0 0 60000
# 0 0 12000 60000      <- the 6th request: denied, retry in 12 s (one token per 60000/5 ms)
# 1 0 0 60000          <- 12 s later one token has refilled
for t in 59000 59000 59000 61000 75000 80000; do
  $R EVAL "$(cat spec/lua/sliding_window.lua)" 2 'rg:{demo}:sw:default' 'rg:{demo}:usage' 3 60000 1 $t | tr '\n' ' '; echo
done
# three allowed at 0:59, then at 1:01: "0 0 19000 …" (denied; a fixed window would allow it)
$R TTL 'rg:{demo}:tb:default'; $R HGETALL 'rg:{demo}:usage'; $R FLUSHDB
```
If your numbers differ, find out which of you is right by doing the maths on paper. The same
sequences become integration tests in Phase 4, against your pure functions.

### Step 2.5 · Map the contract
For every path in `spec/openapi.yaml`, write the FR number next to it (a table in `architecture.md`).
Any endpoint without a requirement, or any requirement without an endpoint, is a design bug: fix one of
the two. Check that every admin path declares `security: [admin]` and the tenant paths
`security: [apiKey]`, and that `/health` declares none. Validate the file:
```bash
pipx run openapi-spec-validator spec/openapi.yaml       # or: npx @redocly/cli lint spec/openapi.yaml
```

### Step 2.6 · Commit
```bash
git switch -c docs/phase-2-design
git add spec/
git commit -m "docs(spec): architecture, ADRs 0001-0005, Lua scripts"
```

## 4. AI-assist prompts

### Prompt 1: Mermaid sequence diagram for two instances racing
```text
Write a Mermaid sequenceDiagram with participants InstanceA, InstanceB and Redis for a rate limiter
whose decision is a Lua script: both instances call EVALSHA token_bucket for the same tenant at the
same moment while 1 token is left. Show that Redis runs the scripts one after the other: A gets
{allowed=1, remaining=0}, B gets {allowed=0, retry_after_ms=12000}. Then add a second diagram of the
broken version (GET tokens, decide in the app, SET tokens) where both are allowed. Keep messages short.
```
**Verify the output by:** pasting both into https://mermaid.live (they must render), then checking that
the correct version has **no** read in the app before the script call and the broken one shows the
interleaving (A reads, B reads, A writes, B writes). If the assistant adds a lock (Redlock, `SETNX`) to
the correct version, it has not understood why the script is enough.

### Prompt 2: Review my Lua script
```text
Review this Redis Lua script for a token-bucket rate limiter: <paste spec/lua/token_bucket.lua>.
Calling convention: <paste spec/lua/README.md>. Check: the whole decision is inside the script; the
clock is redis.call('TIME') unless ARGV[4] is given; refill is capped at the limit; cost > tokens is
denied without changing the stored tokens; retry_after_ms and reset_after_ms are rounded up; every
written key gets a TTL; nothing loops over keys; no global variables. Give a concrete request
sequence for every bug you claim.
```
**Verify the output by:** running each claimed failing sequence with `redis-cli EVAL` and a fixed
`now_ms` (Step 2.4). Keep only claims you can reproduce. Reject suggestions that call `KEYS`, that move
the clock into the app, or that use floats in the sliding window (three languages round floats
differently).

### Prompt 3: Steel-man the option I rejected
```text
For a multi-tenant rate limiter with several app instances sharing one Redis, argue as convincingly as
you can FOR failing OPEN (admitting all requests) when Redis is unavailable, instead of failing
closed with 503. Then list when failing open is genuinely the better choice. Keep it under 250 words.
```
**Verify the output by:** checking each claim against your ADR-0004 (who is harmed in each case, how
long outages last, what an abusive tenant does during one) and recording the strongest
counter-argument in the ADR's *Consequences*. A good ADR admits what the rejected option does better;
you will come back to this one in Phase 7.

## 5. Deliverables
- [ ] `spec/architecture.md` with context, container and check-sequence diagrams, the algorithm table,
  the key table and the failure modes, rendering on GitHub.
- [ ] `spec/adr/0001…0005` accepted.
- [ ] `spec/lua/token_bucket.lua` and `spec/lua/sliding_window.lua` giving the numbers in Step 2.4.
- [ ] Every OpenAPI path mapped to a requirement.
- [ ] Compare: `git diff phase-1-end phase-2-end --stat`.

## 6. Self-check quiz

### Q1. Why is a Lua script safe when `GET` + decide in the app + `SET` is not?
- [ ] Lua is faster than Java, Python or TypeScript
- [x] Redis runs the whole script without running any other command in between, so two instances cannot both see "1 token left"
- [ ] Redis caches the GET
- [ ] Scripts use a distributed lock internally
> **Answer:** With read-decide-write, two requests can read 1 at the same time and both write 0. Inside a script the read and the write are one atomic step, and the second caller sees 0 and is denied.

### Q2. A limit of 3 per 60 s, fixed window. Requests at 0:59 (×3) and 1:00 (×3). How many are allowed?
- [ ] 3
- [ ] 4
- [x] 6
- [ ] 0
> **Answer:** A fixed window resets at 1:00, so 6 requests pass within one second: twice the limit. The sliding window counter weights the previous window and denies the second burst.

### Q3. Why do the scripts read `redis.call('TIME')` instead of receiving the time from the app?
- [ ] Lua has no clock
- [x] App instances' clocks drift; one clock in Redis makes every instance agree about "now"
- [ ] TIME is more precise than the app clock
- [ ] Redis Cluster requires it
> **Answer:** If instance A is 300 ms ahead of instance B, B can "un-refill" a bucket A just refilled. `now_ms` exists only so tests are deterministic (ADR-0002).

### Q4. Why are the limiter keys named `rg:{acme}:tb:export` with braces?
- [ ] Braces make keys sort first
- [x] In Redis Cluster only the part in braces is hashed, so all keys a script touches for one tenant land in the same slot
- [ ] Braces are required for hash-type keys
- [ ] To hide the tenant id
> **Answer:** A script may only touch keys of one slot; without the hash tag the bucket and the usage ring could be on different nodes and the call would fail with `CROSSSLOT` (ADR-0003).

### Q5. Why is SHA-256 acceptable for API keys when it is wrong for passwords?
- [ ] SHA-256 is slower than bcrypt
- [x] The keys are 128 random bits, so there is no dictionary or brute force to slow down, and a fast hash keeps every check cheap
- [ ] API keys are not secrets
- [ ] Redis cannot store bcrypt hashes
> **Answer:** Passwords are short and guessable, so they need a slow hash. A random 128-bit key cannot be guessed at any speed; what matters is that a leaked Redis dump does not contain usable keys (ADR-0005).

### Q6. Redis freezes for 30 seconds. What does v1.0 answer for a check?
- [ ] `200` with `allowed: true`
- [ ] `429` with `Retry-After`
- [x] `503 limiter-unavailable` with `Retry-After: 1`, after the 1 s command timeout
- [ ] Nothing: the request hangs until Redis is back
> **Answer:** ADR-0004 chooses to fail closed: the protection never disappears silently. Phase 7 measures what this costs in a game day and refines it.

## 7. What you learned
- Diagrams-as-code with Mermaid: context, containers, sequences, a key table.
- The four classic rate-limiting algorithms, their state, their burst behaviour and their error.
- Atomicity with Redis Lua scripts, and why one shared clock matters in a distributed system.
- Key design for Redis Cluster (hash tags), TTLs everywhere, and input validation as a key-safety measure.
- Writing ADRs that admit what the rejected option does better.

## 8. Catch-up git commands
```bash
git fetch upstream --tags
git switch -c my-phase-3 phase-2-end
git diff phase-1-end phase-2-end -- spec/        # the reference design
git checkout phase-2-end -- spec/lua/            # take only the Lua scripts
```
