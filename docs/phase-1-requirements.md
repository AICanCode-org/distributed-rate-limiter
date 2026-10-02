---
phase: 1
slug: phase-1-requirements
title: Requirements, threats and user stories
duration: 2.5 h
start_tag: phase-0-end
end_tag: phase-1-end
---
# Phase 1 · Requirements, threats and user stories

## 1. Why it matters
Most failed projects fail before the first line of code: nobody wrote down what "done" means. A
rate limiter adds two questions that are easy to skip and expensive to get wrong: **what exactly is
"100 per minute"** (a fixed minute? any 60 seconds? may a client burst?) and **what happens when
the limiter itself is in trouble**? A short, testable requirements document with an explicit threat
list gives every test a reason to exist, lets you say *no* to scope creep, and is the first thing an
interviewer asks about ("design a rate limiter" is one of the most common system-design questions).
AI assistants are great at drafting requirements, and just as good at inventing requirements nobody
asked for. Your job is to keep the ones you can test.

## 2. Objectives
- Turn a one-paragraph brief into numbered **functional** (FR) and **measurable non-functional** (NFR) requirements.
- Map personas (and one system: the tenant's backend instances) to **credentials** and write down who may do what.
- List at least six **threats** (abuse cases and failure cases) and the requirement that answers each.
- Write 8–10 **user stories** with Given/When/Then acceptance criteria and create them as GitHub Issues.
- Write an explicit **out-of-scope** list and a **back-of-the-envelope estimate** of checks per second and Redis load.

## 3. Step-by-step instructions

### Step 1.1 · Read the brief and the contract
> *Build RateGuard: a rate-limiting service for a small API company whose customers ("tenants") share
> one backend. The operator creates tenants, gives each an API key and a policy ("100 requests per
> minute"), can change policies and replace leaked keys at any time, and wants to see who uses how
> much. The company's backend runs on several instances, in more than one language, and asks
> RateGuard before doing work; the limit must hold for the tenant as a whole. Clients must be told
> exactly when to retry. Some operations cost more than others. AI may suggest better policies from
> the usage numbers, but must never change anything by itself, and the limiter must work without it.
> It must run on a laptop for free.*

Open `spec/openapi.yaml`. The HTTP contract is **given** so the three tracks stay compatible; your
requirements must be consistent with it (status codes, limits, the `problem+json` error format, the
two `securitySchemes`, the `X-RateLimit-*` and `Retry-After` headers).

### Step 1.2 · Personas and the permission matrix
Write at least three personas, e.g. *Meera, the operator*; *Ravi, a developer at a tenant*; and **the
API company's backend instances**: not people, but the callers of `/limits/check` on every request.
Then fill a matrix: rows = actions (health, create/read/delete tenants, change a policy, rotate a key,
reset, usage, advice, check, demo), columns = anonymous, tenant key, admin token. Every "no" in this
table becomes a test in Phase 3. Notice that the check request contains **no tenant id**: the key
decides which tenant you are. Ask yourself why that removes a whole class of bugs.

### Step 1.3 · Functional requirements
In `spec/requirements.md` write FR-1 … FR-n as "The system shall …" statements. Each needs a number
or a status code, so a test can check it. Bad: *"Limits are enforced fairly."* Good: *"With a token
bucket of 5 per 60 s, the 6th check within the same second is denied with `retryAfterMs` = 12 000."*
Cover admin authentication, tenants and policies, API keys (issue once, hash, rotate), the two
algorithms, the check and demo endpoints, the headers, reset, usage, the advisor, errors and health.

The algorithms deserve their own requirements. Write down **what state each keeps** and **the exact
decision rule**: a token bucket refills `limit` tokens per window continuously and starts full; a
sliding window counter weights the previous fixed window by how much of it is still inside the
sliding window. If you leave this open, three tracks will disagree by one request at a window edge
and every test that counts "allowed" becomes flaky.

### Step 1.4 · Non-functional requirements and threats
Cover correctness across instances, clocks, latency, behaviour when Redis is slow or down, security,
memory, AI independence, cost and portability. Make each one measurable: *"with 3 instances and 50
parallel checks against a limit of 10, exactly 10 are allowed"*, *"a check adds < 5 ms p95"*, *"a
Redis command slower than 1 000 ms is a failure, and a failure is never an 'allowed'"*.

Then the threat list. For each abuse or failure case, name the requirement that stops it:

| Threat | Answer |
|---|---|
| A Redis backup leaks every tenant's API key | store only a SHA-256 hash of each key (FR-5) |
| Two instances both admit the "last" request | one atomic decision in Redis per check (NFR-1) |
| `{"cost": -5}` refills the bucket | cost validated 1–1 000, cost > limit is `422` (FR-9) |
| A tenant id like `acme*` or `a}{b` reaches other tenants' keys | strict id and resource character sets (FR-2, FR-9) |
| A leaked key keeps working | rotation refuses the old key immediately (FR-5) |
| Redis hangs and every request waits forever | timeout + fail closed with `503` and `Retry-After` (NFR-3, NFR-4) |

### Step 1.5 · User stories → GitHub Issues
For each story use the **User story** issue template (`.github/ISSUE_TEMPLATE/user_story.yml`). It asks
for the story, the acceptance criteria and the Phase 3 milestone. Label them `user-story`. With the
GitHub CLI:
```bash
gh issue create --template user_story.yml      # or use the web UI: Issues → New issue
```
Write at least one acceptance criterion per story that describes a **refusal or a failure** (no
token, a rotated key, the 6th request, a cost above the limit, Redis down), not only the happy path.

### Step 1.6 · Out of scope + estimates
List what v1 will **not** do (billing, monthly quotas, per-user limits inside a tenant, a proxy mode,
Redis Cluster setup, a dashboard, automatic policy changes…). Then estimate: tenants, resources per
tenant, peak checks/s, Redis commands per check, Redis commands/s, memory per bucket and per usage
history, and the latency budget. Write one sentence on what the numbers mean for the design
(spoiler: one small Redis is plenty; the hard parts are **atomicity across instances** and **what
happens when Redis is not there**).

### Step 1.7 · Commit
```bash
git switch -c docs/phase-1-requirements
git add spec/requirements.md AI_LOG.md
git commit -m "docs(spec): requirements, threats and user stories"
git push -u origin docs/phase-1-requirements      # open a PR, wait for CI, merge
```
Compare with the reference: `git diff phase-0-end phase-1-end -- spec/requirements.md`.

## 4. AI-assist prompts

### Prompt 1: Draft requirements from the brief
```text
Here is a product brief: <paste the brief> and an OpenAPI contract: <paste spec/openapi.yaml>.
Draft numbered functional requirements ("The system shall ...") and measurable non-functional
requirements for v1 of a multi-tenant rate-limiting service. Every requirement must be testable and
consistent with the contract (quote the status code, header or limit). Define the token bucket and
the sliding window counter precisely enough that two programmers would get the same answer for the
same sequence of requests. Do not add features that are not in the brief or the contract.
```
**Verify the output by:** checking every number, header and status code against `spec/openapi.yaml`,
deleting anything not in the brief (AI loves adding dashboards, billing and "per-user limits"), and
working one algorithm by hand: limit 5 per 60 s, 6 requests in one second, then one more 12 s later.
If your FR does not tell you the answers, it is not precise enough.

### Prompt 2: Threat-model the limiter
```text
Act as a security and reliability reviewer for a multi-tenant rate-limiting service (contract:
<paste>). Tenants authenticate with an API key, the operator with one admin bearer token, state lives
in one Redis shared by several app instances. List the 8 most likely abuse or failure cases (include
concurrency between instances, clock drift, Redis being slow or down and input that becomes part of a
Redis key, not only attackers). For each: the scenario in one sentence, the impact, and a concrete,
testable countermeasure. Name the OWASP API Security Top 10 category where one applies.
```
**Verify the output by:** looking up each named OWASP API Top 10 category on owasp.org (assistants
sometimes misname them), keeping only cases that apply to *this* contract, and making sure each kept
countermeasure appears as an FR or NFR. A countermeasure like "use a distributed lock (Redlock) around
every check" is a sign the assistant is pattern-matching: it costs several round trips per request
and is unnecessary when one atomic script decides.

### Prompt 3: Turn my stories into Given/When/Then
```text
Here are my user stories: <paste>. For each, write 2-4 acceptance criteria in Given/When/Then form,
including at least one refusal or failure case (no admin token, a rotated key, the request after the
last token, a cost above the limit, Redis down, invalid input). Use only status codes and headers
from this contract: <paste the relevant paths>. Give concrete numbers (limit, window, cost).
```
**Verify the output by:** recomputing every count yourself (how many of 6 requests pass? what is
`Retry-After`?) and cross-checking every status code with the contract and your permission matrix. A
criterion that answers a denied `/limits/check` with `429` when the contract says `200` with
`allowed: false` is a design decision you must notice, not accept by default.

## 5. Deliverables
- [ ] `spec/requirements.md` with problem statement, personas + permission matrix, FRs (including the
  exact algorithm rules), measurable NFRs, threats, out of scope and estimates.
- [ ] 8–10 GitHub Issues created from the *User story* template, each with a refusal or failure criterion.
- [ ] A merged PR and an `AI_LOG.md` entry (what the assistant invented that you removed).

## 6. Self-check quiz

### Q1. Which requirement is testable?
- [ ] "The limiter should be fast."
- [ ] "Limits must be fair to all tenants."
- [x] "With a token bucket of 5 per 60 s, a 6th check in the same second is denied with retryAfterMs = 12000."
- [ ] "The service should handle bursts."
> **Answer:** It has an exact rule a unit test can check with numbers: one token refills every 60 000 / 5 = 12 000 ms. The others need a measurable threshold first.

### Q2. Why does `POST /limits/check` contain no tenant id?
- [ ] To keep the request small
- [x] The API key identifies the tenant, so a caller can never check (or spend) another tenant's limit by changing an id
- [ ] Tenant ids are secret
- [ ] Redis cannot look up ids
> **Answer:** If the id were in the body, every handler would need to check "does this key belong to this id?", and the one that forgets is a broken-object-level-authorisation bug (OWASP API1). Without an id there is nothing to forget.

### Q3. Three app instances each keep a limit of 100 per minute in their own memory. What does a tenant really get?
- [ ] 100 per minute
- [ ] 33 per minute per instance
- [x] Up to 300 per minute, depending on how the load balancer spreads the requests
- [ ] It depends on the algorithm only
> **Answer:** Each instance counts only what it sees. That is why the state must live in one shared place (Redis) and the decision must be atomic there (NFR-1).

### Q4. Why is a denied `/limits/check` a `200` with `allowed: false`, while the demo endpoint answers `429`?
- [ ] It is a mistake in the contract
- [x] The check endpoint answers a question ("may I?") successfully either way; the demo endpoint is the protected resource itself, so it refuses the request
- [ ] `429` is not allowed for POST requests
- [ ] Clients cannot read `429` bodies
> **Answer:** The caller of `/limits/check` is a backend that wants the full decision (remaining, retry time) and decides itself; an end client of a protected endpoint needs the standard `429` + `Retry-After`.

### Q5. Your estimate says ~6 000 Redis commands per second at peak. What does that mean for the design?
- [ ] You need Redis Cluster from day one
- [x] One Redis instance is plenty; spend the effort on atomicity and on behaviour when Redis is unavailable
- [ ] You must cache decisions in the app
- [ ] You need a message queue in front of Redis
> **Answer:** A single Redis handles roughly 100 000 simple commands per second. What breaks limiters is concurrency between instances and a slow or missing Redis, not volume. The key layout can still be made cluster-ready cheaply (Phase 2).

### Q6. An AI draft adds "FR-17: the advisor applies its suggested limit automatically every night". What do you do?
- [ ] Keep it, automation is good
- [x] Remove it: the brief says AI must never change anything by itself, and an attacker who controls usage patterns could steer an automatic change
- [ ] Keep it, but only for RAISE_LIMIT
- [ ] Ask the AI if it is safe
> **Answer:** Requirements come from the brief, not from the assistant's idea of a smart product. A human applies advice with `PUT /policy`; log the rejection in `AI_LOG.md`.

## 7. What you learned
- Writing testable FRs and measurable NFRs, consistent with a given API contract.
- A permission matrix with two credentials, and why the key (not an id) identifies the tenant.
- Defining algorithms precisely enough that three implementations agree to the request.
- Threat modelling that includes failure cases: races between instances, clock drift, a slow Redis.
- User stories with refusal criteria, tracked as GitHub Issues, and estimates that keep the design simple.

## 8. Catch-up git commands
```bash
git fetch upstream --tags
git switch -c my-phase-2 phase-1-end
git show phase-1-end:spec/requirements.md | less     # the reference requirements
git diff phase-0-end phase-1-end --stat
```
