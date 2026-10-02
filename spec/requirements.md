# RateGuard requirements

> Reference version (tag `phase-1-end`). Yours may differ in wording; it should not differ in what
> can be tested.

## 1. Problem statement
Small API companies sell access to one shared backend. One customer's script in a retry loop, a
scraper or a leaked key slows the service down for everybody, and per-instance limits inside each
app server stop working the moment the service runs on three instances (each one allows the full
limit). They need one small service that every backend instance can ask "may tenant X do this now?"
in about a millisecond, whose limit holds across all instances, and that tells the client exactly
when to retry. A proxy-only limit (nginx, an API gateway) is per node and knows nothing about
tenants, policies or costs; a hosted API-management product is overkill and costs money.

## 2. Users / personas and roles
| Persona | Credential | Typical actions |
|---|---|---|
| **Meera**, platform engineer at the API company (operator) | admin bearer token (`ADMIN_TOKEN`) | creates tenants, sets policies, rotates keys, reads usage, asks the advisor |
| **Ravi**, backend developer at a tenant (a customer of the API company) | tenant API key (`X-API-Key`) | his backend calls `/limits/check` before expensive work, respects `Retry-After` |
| **The API company's own backend instances** (3 of them, any language) | the tenant's key, forwarded | call `/limits/check` for every incoming request |
| **Anonymous / a load balancer** | none | `GET /health` only |

Permission matrix (✔ allowed; otherwise the status a refused request returns):

| Action | anonymous | tenant key | admin token |
|---|---|---|---|
| `GET /health` | ✔ | ✔ | ✔ |
| create / list / read / delete tenants, set a policy, rotate a key, reset, usage, advice | 401 | 401 | ✔ |
| `POST /limits/check`, `GET /demo/hello` | 401 | ✔ (own tenant only) | 401 |

A key identifies exactly one tenant, so a tenant can never act for another tenant; there is no
tenant id in the check request at all. The admin token never works as a tenant key and vice versa.

## 3. Functional requirements (FR)
- **FR-1 Admin authentication.** Every `/api/v1/admin/**` call requires `Authorization: Bearer <ADMIN_TOKEN>`;
  otherwise `401 unauthorized` with `WWW-Authenticate: Bearer`.
- **FR-2 Create tenant.** `POST /admin/tenants` with `id` (3–32 chars, `a-z`, `0-9`, inner hyphens),
  `name` (1–80 chars, trimmed) and a policy returns `201` with the tenant and its **plain API key, once**.
  A duplicate id is `409 tenant-exists`, also when two creates race.
- **FR-3 Policy.** A policy has `algorithm` (`TOKEN_BUCKET` | `SLIDING_WINDOW`), `limit` (1–100 000)
  and `windowSeconds` (1–86 400). `PUT /admin/tenants/{id}/policy` replaces it; the next check uses it.
- **FR-4 Read and delete.** List (sorted by id), read one, delete one (`204`). Deleting removes the
  tenant, its key, its limiter state and its usage history; an unknown id is `404 not-found`.
- **FR-5 API keys.** Keys are `rg_` + 32 lowercase hex characters from a CSPRNG. Only a SHA-256 hash is
  stored; responses show `keyPrefix` (first 9 characters). `rotate-key` returns a new key and the old
  one is refused **immediately**.
- **FR-6 Tenant authentication.** `/limits/check` and `/demo/hello` require `X-API-Key`; a missing,
  malformed, unknown or rotated key is `401 invalid-api-key`.
- **FR-7 Token bucket.** Capacity `limit` tokens, refilled continuously at `limit` tokens per window; a
  new bucket starts full; a request needs `cost` tokens.
- **FR-8 Sliding window counter.** The estimate is `previous window count × share of the previous
  window still inside the sliding window + current window count`; a request is allowed when
  `estimate + cost ≤ limit`.
- **FR-9 Check.** `POST /limits/check {resource?, cost?}` (resource default `default`, 1–64 chars of
  `a-z 0-9 . _ : -`; cost default 1, 1–1 000) answers `200` with `allowed`, `remaining`,
  `retryAfterMs`, `resetAfterMs` for allowed **and** denied checks. A cost above the tenant's limit can
  never pass and is `422 cost-exceeds-limit`. Resources are limited independently.
