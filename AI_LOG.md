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
