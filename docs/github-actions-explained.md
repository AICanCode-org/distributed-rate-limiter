# GitHub Actions, explained step by step

This page explains **every** GitHub Actions concept used by RateGuard's workflows, in the order you meet
them. Keep the YAML open next to it:

| File | Appears in | Purpose |
|---|---|---|
| `.github/workflows/ci.yml` | starter (simple), Phase 5 (full), Phase 6 (+ Docker) | lint, test, integration, coverage, image smoke test |
| `.github/workflows/reusable-track-ci.yml` | Phase 5 | the full check for **one** track, called three times |
| `.github/workflows/deploy.yml` | Phase 6 | push images to GHCR on version tags; optional Render deploy |
| `.github/dependabot.yml` | Phase 5–6 | weekly dependency update PRs |

Official reference: <https://docs.github.com/actions>. When this page and the docs disagree, the docs win.
Please open an issue so we can fix this page.

---

## 1. Vocabulary in one minute
- **Workflow:** a YAML file in `.github/workflows/`. A repository can have many.
- **Event / trigger:** what starts a workflow (`push`, `pull_request`, a tag, a button click…), written under `on:`.
- **Job:** a group of steps that runs on **one fresh virtual machine** (a *runner*). Jobs run **in parallel** unless you connect them with `needs:`.
- **Step:** one shell command (`run:`) or one reusable building block (`uses:`), executed in order inside a job. Steps of the same job share the file system.
- **Action:** a reusable step published in a repository, e.g. `actions/checkout@v4`. `@v4` is a tag of that repository (see §15 on pinning).
- **Runner:** the machine. `runs-on: ubuntu-latest` = a GitHub-hosted Ubuntu VM with Docker, Git, `shellcheck`, Python, Node and Java preinstalled. Each job gets a brand-new one and it is destroyed afterwards.

```text
workflow (ci.yml)
 ├─ job: changes      ── runner #1 ── steps: checkout → paths-filter
 ├─ job: java   (needs: changes) ── runner #2 ── steps: checkout → setup-java → make …
 ├─ job: python (needs: changes) ── runner #3 ── …
 └─ job: ci-ok  (needs: all)     ── runner #N ── step: check results
```

## 2. Triggers (`on:`)
```yaml
on:
  push:
    branches: [main, solution]   # 1. a push to these branches
  pull_request:                  # 2. a PR is opened/updated (any target branch)
  workflow_dispatch:             # 3. a "Run workflow" button in the Actions tab
```
Step by step, what happens on a PR from your feature branch:
1. You push `feat/m1` and open a PR into `main`.
2. GitHub creates a temporary **merge commit** (your branch merged into `main`) and runs the workflow on it, so CI tests what `main` *would* look like.
3. Every new push to the branch re-runs it.

Other triggers we use:
- `push: tags: ["v*.*.*"]` (in `deploy.yml`): runs when you push a tag such as `v1.0.0`. The pattern is a glob, not a regex.
- `workflow_call` (in `reusable-track-ci.yml`): the workflow does not start by itself; other workflows call it like a function (§7).

**Pull requests from forks** run with a *read-only* token and **without your secrets**. This protects
you from a stranger's PR printing your secrets. It is also why the Render step must cope with an empty
secret (§10).

## 3. Jobs, steps and the keywords around them
```yaml
jobs:
  test:                                  # job id (used in needs: and in the UI)
    name: ${{ matrix.track }} · lint + unit   # display name
    runs-on: ubuntu-latest
    timeout-minutes: 15                  # kill a hung job instead of waiting the 6-hour default
    defaults:
      run:
        working-directory: tracks/${{ matrix.track }}   # every run: step starts in this folder
    steps:
      - uses: actions/checkout@v4        # clone the repository into the runner
      - name: Lint                       # optional label shown in the log
        run: make lint                   # executed with bash -e: the first failing command fails the step
```
- `needs: [a, b]`: wait for jobs `a` and `b`. If one of them fails, this job is **skipped**, unless it has `if: always()`.
- `if:` on a job or step: run only when the expression is true. Example: `if: matrix.track == 'java'`.
- `outputs:` on a job: values that later jobs read as `needs.<job>.outputs.<name>` (used by `changes`).
- `concurrency:` at the top:
  ```yaml
  concurrency:
    group: ci-${{ github.workflow }}-${{ github.ref }}
    cancel-in-progress: true
  ```
  If you push twice quickly to the same branch, the first run is cancelled. That saves minutes, and the latest commit is all you care about.