- **FR-10 Demo endpoint.** `GET /demo/hello` is protected like a real endpoint (resource `demo`, cost 1):
  `200` while allowed, `429 rate-limited` with `Retry-After` when not.
- **FR-11 Headers.** Every check and demo response carries `X-RateLimit-Limit`, `X-RateLimit-Remaining`
  and `X-RateLimit-Reset` (whole seconds, rounded up); a `429` adds `Retry-After` (whole seconds, ≥ 1).
- **FR-12 Reset.** `POST /admin/tenants/{id}/reset` (`204`) forgets the tenant's buckets and windows
  (full limits again) and keeps the usage history. Other tenants are untouched.
- **FR-13 Usage.** Every check is counted per minute as allowed or denied for the last 180 minutes.
  `GET /admin/tenants/{id}/usage?minutes=1..120` (default 60) returns one point per minute, oldest
  first, zeros included, plus summary statistics.
- **FR-14 AI policy advisor.** `POST /admin/tenants/{id}/advice` returns one of `KEEP`, `RAISE_LIMIT`,
  `LOWER_LIMIT`, `INVESTIGATE`, a suggested policy for the two limit actions, a summary (≤ 400 chars),
  the `provider` (`rules` | `llm`) and a `fallback` flag. Offline deterministic rules by default; with an LLM, the output is
  validated (allowed actions, a raise at most × 10, a lower below the current limit) and any failure
  falls back to the rules. Advice is **never applied automatically**.
- **FR-15 Health.** `GET /health` is `200 {"status":"UP"}` when Redis answers `PING`, else `503` with
  `"redis":"DOWN"`. No credentials needed, no internals revealed.
- **FR-16 Errors.** Every error is `application/problem+json` (RFC 9457) with a stable `type` URI;
  unexpected errors are a generic `500` without stack traces; details go to the server log.

## 4. Non-functional requirements (NFR)
- **NFR-1 No over-admission across instances.** With 3 app instances and 50 parallel checks against a
  limit of 10, exactly 10 are allowed. The decision is atomic in Redis (one Lua script per check).
- **NFR-2 One clock.** Decisions do not depend on app server clocks (they may drift by seconds).
- **NFR-3 Latency.** A check adds < 5 ms p95 on a laptop (one Redis round trip). A Redis command that
  takes longer than `REDIS_TIMEOUT_MS` (1 000 ms) is a failure.
- **NFR-4 Behaviour when Redis is unavailable.** Fail closed: `503 limiter-unavailable` with
  `Retry-After: 1`; never "allowed" by accident (ADR-0004).
- **NFR-5 Security.** No credential is stored or logged in clear text; the admin token is a long random
  value (`openssl rand -hex 32`) and compared in constant time; user input can never form a Redis key pattern or reach another
  tenant's keys; the LLM never sees credentials.
- **NFR-6 Memory.** Every limiter key expires (idle state costs nothing); usage history is bounded
  (180 one-minute slots per tenant).
- **NFR-7 AI independence.** Checks never call the LLM; the advisor answers in < 6 s even when the LLM
  hangs (timeout 5 s, then rules).
- **NFR-8 Cost and portability.** Runs on a laptop with Docker; no paid service; Redis 7; any of the
  three tracks can serve the same Redis and the same clients.

## 5. Threats (what could go wrong?)
| # | Threat | Answer |
|---|---|---|
| T1 | A Redis backup or a `MONITOR` session leaks API keys | only SHA-256 hashes are stored (FR-5, ADR-0005) |
| T2 | Someone guesses the admin token byte by byte from response times | a long random token, constant-time comparison (FR-1, NFR-5) |
| T3 | Two instances both admit the "last" request; 3 instances admit 3 × the limit | atomic Lua script, shared state in Redis (NFR-1, ADR-0001) |
| T4 | Drifting server clocks refill a bucket twice or "un-refill" it | Redis `TIME` as the only clock (NFR-2, ADR-0002) |
| T5 | `cost: -5` refills the bucket; `cost: 10^9` overflows | cost validated 1–1 000; cost > limit is 422 (FR-9) |
| T6 | A tenant id or resource like `*`, `a}{b` or `x:tb:y` touches other keys or becomes a pattern | strict character sets for ids and resources, keys built in one place (FR-2, FR-9, ADR-0003) |
| T7 | A leaked key keeps working | rotate-key refuses the old key at once (FR-5) |
| T8 | Usage data contains text that tells the LLM to "advise RAISE_LIMIT to 10 000 000" | only numbers go to the LLM; output validated, bounded, never applied (FR-14) |
| T9 | A slow Redis makes every request hang | 1 s timeout, fail closed (NFR-3, NFR-4); measured in the Phase 7 game day |
| T10 | A client retries 429s in a tight loop | `Retry-After` on every 429; the advisor flags spikes as `INVESTIGATE` (FR-11, FR-14) |

