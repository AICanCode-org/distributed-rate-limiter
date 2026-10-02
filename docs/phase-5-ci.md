---
phase: 5
slug: phase-5-ci
title: CI with GitHub Actions
duration: 2.5 h
start_tag: phase-4-end
end_tag: phase-5-end
---
# Phase 5 · Continuous integration with GitHub Actions

## 1. Why it matters
"It passed on my machine" is not evidence. CI runs the same checks on a clean machine for every push
and pull request, so a broken change never reaches `main`. It is also the cheapest code reviewer you
will ever have. In this phase you grow the starter workflow into a real pipeline: path filters, a
reusable workflow, service containers for integration tests, caching, a coverage artefact and one
required status check.

Every keyword used here is explained slowly in **[docs/github-actions-explained.md](github-actions-explained.md)**.
Read it side by side with the YAML.

## 2. Objectives
- Explain triggers, jobs, steps, runners, `needs`, `if`, matrices, caching and artefacts.
- Write a **reusable workflow** (`workflow_call`) that runs one track's full check, and call it three times.
- Run integration tests in CI with a Redis **service container**.
- Skip unaffected tracks with a **path filter**, and still have a single required check (`ci-ok`).
- Validate the OpenAPI contract in CI and enable **Dependabot**.
- Protect `main` so a PR cannot be merged while CI is red.

## 3. Step-by-step instructions

### Step 5.1 · Read the starter workflow
Open `.github/workflows/ci.yml`. It is a **matrix** over `[java, python, typescript]`; each matrix job
sets up one toolchain (`if: matrix.track == 'java'`), then runs `make install`, `make lint` and `make test`.
The same Makefile targets exist in every track, so CI does not need three different scripts.

### Step 5.2 · Create the reusable workflow
Create `.github/workflows/reusable-track-ci.yml` with `on: workflow_call` and an input `track`. Give its job:
- `services:` `redis:7.4-alpine` with a health check (`redis-cli ping`) and a published port,
- `env:` `TEST_REDIS_URL: redis://localhost:6379/15` (the tests flush database 15 before each test),
- the setup steps from the starter, then `make install lint test`,
- `make coverage` (unit + integration + the 85 % gate),
- `actions/upload-artifact@v4` for the coverage report (`if: always()`).

### Step 5.3 · Rewrite `ci.yml` as an orchestrator
1. A `changes` job with `dorny/paths-filter@v3` that outputs `docs`, `shared`, `java`, `python`, `typescript`.
2. A `contract` job (only when shared files or docs change): `pipx run openapi-spec-validator spec/openapi.yaml`,
   `shellcheck` on `scripts/*.sh` and `python3 scripts/build-cms.py --check` (the course export must match `docs/`).
3. Three jobs `java`, `python`, `typescript`, each `uses: ./.github/workflows/reusable-track-ci.yml`
   with `if:` on its path output.
4. A final `ci-ok` job with `if: always()` and `needs:` on all of them that fails when any result is
   `failure` or `cancelled`. Skipped is fine.

The reference is at `git show phase-5-end:.github/workflows/ci.yml`.

### Step 5.4 · Lint your workflows before pushing
```bash
# https://github.com/rhysd/actionlint  (Go binary; also available via Homebrew / Docker)
go install github.com/rhysd/actionlint/cmd/actionlint@latest
actionlint                       # run from the repo root
```
`actionlint` catches typos in expressions, unknown inputs, bad `needs` and shell bugs (via shellcheck)
before GitHub ever sees them.

### Step 5.5 · Dependabot
Add `.github/dependabot.yml` with one entry per ecosystem: `maven` in `/tracks/java`, `pip` in
`/tracks/python`, `npm` in `/tracks/typescript`, and `github-actions` in `/`. Group minor + patch updates
so you get one PR per ecosystem per week.

### Step 5.6 · Protect `main`
In your fork: **Settings → Branches → Add branch ruleset** (or classic rule) for `main`:
- Require a pull request before merging.
- Require status checks to pass → add **`ci-ok`** (it appears after the first run).
- Block force pushes.

Because skipped jobs count as success, a docs-only PR still gets a green `ci-ok` quickly.

### Step 5.7 · Prove it works
Open a PR that breaks one test in **one** track: only that track's job runs (path filter) and `ci-ok`
goes red, so the merge button is blocked. Fix it and watch it go green. Then open a PR that only touches
`spec/openapi.yaml`: all jobs run, because a contract change affects every track.