## 4. Expressions and contexts: `${{ … }}`
Anything inside `${{ }}` is evaluated by GitHub **before** the step runs. The objects you can read
are called *contexts*:

| Context | Example | Value |
|---|---|---|
| `github` | `${{ github.ref }}` | `refs/heads/main`, `refs/pull/12/merge`, `refs/tags/v1.0.0` |
| `github` | `${{ github.event_name }}` | `push`, `pull_request`, `workflow_dispatch` |
| `matrix` | `${{ matrix.track }}` | `java` / `python` / `typescript` |
| `needs` | `${{ needs.changes.outputs.java }}` | `'true'` or `'false'` (strings!) |
| `inputs` | `${{ inputs.track }}` | inputs of a reusable workflow |
| `secrets` | `${{ secrets.GITHUB_TOKEN }}` | masked in logs as `***` |
| `vars` | `${{ vars.RENDER_APP_URL }}` | non-secret configuration variables (§10) |
| `env` | `${{ env.RENDER_DEPLOY_HOOK_URL }}` | variables declared with `env:` |
| `job` | `${{ job.services.redis.id }}` | the Docker container id of a service container |

Useful functions: `always()`, `failure()`, `contains()`, `startsWith()`, `join()`, and the `||`
operator for defaults: `${{ vars.DEPLOY_TRACK || 'python' }}`.

Inside `run:` you can also use normal shell variables that GitHub sets for you: `$GITHUB_REF_NAME`
(`v1.0.0`), `$GITHUB_SHA`, `$GITHUB_REPOSITORY_OWNER` (`AICanCode-org`), `$GITHUB_OUTPUT` (a file you append
`name=value` lines to in order to create step outputs).

> **Security rule:** never put untrusted text such as `${{ github.event.pull_request.title }}`
> directly inside `run:`. It is pasted into the script *before* bash runs and can inject commands. Pass it
> through `env:` and use `"$VAR"` instead. Our workflows only interpolate values we control.

## 5. Matrix builds
```yaml
strategy:
  fail-fast: false            # keep the other tracks running if one fails
  matrix:
    track: [java, python, typescript]
```
GitHub expands one job definition into **three jobs**, one per value. Inside each, `matrix.track` has
that job's value. We combine it with conditional steps (`if: matrix.track == 'python'`) so each job only
installs its own toolchain. You see three entries in the UI: `java · lint + unit`, `python · …`, `typescript · …`.

Used in: starter `ci.yml` (`test`), Phase 6 `ci.yml` (`docker`), `deploy.yml` (`image`).

## 6. Caching (three different kinds)
**a) Package caches via the setup actions** (CI jobs):
```yaml
- uses: actions/setup-node@v4
  with:
    node-version: "22"
    cache: npm
    cache-dependency-path: tracks/typescript/package-lock.json
```
Step by step: (1) the action computes a key from the hash of the lock file; (2) if a cache with that
key exists, it restores `~/.npm` before `npm ci`; (3) at the end of a successful job it saves the
cache if the key was new. Change the lock file → new key → fresh cache. Same idea with
`setup-java` (`cache: maven` → `~/.m2/repository`, keyed on `pom.xml`) and `setup-python` (`cache: pip`).
`cache-dependency-path` is needed because our manifests live in sub-folders, not in the repo root.

**b) Docker layer cache in the GitHub Actions cache** (`docker` and `image` jobs):
```yaml
cache-from: type=gha,scope=${{ matrix.track }}
cache-to: type=gha,mode=max,scope=${{ matrix.track }}
```
BuildKit stores image layers in the same cache service. `scope` keeps the three tracks from overwriting
each other's cache; `mode=max` also caches the intermediate build stage (where dependencies are
downloaded), not just the final image.

**c) BuildKit cache mounts inside the Dockerfile** (Java): `RUN --mount=type=cache,target=/root/.m2 …`
speeds up local rebuilds. In CI the runner is fresh, so (b) does the heavy lifting.

Limits worth knowing: caches are scoped per branch (a PR can read the cache of its base branch), and
entries unused for 7 days or over the repository's size limit are evicted. A cache miss only makes
things slower, never wrong.

