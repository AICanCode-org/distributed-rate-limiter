# AI_LOG: how I used AI on this project

Employers and reviewers increasingly ask *how* you used AI, not *whether*. This log is your evidence
that you stayed in control. Add one entry every time an AI assistant (ChatGPT, Gemini, Copilot,
Cursor, Claude, a local Ollama model, ...) produced something you kept, changed or rejected.

**Rules**
1. Never paste secrets, `.env` contents, the admin token, tenant API keys, LLM API keys or other people's personal data
   into a prompt.
2. Every AI output is a *draft*. Record how you **verified** it (test, docs, running it, a second source).
3. Be honest about rejections: they are the most valuable entries. Concurrency and security code
   (Lua scripts, the algorithms, key layout, hashing, tokens, tenant isolation) gets the strictest review.

## Entry template (copy for each entry)

### YYYY-MM-DD · Phase N · <short title>
* **Tool / model:** e.g. Gemini 2.0 Flash (free tier), Copilot in VS Code, Ollama llama3.2:1b
* **Prompt (summary or paste):**
  > ...
* **What it produced:** one or two lines, or a link to the commit
* **What I kept / changed / rejected, and why:**
* **How I verified it:** e.g. `make test` (60 passed), compared with the OpenAPI spec, read the official docs at ...
* **Time saved / lost (honest guess):**
* **What I learned:**

---

## My entries

<!-- Start below this line. Phase 0 asks you to write your first entry. -->

> The reference solution was written by the AiCanCode maintainers working with an AI coding agent.
> These entries record checks that really happened while building it. Your entries will be about *your* work.

### 2026-10-02 · Phase 0 · Port 6379 was already taken
* **Tool / model:** AI coding agent (chat + shell)
* **Prompt (summary):**
  > `docker compose up -d redis` fails with "Bind for 0.0.0.0:6379 failed: port is already allocated". What is going on and what is the least invasive fix?
* **What it produced:** the explanation that another Redis (here: a different project's Compose stack on
  the same machine) already published 6379, and three options: stop the other stack, change the published
  port with `REDIS_PORT` (already supported by our `docker-compose.yml`), or stop a system Redis.
* **What I kept / changed / rejected and why:** kept the `REDIS_PORT` override (plus a separate Compose
  project name) and pointed `REDIS_URL` at the new port, because stopping someone else's stack is not my
  call. Rejected the suggestion to `kill` whatever process owned the port.
* **How I verified it:** `docker compose ps` → `healthy`; `redis-cli -p <port> ping` → `PONG`; each starter
  boots and `curl localhost:<port>/health` → `{"status":"UP","checks":{"redis":"UP"}}`; `make lint test` in
  each track (only the always-on tests pass, the rest are skipped as pending).
* **Time saved / lost (honest guess):** saved ~10 min.
* **What I learned:** make ports configurable from day one; it costs one line in Compose. (Phase 4 shows
  what happens when *every* setting that points at Redis is not moved along with it.)

### 2026-10-02 · Phase 1 · A field name that existed only in the requirements
* **Tool / model:** AI coding agent
* **Prompt (summary):**
  > Draft FR-14 (the AI policy advisor) and its user story with Given/When/Then acceptance criteria.
* **What it produced:** a clear requirement, but it said the advice carries a `source` (`rules` | `llm`),
  and the acceptance criterion checked `source: rules` when the LLM is down. The contract and the tests
  call it `provider` and add a separate boolean `fallback`; "the rules answered" and "the rules answered
  *because the LLM failed*" are different facts.
* **What I kept / changed / rejected and why:** kept the structure, renamed the field to `provider`, added
  `fallback`, and made the criterion check `fallback: true`.
* **How I verified it:** `grep` for every field, header, problem type and status code in the requirements
  against `spec/openapi.yaml`; each acceptance criterion maps to a test name.
* **Time saved / lost (honest guess):** saved ~1 h of writing; the cross-check took 15 minutes.
* **What I learned:** requirements and contract drift apart within one draft. Check names, not just ideas.

### 2026-10-02 · Phase 3 · A starter that could not even start
* **Tool / model:** AI coding agent
* **Prompt (summary):**
  > Generate the starter stubs from the reference code: every method body becomes `TODO(phase-3 Mx)` with a one-line hint.
* **What it produced:** stubs in all three tracks that compiled. But the Redis store's constructor loads
  `spec/lua/*.lua`, and on `main` those scripts do not exist yet (students write them in Phase 2). Every
  starter would have crashed at boot with "file not found", so Phase 0 would have failed for everyone.
* **What I kept / changed / rejected and why:** kept the generated stubs; in the starter the constructor
  no longer loads the scripts and carries a `TODO(phase-3 M3)` to do it. Rejected the alternative of
  shipping placeholder Lua files on `main`, which would give away the calling convention's answers and
  make "the scripts exist" a lie.
* **How I verified it:** a boot script starts each starter with `LUA_DIR` pointing at an empty folder and
  checks `/health` (`200 {"status":"UP","checks":{"redis":"UP"}}` in all three tracks; creating a tenant
  answers `500 application/problem+json` until M1 is done).
* **Time saved / lost (honest guess):** generation saved hours; this review took 15 minutes.
* **What I learned:** anything a constructor reads runs before any test can be skipped. Boot the starter, don't just compile it.

### 2026-10-02 · Phase 3 · Lint fixes that would have deleted the exercise
* **Tool / model:** AI coding agent + `ruff --fix`, ESLint
* **Prompt (summary):**
  > The generated starters fail lint with unused imports and unused declarations. Fix it.
