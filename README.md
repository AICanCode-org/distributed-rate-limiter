# RateGuard: build a distributed rate limiter that holds across instances, with AI as your pair programmer

[![CI](https://github.com/AICanCode-org/distributed-rate-limiter/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/AICanCode-org/distributed-rate-limiter/actions/workflows/ci.yml)
[![CI (solution)](https://github.com/AICanCode-org/distributed-rate-limiter/actions/workflows/ci.yml/badge.svg?branch=solution)](https://github.com/AICanCode-org/distributed-rate-limiter/actions/workflows/ci.yml?query=branch%3Asolution)

## Case study (reference solution)

> **RateGuard is a rate-limiting service that many API instances can share: one Redis, one atomic Lua
> script per decision, exact limits across instances, a per-tenant answer to "what if Redis is down?",
> and an AI advisor that suggests better limits but never changes them. It is built three times, in
> Java, Python and TypeScript, against one contract.**

```console
$ curl -s -X POST localhost:8080/api/v1/admin/tenants -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
    -d '{"id":"acme","name":"Acme Inc.","policy":{"algorithm":"TOKEN_BUCKET","limit":3,"windowSeconds":60,"failMode":"LOCAL"}}'
{"id":"acme", ... ,"keyPrefix":"rg_5c7820", ... ,"apiKey":"rg_5c78202606cbfcb9a25ee572aae1054f"}   # shown once, stored as SHA-256
$ curl -si -X POST localhost:8080/api/v1/limits/check -H "X-API-Key: $KEY" -H 'Content-Type: application/json' -d '{"resource":"search","cost":1}'
HTTP/1.1 200 OK
x-ratelimit-limit: 3
x-ratelimit-remaining: 2
{"allowed":true,"tenantId":"acme","resource":"search","cost":1,"algorithm":"TOKEN_BUCKET","limit":3,"remaining":2, ... ,"degraded":false}
$ # ... two more, then:
{"allowed":false, ... ,"remaining":0,"resetAfterMs":59967,"retryAfterMs":19968,"degraded":false}
$ docker compose pause redis          # Redis freezes: the breaker opens after 3 slow calls, LOCAL takes over
$ curl -si -X POST localhost:8080/api/v1/limits/check -H "X-API-Key: $KEY" -d '{"resource":"search","cost":1}' ...
HTTP/1.1 200 OK
x-ratelimit-degraded: true
{"allowed":true, ... ,"remaining":2, ... ,"degraded":true}                                       # 2.6 ms, decided in memory
$ curl -s localhost:8080/health
{"status":"DEGRADED","checks":{"redis":"DOWN"}}
$ curl -s -X POST localhost:8080/api/v1/admin/tenants/acme/advice -H "Authorization: Bearer $ADMIN_TOKEN"
{"tenantId":"acme","provider":"rules","fallback":false, ... ,"action":"RAISE_LIMIT","suggestedPolicy":{"algorithm":"TOKEN_BUCKET","limit":5, ...},
 "summary":"25% of requests were denied (1 of 4). Steady demand is about 4 per minute (90th percentile) against a capacity of 3 per minute. Raise the limit to 5 per 60 s (20% headroom)."}
```

**Problem → solution.** Most rate-limiter tutorials keep a counter in memory, which stops working the
moment a second instance starts, or do `GET` then `SET` in Redis, which lets two instances spend the
same last token. RateGuard handles the parts that make a shared limiter hard on purpose and proves them
with tests: each decision is one atomic Lua script, Redis `TIME` is the only clock (app servers may
drift), every key a script touches shares a `{tenantId}` hash tag (Redis Cluster ready), every limiter
key expires, API keys are stored only as SHA-256 and the admin token is compared in constant time,
`SCAN` instead of `KEYS`, and a frozen Redis is a tested failure with a per-tenant fail mode. It runs
on a laptop with one command and needs no API key.

**Architecture.** Stateless API containers (any number, any track) and Redis as the only stateful
component: see the diagrams and the key layout in [`spec/architecture.md`](spec/architecture.md). Three
decisions worth discussing:
1. **Atomic Lua scripts with pure-function twins**: the token bucket and the sliding window counter are
   pure functions (unit tests with a fake clock) and Lua scripts with identical maths, and the integration
   tests prove they agree ([ADR-0001](spec/adr/0001-atomic-lua-scripts-with-pure-function-twins.md),
   [ADR-0002](spec/adr/0002-redis-time-as-the-only-clock.md)).
2. **Fail closed by default, per tenant from v1.1**: a limiter that cannot decide says `503`, never
   "allowed" by accident; tenants may choose `OPEN` or `LOCAL` instead, behind a circuit breaker
   ([ADR-0004](spec/adr/0004-fail-closed-when-redis-is-unavailable.md),
   [ADR-0006](spec/adr/0006-per-tenant-fail-modes-and-redis-circuit-breaker.md)).
3. **Hashed API keys, constant-time admin token**: a leaked Redis backup contains no usable key; rotation
   invalidates the old key at once ([ADR-0005](spec/adr/0005-hashed-api-keys-and-constant-time-admin-token.md)).

**Security and quality evidence (v1.1.0, measured on the maintainers' Linux build box with Docker).**

| | Java 21 / Spring Boot | Python / FastAPI | TypeScript / Fastify |
|---|---|---|---|
| Tests (unit + integration) | 153 unit + 18 integration | 192 unit + 18 integration | 156 unit + 18 integration |
| Line coverage (gate 85 %) | 97 % (JaCoCo) | 99 % (coverage.py) | 99.5 % (V8) |
| Docker smoke test (`scripts/smoke-test.sh`) | 64/64 (and 64/64 with the LLM unreachable) | 64/64 (and 64/64) | 64/64 (and 64/64) |

Each threat in [`spec/requirements.md`](spec/requirements.md) (T1–T10: keys leaked from a backup,
timing attacks on the admin token, over-admission across instances, drifting clocks, negative or huge
costs, key-pattern injection, a leaked key that keeps working, prompt injection into the advisor, a slow
Redis, retry loops) maps to unit and integration
tests, and the black-box smoke test re-checks the most important ones against every Docker image.
`scripts/load-test.py` sends 400 checks through two instances against a limit of 100 and gets exactly
100 allowed. Every phase tag (`phase-0-end` … `phase-8-end`) builds and passes the tests of all three
tracks (`scripts/verify-tags.sh`). CI runs lint, unit and integration tests against a Redis service
container, a coverage gate, contract checks and a Docker smoke test per track. Release tags publish
images to GHCR.

**Operations.** `/health` checks Redis. `/metrics` exposes eight Prometheus counters (decisions allowed,
denied and degraded, store errors, store short-circuits, tenants created, AI fallbacks, LLM
short-circuits), and every log line is JSON with the request's `X-Request-ID`. A **game day** froze
Redis with `docker pause`: v1.0.0 failed closed as designed, but every check waited the full 1 s timeout
and `/health` answered `503`, so a load balancer would have dropped every instance at once. v1.1.0 fixed
it with a circuit breaker and per-tenant fail modes (a CLOSED tenant: 10 checks avg ≈ 0.3 s instead of
1 s; OPEN and LOCAL tenants answer in 1–4 ms, marked degraded; `/health` `200 DEGRADED` in 2–4 ms):
[post-mortem](docs/postmortem-2026-10-02-redis-freeze.md), [runbook](docs/runbook.md).

**How AI was used.** Assistants drafted the starter stubs, Dockerfiles, workflow skeletons, test
scaffolding, the game-day script and first versions of docs. Their output needed the strictest review
exactly where it mattered: the Lua maths, read-then-write patterns, key handling, field names that
drifted from the contract, and version-specific framework details. Every suggestion was verified by
tests, `actionlint`, the smoke test or official documentation, and logged in [`AI_LOG.md`](AI_LOG.md).
See also [`docs/retrospective.md`](docs/retrospective.md).

**What I'd do next.** A Redis Cluster deployment (the hash tags are ready); a game day in CI with a
latency assertion; rate limiting and audit logging for the admin API; alerts on degraded decisions;
client SDKs that cache `Retry-After`.

---

RateGuard is a guided project from **[AiCanCode](https://www.aicancode.org)**. Over 9 phases (about 36
hours) you build a multi-tenant rate-limiting service like a professional team would: requirements
and threats, design, a test-first implementation, CI/CD, containers, a game day, observability and a
retrospective. AI assistants help you at every step, and you **verify** everything they produce.

**What you build:** a REST service that API companies put in front of their backends. Each tenant
(a customer of the API) gets an API key and a policy such as "100 requests per 60 s, token bucket".
Every request is checked with one call (`POST /api/v1/limits/check`) and answered with the standard
rate-limit headers, or `429 Too Many Requests` with `Retry-After`. The hard parts are the ones that
break real limiters in production, and they are done properly:

- **two algorithms**, token bucket and sliding window counter, written first as **pure functions**
  (exhaustively unit-tested) and then as **Redis Lua scripts** with the same maths;
- **atomic decisions across instances**: the read-decide-write happens inside one Lua script, so
  three app instances (even one per language!) behind a load balancer enforce **one** limit, proven
  by an integration test that fires parallel requests at the last tokens;
- **one clock**: the scripts use Redis `TIME`, so drifting app clocks change nothing;
- a **Redis Cluster-ready key layout** with `{tenantId}` hash tags, TTLs on every key, `SCAN` never `KEYS`;
- **API keys stored only as SHA-256** (shown once, rotatable), an admin token compared in constant
  time, validated tenant ids and resources so no input can become a Redis key pattern;
- a deliberate **failure policy**: v1.0 fails closed with `503`; after a game day that froze Redis,
  v1.1 adds a **circuit breaker** and per-tenant **fail modes** (`CLOSED`, `OPEN`, `LOCAL`);
- errors as RFC 9457 `application/problem+json`, request IDs, JSON logs and `/metrics` (v1.1).

Admins get per-minute **usage statistics** and an **AI policy advisor** that reads them and suggests
`KEEP`, `RAISE_LIMIT`, `LOWER_LIMIT` or `INVESTIGATE` (a spike that looks like abuse). The advice is
validated like user input and **never applied automatically**. It works fully offline with
deterministic rules; a local Ollama model or a free Gemini/Groq key are optional, and no rate-limit
decision ever depends on the AI.

**Pick one track:** the spec, Lua scripts, API contract, tests and smoke test are shared. Only the code differs.

| Track | Stack | Folder |
|---|---|---|
| Java | Java 21 · Spring Boot 4 (Web MVC) · Lettuce · JUnit 5 · JaCoCo · Checkstyle | `tracks/java` |
| Python | Python 3.11+ · FastAPI · redis-py · pytest · coverage · ruff | `tracks/python` |
| TypeScript | Node 20+ · Fastify 5 · ioredis · Vitest · ESLint + Prettier | `tracks/typescript` |

**Why these three tracks?** They are the three stacks most often asked for in backend job posts, and
rate limiting is a classic system-design interview topic in all of them. Spring Boot is the
enterprise standard (we use a plain Lettuce client, no Spring Data magic, so you see every Redis
call), FastAPI is the fastest way to a typed, documented Python API, and Fastify is a lean Node
framework where nothing is hidden. Because the contract, Lua scripts and tests are shared, you can
run all three tracks against **one** Redis and watch them share a limit.

Course repository: **<https://github.com/AICanCode-org/distributed-rate-limiter>** (`main` = starter, `solution` = reference).

Everything runs **on your laptop** with Docker Compose (app + Redis). Deploying to the cloud is an
**optional** extra in Phase 6 and needs no credit card.

---

## Repository layout
```text
.
├── README.md                 ← you are here
├── AI_LOG.md                 ← your log of AI prompts, outputs and how you verified them
├── docs/                     ← the course: phase-0 … phase-8 + GitHub Actions explained
├── spec/                     ← shared spec: requirements, architecture, ADRs, openapi.yaml, lua/
├── tracks/{java,python,typescript}/   ← code + tests (same Makefile targets in each)
├── scripts/                  ← doctor.sh, check-all.sh, verify-tags.sh, build-cms.py (+ smoke-test.sh from Phase 6)
├── cms/                      ← machine-readable export of the course for the AiCanCode site
├── docker-compose.yml        ← Redis (+ the app per track from Phase 6, optional Ollama)
└── .github/                  ← CI/CD workflows, issue/PR templates (+ Dependabot from Phase 5)
```

## Prerequisites (per operating system)
You need **Git, Docker with Compose v2, `make`, `curl`** plus the toolchain of **one** track.
Run `scripts/doctor.sh` at any time to see what is missing.

| | Windows 10/11 | macOS | Linux (Ubuntu/Debian) |
|---|---|---|---|
| Shell | **WSL2** with Ubuntu: `wsl --install` in an admin PowerShell, reboot | Terminal (zsh) | any |
| Docker | Docker Desktop, *Use the WSL 2 based engine* + *WSL integration* for Ubuntu enabled | Docker Desktop (or Colima) | Docker Engine + `docker-compose-plugin`; `sudo usermod -aG docker $USER`, log out/in |
| Git, make, curl | inside WSL: `sudo apt install git make curl` | `xcode-select --install` | `sudo apt install git make curl` |
| Java track | inside WSL: SDKMAN → `sdk install java 21-tem && sdk install maven` | SDKMAN or `brew install openjdk@21 maven` | SDKMAN |
| Python track | inside WSL: `sudo apt install python3 python3-venv` | `brew install python@3.12` | `sudo apt install python3 python3-venv` |
| TypeScript track | inside WSL: nvm → `nvm install 22` | nvm or `brew install node@22` | nvm |

**Windows, important:** clone and work **inside the WSL file system** (`~/code/distributed-rate-limiter`), not under
`/mnt/c/...`. It is many times faster and avoids line-ending and file-watching problems. VS Code: install
the *WSL* extension and run `code .` from the WSL terminal.

Hardware: 8 GB RAM is enough. The optional Ollama model (`llama3.2:1b`) needs about 2 GB more disk/RAM.

## Quick start (5 minutes)
```bash
git clone https://github.com/<you>/distributed-rate-limiter.git && cd distributed-rate-limiter   # your fork (docs/phase-0-setup.md)
scripts/doctor.sh                        # check your tools
cp .env.example .env                     # optional: change ports / AI provider
docker compose up -d redis               # Redis 7 on localhost:6379
cd tracks/python                         # or tracks/java, tracks/typescript
make install                             # dependencies
make lint test                           # a few passing tests, the rest are "pending" until Phase 3
make run                                 # http://localhost:8080
curl -s localhost:8080/health            # {"status":"UP","checks":{"redis":"UP"}}
```

Every track offers the same commands:

| Command | What it does |
|---|---|
| `make install` | install dependencies (Python: creates `.venv`) |
| `make lint` | linter + formatter check (Checkstyle / ruff / ESLint + Prettier + tsc) |
| `make test` | unit tests (no Redis needed: an in-memory store runs the same algorithms) |
| `make test-integration` | integration tests against Redis database 15 (`TEST_REDIS_URL`, emptied before each test) |
| `make coverage` | unit + integration with an 85 % line-coverage gate |
| `make run` | start the API on port 8080 (with a local-only admin token) |
| `make build` | build the Docker image (from Phase 6) |

From Phase 6 on, the app runs in Docker too: `docker compose --profile python up --build`
(or `--profile java` / `--profile typescript`), then `scripts/smoke-test.sh http://localhost:8080`.

## How the phases work
| Phase | Topic | You end at tag |
|---|---|---|
| 0 | [Setup and orientation](docs/phase-0-setup.md) | `phase-0-end` |
| 1 | [Requirements, threats and user stories](docs/phase-1-requirements.md) | `phase-1-end` |
| 2 | [Design: algorithms, Lua scripts, key layout, failure policy, API contract](docs/phase-2-design.md) | `phase-2-end` |
| 3 | [Implementation in five milestones](docs/phase-3-implementation.md) | `phase-3-end` |
| 4 | [Testing: Lua parity, concurrency across instances, security, coverage gate](docs/phase-4-testing.md) | `phase-4-end` |
| 5 | [CI with GitHub Actions](docs/phase-5-ci.md) | `phase-5-end` |
| 6 | [Containerise, deliver, optional deploy](docs/phase-6-deploy.md) | `phase-6-end` = **`v1.0.0`** |
| 7 | [Observability, game day and resilience](docs/phase-7-observability.md) | `phase-7-end` = **`v1.1.0`** |
| 8 | [Retrospective and portfolio](docs/phase-8-retrospective.md) | `phase-8-end` |

Each phase page has the same 8 blocks: *Why it matters · Objectives · Step-by-step instructions ·
AI-assist prompts · Deliverables · Self-check quiz · What you learned · Catch-up git commands*.

### Branches and tags
- **`main`**: the **starter**. Tracks have the structure, the full (pending) test suite and `/health`;
  the business logic and the Lua scripts are `TODO`.
- **`solution`**: the reference implementation, one or more commits per phase, with an annotated tag at
  the end of every phase: `phase-0-end` … `phase-8-end`, plus releases `v1.0.0` and `v1.1.0`.
  Every tag builds and passes the tests of all three tracks.

**Stuck or behind?** Jump to the end state of the previous phase and continue from there:
```bash
git fetch upstream --tags
git switch -c my-phase-4 phase-3-end     # start Phase 4 from the reference end of Phase 3
```
**Compare your work with the reference:** `git diff phase-3-end -- tracks/python`.
**Peek at a single file:** `git show phase-2-end:spec/lua/token_bucket.lua`.
Try first, then compare. You learn far more that way.

### Pending tests
The complete test suite already exists on `main` but is switched off in one list per track
(`Pending.java`, `tests/conftest.py`, `test/pending.ts`). In Phase 3 you remove one entry at a time,
watch the tests fail, and make them pass. Skipped tests are reported as skipped, never as passed.

## Working with AI
Use any assistant you like. Each phase contains copy-paste prompts and a **"Verify the output by"**
checklist. Record what you asked and how you checked it in [`AI_LOG.md`](AI_LOG.md). Never paste
secrets, tokens, API keys, `.env` files or personal data into a prompt. Concurrency and security code
get the strictest review: an assistant that "simplifies" a Lua script into `GET` + `SET` from the app,
uses `KEYS *` to find a tenant's keys, or compares a token with `==`, is the classic way real
limiters get broken.

## More
- [docs/README.md](docs/README.md): index of all docs
- [docs/github-actions-explained.md](docs/github-actions-explained.md): every CI/CD concept, step by step
- [spec/README.md](spec/README.md): the shared specification
- [CONTRIBUTING.md](CONTRIBUTING.md) · [CHANGELOG.md](CHANGELOG.md) · [LICENSE](LICENSE) (MIT)

Questions or problems: open an issue using the templates, or contact us via <https://www.aicancode.org/contact>.