## 7. Reusable workflows
Definition (`reusable-track-ci.yml`):
```yaml
on:
  workflow_call:
    inputs:
      track:       { required: true,  type: string }
      integration: { required: false, type: boolean, default: true }
```
Call site (`ci.yml`):
```yaml
java:
  needs: changes
  if: needs.changes.outputs.java == 'true' || needs.changes.outputs.shared == 'true'
  uses: ./.github/workflows/reusable-track-ci.yml     # a whole workflow instead of runs-on/steps
  with:
    track: java
```
Step by step: (1) the caller job has **no** `runs-on` or `steps`, only `uses` + `with`; (2) GitHub runs the
reusable workflow's jobs as children of the caller job (shown as `java / lint · unit · integration ·
coverage` in the UI); (3) inside it, `inputs.track` is `java`. Secrets are **not** passed automatically:
you would add `secrets: inherit` or list them; our CI does not need any. A matrix (`strategy.matrix`)
can also be combined with `uses:`. We use three explicit jobs instead, so each can have its own path filter.

Why bother? The 60-line check is written once. Fix it once and all three tracks get the fix.

## 8. Service containers
```yaml
services:
  redis:
    image: redis:7.4-alpine
    ports: ["6379:6379"]
    options: >-
      --health-cmd "redis-cli ping"
      --health-interval 3s --health-timeout 3s --health-retries 20
```
Step by step: (1) before the first step, the runner starts this container; (2) `options` are `docker run`
flags; with a health check, GitHub **waits until it is healthy**; (3) `ports` publishes it on the runner, so
tests use `localhost:6379` exactly as on your laptop; (4) no password is needed: the container
lives for one job and is unreachable from outside; (5) after the job the container is deleted. The
job sets `TEST_REDIS_URL=redis://localhost:6379/15`, so the integration tests flush only database 15
of a Redis that exists for them alone.

## 9. Permissions and `GITHUB_TOKEN`
Every run gets an automatic, short-lived token, `secrets.GITHUB_TOKEN`, valid only for this repository
and only while the job runs. Its powers are set by `permissions:`:
```yaml
permissions:
  contents: read          # top of every workflow: least privilege by default
jobs:
  image:
    permissions:
      contents: read
      packages: write     # only this job may push to GHCR
```
Anything not listed is `none`. `changes` additionally asks for `pull-requests: read` because the path
filter lists a PR's changed files through the API.

## 10. Secrets and variables: where to click, step by step
**Secrets** (encrypted, masked as `***` in logs) vs **variables** (plain configuration, visible):

| Name | Kind | Scope | Used by |
|---|---|---|---|
| `GITHUB_TOKEN` | secret | automatic | GHCR login |
| `RENDER_DEPLOY_HOOK_URL` | secret | environment `production` | `deploy.yml` → `render` |
| `RENDER_APP_URL` | variable | environment `production` | health poll + smoke test |
| `DEPLOY_TRACK` | variable | environment or repository | which image Render deploys (default `python`) |

Add one in the UI:
1. Repository → **Settings** → **Environments** → **New environment** → name `production` → **Configure environment**.
2. Under **Environment secrets** → **Add environment secret** → name `RENDER_DEPLOY_HOOK_URL`, value = the hook URL from Render → **Add secret**.
3. Under **Environment variables** → **Add environment variable** → `RENDER_APP_URL` = `https://<your-service>.onrender.com`.

Or with the GitHub CLI: `gh secret set RENDER_DEPLOY_HOOK_URL --env production` (it prompts for the
value, so it never lands in your shell history) and `gh variable set RENDER_APP_URL --env production --body https://…`.

**Secrets cannot be used in `if:` conditions.** The workaround used in `deploy.yml`:
```yaml
env:
  RENDER_DEPLOY_HOOK_URL: ${{ secrets.RENDER_DEPLOY_HOOK_URL }}   # empty string if not set
steps:
  - if: env.RENDER_DEPLOY_HOOK_URL == ''
    run: echo "::notice title=Render deploy skipped::…"
  - if: env.RENDER_DEPLOY_HOOK_URL != ''
    run: curl -fsS -X POST "${RENDER_DEPLOY_HOOK_URL}&imgURL=…"
```
That is "skipped gracefully": no secret, so the job prints a notice (visible on the run summary) and succeeds.

## 11. Environments
```yaml
render:
  environment:
    name: production
    url: ${{ vars.RENDER_APP_URL || 'https://render.com' }}
```
An environment is a named deployment target with its own secrets/variables and **protection rules**:
1. **Required reviewers:** the job pauses with "Waiting for review" until someone you named clicks *Approve*.
2. **Deployment branches and tags:** e.g. allow only tags matching `v*`, so a random branch cannot deploy.
3. **Wait timer:** delay before the job starts.
4. The `url` appears on the run page and in the repository's **Deployments** list, so you can see which version is live.