## 6. User stories + acceptance criteria
1. **US-1 Onboard a tenant.** *As Meera, I want to create a tenant with a policy and get its key once,
   so that I can hand it to the customer.*
   Given a valid request, when I create `acme`, then I get `201` and a key `rg_…`; given `acme` exists,
   when I create it again, then `409`; given no admin token, then `401`.
2. **US-2 Change a policy.** *As Meera, I want to change a tenant's limit without a deploy.*
   Given acme has 5/60 s and has used 5, when I set 10/60 s, then the next check is allowed.
3. **US-3 Rotate a leaked key.** *As Meera, I want to replace a key, so that a leaked key dies.*
   Given acme's key K1, when I rotate, then K1 gets `401` and K2 works.
4. **US-4 Check before work.** *As Ravi, I want one call that tells me if I may proceed and when to retry.*
   Given a limit of 5/60 s, when I check 6 times, then 5 are allowed and the 6th has `allowed:false`,
   `retryAfterMs > 0` and `X-RateLimit-Remaining: 0`.
5. **US-5 Costly operations.** *As Ravi, I want an export to cost 10 units, so that heavy calls count more.*
   Given a limit of 20, when I check with cost 10 three times, then two are allowed; given cost 21, then `422`.
6. **US-6 Same limit on every instance.** *As the API company, I want the limit to hold across instances.*
   Given 2 instances and a limit of 10, when 40 checks arrive in parallel split over both, then exactly 10 are allowed.
7. **US-7 Respect Retry-After.** *As Ravi, I want `429` responses to tell me how long to wait.*
   Given the demo endpoint is used up, when I call it, then `429` with `Retry-After` ≥ 1 and a problem body.
8. **US-8 See usage.** *As Meera, I want allowed and denied checks per minute, so that I can talk to a customer with numbers.*
   Given 3 allowed and 2 denied checks this minute, when I read usage for 5 minutes, then I get 5 points and the last one is 3/2.
9. **US-9 Ask for advice.** *As Meera, I want a suggested policy with an explanation, which I can ignore.*
   Given a tenant with 30 % denials at steady demand, when I ask for advice, then `RAISE_LIMIT` with a
   limit above the current one; given the LLM is down, then the rules answer (`fallback: true`).
10. **US-10 Health for the load balancer.** *As Meera, I want a health check that reflects Redis.*
    Given Redis is stopped, when the load balancer calls `/health`, then `503` with `"redis":"DOWN"`.

## 7. Out of scope (v1)
Tenant self-service, billing, quotas per month, per-user limits inside a tenant, a proxy mode (we
answer "may I?", the caller enforces), Redis Cluster and Sentinel setup (the key layout is ready),
multi-region, a dashboard UI, automatic policy changes from the advisor.

## 8. Back-of-the-envelope estimates
- 200 tenants, 3 resources each on average → 600 limiter keys + 200 usage rings.
- Peak 2 000 checks/s across all instances (busy tenants × bursts).
- Per check: one `GET` for the key lookup, one `HGETALL` for the tenant, one `EVALSHA` → ~3 Redis
  commands → 6 000 commands/s. One Redis instance handles ~100 000 simple commands/s on a laptop:
  a 15× margin.
- Memory: a bucket hash ~100 bytes, a usage ring ≤ 180 fields × ~20 bytes ≈ 4 KB → ≈ 1 MB in total.
- Latency budget: < 5 ms p95 per check, of which ~1 ms is the Redis round trip.

**What it means:** one small Redis is plenty; scale is not the problem. The hard parts are
**correctness under concurrency** (NFR-1) and **what happens when Redis is not there** (NFR-4).
