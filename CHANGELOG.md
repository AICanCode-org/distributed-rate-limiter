# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.0.0] - 2026-10-02
### Added
- Requirements (FR-1..16, NFR-1..8, threats T1..T10, user stories with Given/When/Then), architecture
  diagrams, the algorithm and Redis key tables, ADRs 0001-0005 (atomic Lua scripts with pure-function
  twins, Redis `TIME` as the only clock, `{tenantId}` hash tags, fail closed, hashed API keys +
  constant-time admin token), `spec/lua/token_bucket.lua` and `spec/lua/sliding_window.lua`, OpenAPI 1.0.0.
- Tenants in all three tracks: create (key shown once, `409` on duplicates, races decided by `SADD`),
  list, read, delete, policy changes, key rotation (old key refused at once), reset (`SCAN`, never `KEYS`).
- API keys `rg_` + 32 hex characters from a CSPRNG, stored only as SHA-256; admin bearer token compared
  in constant time.
- Token bucket and sliding window counter as pure functions and as atomic Redis Lua scripts with
  identical maths; `POST /api/v1/limits/check` with resources and costs (`422` when cost > limit),
  `GET /api/v1/demo/hello` (`429` + `Retry-After`), `X-RateLimit-Limit/Remaining/Reset` headers.
- Usage per minute (180-minute ring) and statistics; AI policy advisor (`KEEP`, `RAISE_LIMIT`,
  `LOWER_LIMIT`, `INVESTIGATE`): offline rules, optional Ollama/Gemini/Groq with validated output and
  fallback; never applied automatically.
- Fail closed: Redis errors and commands slower than `REDIS_TIMEOUT_MS` → `503 limiter-unavailable`
  with `Retry-After: 1`; `/health` reports Redis.
- Unit + integration tests per track (Lua/pure-function parity, parallel checks from two instances,
  TTLs, script reload after `SCRIPT FLUSH`), 85 % line-coverage gate; `scripts/load-test.py`.
- CI: path filters, reusable per-track workflow with a Redis service container, contract checks
  (OpenAPI, shellcheck, CMS export), Docker image smoke test, `ci-ok`.
- Dockerfiles (multi-stage, non-root, health check, Lua scripts in `/app/lua`), Compose profiles per
  track and a `pair` profile (two instances, one Redis), `scripts/smoke-test.sh` (53 checks).
- `deploy.yml`: images to GHCR on version tags; optional Render deploy.
- `docs/runbook.md`.

## [0.1.0] - starter
### Added
- Starter: three track skeletons (Java, Python, TypeScript) with the complete (pending) test suite,
  shared OpenAPI contract, `/health`, Docker Compose with Redis, minimal CI.