Environment secrets are only given to jobs that declare that environment. They are safer than
repository secrets.

## 12. GitHub Container Registry (GHCR), step by step
1. **Name:** `ghcr.io/<owner>/<image>`. It must be **lower-case**, and our org is `AICanCode-org`, hence
   `ghcr.io/${GITHUB_REPOSITORY_OWNER,,}/rateguard-<track>` (bash `,,` = to lower case). Result:
   `ghcr.io/aicancode-org/rateguard-python`.
2. **Login:** `docker/login-action@v3` with `registry: ghcr.io`, `username: ${{ github.actor }}`, `password: ${{ secrets.GITHUB_TOKEN }}`.
3. **Permission:** the job needs `packages: write` (§9).
4. **Tags:** `docker/metadata-action@v5` turns git tag `v1.1.0` into image tags `1.1.0`, `1.1`,
   `sha-1a2b3c4` and `latest`, and adds OCI labels, including `org.opencontainers.image.source`, which
   links the package to the repository on its first push.
5. **Push:** `docker/build-push-action@v6` with `push: true`.
6. **Visibility:** a new package is **private**. To let Render (or anyone) pull without credentials:
   the package page → **Package settings** → **Change visibility** → Public. In an organisation, an owner may
   first have to allow public packages: **Org settings → Packages → Package creation**.
7. **Pull:** `docker pull ghcr.io/aicancode-org/rateguard-python:1.1.0`.

## 13. Path filters and the aggregator job
GitHub's built-in `on: push: paths:` filter skips the **whole workflow**, and a required check that never
runs blocks the PR forever. So we always run the workflow, and decide per job:
1. `changes` runs `dorny/paths-filter@v3`, which compares the PR's files with the patterns and outputs `'true'`/`'false'` per area.
2. Each track job has `if: needs.changes.outputs.<track> == 'true' || needs.changes.outputs.shared == 'true'`.
3. `ci-ok` has `if: always()` and `needs:` on everything, and fails only if a result is `failure` or `cancelled`.
   Skipped jobs are fine.
4. Branch protection requires **only** `ci-ok`.

## 14. Artefacts
```yaml
- if: always() && inputs.integration
  uses: actions/upload-artifact@v4
  with:
    name: coverage-${{ inputs.track }}
    path: |
      tracks/java/target/site/jacoco/
      tracks/python/coverage.xml
      tracks/typescript/coverage/
    if-no-files-found: ignore
    retention-days: 7
```
Files saved from a job, downloadable from the run page for 7 days. `if: always()` uploads even when the
coverage gate failed. That is exactly when you want the report.

## 15. Pinning, Dependabot and actionlint
- `uses: actions/checkout@v4` follows the `v4` tag, which the publisher can move. For maximum supply-chain
  safety, pin a full commit SHA (`@<40-char-sha> # v4.2.2`). We use major tags for readability, and Dependabot
  (`package-ecosystem: github-actions`) proposes updates either way.
- `actionlint` (<https://github.com/rhysd/actionlint>) statically checks workflows, expressions and embedded
  shell (via shellcheck). Run it before pushing; the reference workflows pass it with no findings.

## 16. Debugging a red run
1. Open the failed step and read the **last** error, then scroll up to the first one.
2. Reproduce locally with the same command (`cd tracks/<track> && make coverage`) and the same services
   (`docker compose up -d redis`).
3. Still unclear? **Re-run jobs → Enable debug logging**, or add a step `run: env | sort` (secrets stay masked).
4. Only one runner image differs from your laptop? Check versions: `java -version`, `node -v`, `python --version`.

## 17. Annotated walk-through of a tag push (`git push origin v1.1.0`)
1. Event `push` with `github.ref = refs/tags/v1.1.0` → `deploy.yml` matches `v*.*.*` (`ci.yml` does not run for tags).
2. Job `image` expands into 3 matrix jobs. Each: checkout → lower-case image name → buildx → GHCR login with
   `GITHUB_TOKEN` (`packages: write`) → metadata (`1.1.0`, `1.1`, `sha-…`, `latest`) → build with the GHA cache → push.
3. Job `render` waits for all three (`needs: image`), enters environment `production` (approval if configured),
   reads the secret into `env`.
   - No secret → notice, success. Done.
   - Secret → `curl` the deploy hook with `imgURL=ghcr.io/aicancode-org/rateguard-python:1.1.0` (URL-encoded) →
     poll `$RENDER_APP_URL/health` every 15 s for up to 10 min (cold starts) → run `scripts/smoke-test.sh` against the live URL.
