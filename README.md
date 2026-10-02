# RateGuard: build a distributed rate limiter that holds across instances, with AI as your pair programmer

[![CI](https://github.com/AICanCode-org/distributed-rate-limiter/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/AICanCode-org/distributed-rate-limiter/actions/workflows/ci.yml)
[![CI (solution)](https://github.com/AICanCode-org/distributed-rate-limiter/actions/workflows/ci.yml/badge.svg?branch=solution)](https://github.com/AICanCode-org/distributed-rate-limiter/actions/workflows/ci.yml?query=branch%3Asolution)

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