* **What it produced:** `ruff --fix` removed the Python imports that only the stubbed bodies used (correct:
  students add them back as they implement). For TypeScript it proposed deleting the fence-stripping
  helper of the LLM advisor and the type of a loaded Lua script, which looked unused once the bodies
  calling them were stubs.
* **What I kept / changed / rejected and why:** kept the Python fix; rejected the TypeScript deletions,
  because M3 (the Redis store) and M5 (the LLM advisor) need them. They are exported instead
  (`stripFences`, `LimiterScript`), which satisfies the linter without removing code students rely on.
* **How I verified it:** lint is clean in all three starters; only the `/health` tests run and the rest are
  reported as pending (Python 3 passed / 162 skipped, TypeScript 3 / 129, Java 2 run / 117 skipped); every
  `TODO(phase-3 Mx)` hint was compared with the tests of its class. The hint generator itself reported
  three methods without a hint (`Keys.tenant`, `Keys.apiKey`, `UsageStats.toJson`), which were added.
* **Time saved / lost (honest guess):** neutral.
* **What I learned:** "unused" is relative to the current state of the code. A starter is deliberately incomplete.

### 2026-10-02 · Phase 4 · The integration tests flushed somebody else's database
* **Tool / model:** AI coding agent (shell)
* **Prompt (summary):**
  > Run the Python integration tests against the dev Redis.
* **What it produced:** a green run, against the wrong Redis. Our dev Redis had been moved to another port
  in Phase 0, but `TEST_REDIS_URL` was not set, so the tests used their default
  `redis://localhost:6379/15`: the Redis of a *different* project on the same machine. The suite's
  `FLUSHDB` emptied that project's database 15 before every test.
* **What I kept / changed / rejected and why:** stopped, set `TEST_REDIS_URL` explicitly for every later
  run (in our scripts, not just in a shell), reported to the owner of the other project which database had
  been flushed, and wrote the warning into Phase 0, Phase 4 and the test files ("never point it at data you
  care about"). Rejected the idea of making the tests "safer" by checking that database 15 is empty first:
  a test suite that refuses to run after its own previous run is worse, and the real fix is pointing it
  at the right server.
* **How I verified it:** `redis-cli -p <dev port> -n 15 DBSIZE` changes during a run and the other
  server's does not; CI uses its own service container, where flushing is harmless.
* **Time saved / lost (honest guess):** lost ~20 min, and some trust.
* **What I learned:** a destructive default (`FLUSHDB` on `localhost:6379`) is only safe on a machine with
  one project. Destructive test setup must be pointed somewhere on purpose.

### 2026-10-02 · Phase 6 · Porting the smoke test from another project
* **Tool / model:** AI coding agent
* **Prompt (summary):**
  > Adapt our blog API smoke test (bash + curl only) to RateGuard: admin API, key rotation, both algorithms, `429` with `Retry-After`, headers, usage, advice and reset.
* **What it produced:** a 53-check script that reuses the first-occurrence JSON helpers (`grep -o … | head -1`;
  a greedy `sed` would return the *last* match, and a usage body has many `"allowed"` fields). Two gaps in
  review: the tenants it created were never deleted, so every run against a live deployment left three
  tenants (with valid keys) behind; and against a live URL the script needs the real `ADMIN_TOKEN`, which
  the first version simply assumed was the local Compose value.
* **What I kept / changed / rejected and why:** every tenant id carries a unique run id and the script
  deletes its tenants at the end (and checks that the deleted key gets `401`). `SMOKE_ADMIN_TOKEN` comes from
  a GitHub secret in `deploy.yml`; without it the deploy job only checks `/health` and says so in a notice,
  instead of failing a release for a missing secret. The loops that only repeat a request use `for _ in`, so
  shellcheck has no unused variable to complain about.
* **How I verified it:** the script against the v1.0 images of all three tracks (53/53), again with
  `AI_PROVIDER=llm` pointing at an unreachable address and `EXPECT_FALLBACK=true`, twice in a row against
  the same Redis (no collisions, `GET /api/v1/admin/tenants` empty afterwards), and `shellcheck --severity=error`.
* **Time saved / lost (honest guess):** saved ~1 h.
* **What I learned:** a smoke test against production is a client of production. It must clean up after
  itself, and the credentials it needs must be decided before it runs in CI.

### 2026-10-02 · Phase 6 · Where should `deploy.yml` live?
* **Tool / model:** AI coding agent
* **Prompt (summary):**
  > Should the deploy workflow already be on `main` (the starter) so students see it early?
* **What it produced:** a version that lived on `main` and skipped tracks without a Dockerfile with a notice.
* **What I kept / changed / rejected and why:** rejected for the course. Students write `deploy.yml`
  themselves in Phase 6, the sibling projects (SnapLink, DevBlog, ShopFlow) keep it on `solution` only, and a
  workflow that exists but does nothing on `main` is confusing. The file arrives with the Dockerfiles at
  `phase-6-end`. Because it triggers on `v*.*.*` tags, the tags of this repository were pushed with Actions
  disabled, so publishing the course never deployed anything.
* **How I verified it:** `actionlint` (with shellcheck) on every workflow; `git ls-tree main .github/workflows`
  lists only `ci.yml`; the Actions tab shows no Deploy run.
* **Time saved / lost (honest guess):** neutral.
* **What I learned:** consistency across sibling projects is a feature for learners, not just for maintainers.