## 4. AI-assist prompts

### Prompt 1: Explain a workflow line by line
```text
Explain this GitHub Actions workflow line by line for a beginner. For every expression ${{ ... }} say
when it is evaluated and what value it will have on a pull_request event from a fork:
<paste .github/workflows/ci.yml>
```
**Verify the output by:** comparing each claim with the official docs (docs.github.com/actions, the
"Workflow syntax" and "Contexts" pages) and with `docs/github-actions-explained.md`. Assistants often
mix up `github.ref` on pull requests and the secrets available to forks.

### Prompt 2: Debug a red job
```text
My GitHub Actions job fails. Here is the failing step's log (last 80 lines) and the workflow file.
Tell me the most likely cause, how to reproduce it locally with the same commands, and the smallest fix.
<paste log> <paste YAML>
```
**Verify the output by:** reproducing the failure locally with the same `make` target first. If you
cannot reproduce it, re-run the job with debug logging (**Re-run jobs → Enable debug logging**) before
changing anything.

### Prompt 3: Review for security
```text
Review this workflow for security problems: overly broad permissions, secrets exposed to pull requests
from forks, unpinned third-party actions, script injection through ${{ github.event.* }} in run steps.
<paste YAML>
```
**Verify the output by:** checking the `permissions:` block (we use `contents: read` by default), and
searching the YAML for `${{ github.event` inside `run:`. Untrusted strings must go through `env:` first.

## 5. Deliverables
- [ ] `reusable-track-ci.yml` + rewritten `ci.yml`; `actionlint` passes locally.
- [ ] A green run where all three tracks ran integration tests with service containers.
- [ ] A PR touching one track that skips the others (screenshot of the run graph).
- [ ] Branch protection on `main` requiring `ci-ok`.
- [ ] `.github/dependabot.yml`.

## 6. Self-check quiz

### Q1. What does `on: workflow_call` mean?
- [ ] The workflow runs when someone calls the repository's API
- [x] The workflow can be used as a reusable workflow by other workflows (`jobs.<id>.uses`)
- [ ] The workflow runs on every push
- [ ] The workflow can only be run manually
> **Answer:** A reusable workflow is like a function: it declares `inputs`, and the caller passes them with `with:`.

### Q2. Why is `ci-ok` the only required check instead of every job?
- [ ] GitHub allows only one required check
- [x] With path filters some jobs are skipped; one aggregator job gives a stable name that is green when nothing failed
- [ ] It makes CI faster
- [ ] Reusable workflows cannot be required checks
> **Answer:** Required checks are matched by name. An aggregator with `if: always()` survives jobs being skipped or renamed.

### Q3. How do integration tests reach Redis in CI?
- [ ] They use the in-memory store
- [ ] They connect to a free cloud Redis
- [x] A `services:` container runs next to the job and its port is published on `localhost`
- [ ] Docker Compose is started inside the test
> **Answer:** Service containers live for one job, are health-checked before steps start, and are thrown away afterwards.

### Q4. What does `cache: npm` (or `maven`/`pip`) in a setup action do?
- [ ] Caches the build output
- [x] Restores the package download cache keyed by a hash of the lock file, so installs are faster
- [ ] Commits `node_modules` to the repo
- [ ] Skips the install step
> **Answer:** The key changes when `package-lock.json` / `pom.xml` / `requirements-dev.txt` changes, so you never get stale dependencies.

### Q5. Which default `permissions` block is safest for a CI workflow that only reads code?
- [ ] `permissions: write-all`
- [x] `permissions: contents: read`
- [ ] No block at all
- [ ] `permissions: packages: write`
> **Answer:** Least privilege. Jobs that need more (e.g. pushing images) ask for it explicitly, as `deploy.yml` does in Phase 6.

## 7. What you learned
- Workflow anatomy: triggers, jobs, steps, runners, `needs`, `if`, `defaults`, `concurrency`.
- Reusable workflows with inputs, and an orchestrator workflow that calls them.
- Service containers for real integration tests in CI.
- Path filters plus an aggregator job for fast and safe required checks.
- Caching, artefacts, actionlint, Dependabot and branch protection.

## 8. Catch-up git commands
```bash
git fetch upstream --tags
git switch -c my-phase-6 phase-5-end
git diff phase-4-end phase-5-end -- .github/
git checkout phase-5-end -- .github/          # take the reference workflows
```
