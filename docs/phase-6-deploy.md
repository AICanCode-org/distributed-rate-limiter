---
phase: 6
slug: phase-6-deploy
title: Containerise, deliver, and (optionally) deploy to a free tier
duration: 3 h
start_tag: phase-5-end
end_tag: phase-6-end
release: v1.0.0
---
# Phase 6 · Containerise, deliver and (optionally) deploy

## 1. Why it matters
A container image is the unit you actually ship: the same bytes run on your laptop, in CI and in
the cloud. In this phase you write a production-grade Dockerfile, run the whole stack with one
command, prove it with a black-box smoke test, run **two instances of your track against one Redis**
the way it would run in production, and publish versioned images to GitHub Container Registry (GHCR)
automatically on every release tag. Deploying to the cloud is **optional**: everything graded in this
phase works offline on your laptop.

## 2. Objectives
- Write a multi-stage, non-root Dockerfile with a `HEALTHCHECK` for your track that ships the shared Lua scripts.
- Add your app to `docker-compose.yml` as a profile and start the stack with `docker compose --profile <track> up`.
- Run the language-agnostic smoke test against your container (and against the other tracks' images).
- Run two instances behind one Redis (`pair` profile) and repeat the load test.
- Add a Docker build + smoke job to CI.
- Write `deploy.yml`: on a `v*.*.*` tag, build and push images to GHCR; optionally trigger a Render
  deploy that is **skipped gracefully** when no secret is configured.
- Cut release **v1.0.0** with a CHANGELOG entry.

## 3. Step-by-step instructions

### Step 6.1 · The Dockerfile (build context = repository root)
All tracks copy the shared Lua scripts into the image, so the build context is the repo root and the
Dockerfile lives in the track: `docker build -f tracks/<track>/Dockerfile .`. Rules:
- **Multi-stage:** compilers and dev dependencies stay in the build stage.
- **Dependencies before source:** copy the manifest, install, then copy `src`, so code changes reuse the dependency layer.
- **Non-root user**, `EXPOSE 8080`, `HEALTHCHECK` hitting `/health`, `COPY spec/lua ./lua` and `LUA_DIR=/app/lua`.

<!-- track:java -->
```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY tracks/java/pom.xml tracks/java/checkstyle.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY tracks/java/src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests -Dcheckstyle.skip -Djacoco.skip=true \
    && java -Djarmode=tools -jar target/app.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:21-jre
ENV PORT=8080 LUA_DIR=/app/lua JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
WORKDIR /app
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --no-create-home app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
COPY spec/lua ./lua
USER 10001
HEALTHCHECK CMD curl -fsS http://127.0.0.1:8080/health > /dev/null || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```
(abridged; the full file installs `curl` and sets the health-check timings)
<!-- /track -->

<!-- track:python -->
```dockerfile
FROM python:3.12-slim AS build
WORKDIR /build
COPY tracks/python/requirements.txt .
RUN pip wheel --no-cache-dir --wheel-dir /wheels -r requirements.txt

FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PORT=8080 LUA_DIR=/app/lua
WORKDIR /app
COPY --from=build /wheels /wheels
RUN pip install --no-cache-dir /wheels/* && rm -rf /wheels \
    && useradd --system --uid 10001 --no-create-home appuser
COPY tracks/python/app ./app
COPY spec/lua ./lua
USER 10001
EXPOSE 8080
HEALTHCHECK CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8080/health', timeout=2).status == 200 else 1)"
CMD ["python", "-m", "app"]
```
<!-- /track -->

<!-- track:typescript -->
```dockerfile
FROM node:22-alpine AS build
WORKDIR /build
COPY tracks/typescript/package.json tracks/typescript/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY tracks/typescript/tsconfig.json tracks/typescript/tsconfig.build.json ./
COPY tracks/typescript/src ./src
RUN npm run build

FROM node:22-alpine
ENV NODE_ENV=production PORT=8080 LUA_DIR=/app/lua
WORKDIR /app
COPY tracks/typescript/package.json tracks/typescript/package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund && npm cache clean --force
COPY --from=build /build/dist ./dist
COPY spec/lua ./lua
USER node
EXPOSE 8080
HEALTHCHECK CMD node -e "fetch('http://127.0.0.1:8080/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
CMD ["node", "dist/index.js"]
```
<!-- /track -->

The repository's `.dockerignore` keeps the build context small and excludes `.env`, so local secrets
never reach the Docker daemon. Build and check the size and user:
```bash
docker build -f tracks/<track>/Dockerfile -t rateguard-<track>:local .
docker image ls rateguard-<track>
docker run --rm --entrypoint id rateguard-<track>:local      # must NOT be uid=0
```

### Step 6.2 · Compose profiles: one command for the whole stack
Add a service per track to `docker-compose.yml` under a **profile**, sharing one block of environment
variables (a YAML anchor `x-app-env`), `depends_on` with `condition: service_healthy`, and port
`${APP_PORT:-8080}:8080`. Inside Compose the app reaches Redis as `redis://redis:6379/0`, not
`localhost`. The Compose file sets a **local-only** `ADMIN_TOKEN` through `${VAR:-default}`, so
`docker compose up` works out of the box. A real deployment sets its own value: the default is
public, in this repository. Redis runs with `--appendonly yes`, so tenants and keys survive a restart.
```bash
docker compose --profile <track> up -d --build --wait
docker compose ps
```

### Step 6.3 · Smoke test (the same for every track)
```bash
scripts/smoke-test.sh                      # default http://localhost:8080
```
It checks 53 behaviours with only `bash`, `curl` and `xargs`: health, `404` and `401` as
`problem+json`, creating a token-bucket tenant (the key is shown once and never again), a duplicate
id (`409`), an id that could break a Redis key (`400`), a check with all `X-RateLimit-*` headers, a
cost above the limit (`422`), an unknown key (`401`), three allowed `hello`s and a `429` with
`Retry-After`, a sliding-window tenant whose denied check is still a `200` answer, **parallel**
checks against a limit of 5 (exactly 5 allowed), reset, key rotation (the old key dies at once), a
policy change, usage, advice, and deleting a tenant (its key is revoked). It creates its own uniquely
named tenants and deletes them at the end, so it is safe to run repeatedly. Against a deployment,
pass the admin token as `SMOKE_ADMIN_TOKEN`.

Because the contract and the scripts are shared, try another track's image against the same Redis:
`docker compose stop app-<yours>` and `docker compose --profile python up -d`. Same results. Also run
the AI fallback check:
```bash
AI_PROVIDER=llm LLM_BASE_URL=http://127.0.0.1:9/v1 LLM_TIMEOUT_MS=1000 docker compose --profile <track> up -d --wait
EXPECT_FALLBACK=true scripts/smoke-test.sh      # the advice reports provider "rules" and fallback: true
```

### Step 6.4 · Two instances, one Redis
The `pair` profile starts two containers of the same image on ports 8081 and 8082:
```bash
TRACK=<track> docker compose --profile pair up -d --build --wait
python3 scripts/load-test.py                   # http://localhost:8081 and http://localhost:8082
```
`allowed` must equal the limit, exactly as in Phase 4, now with real containers and a real network.
Stop one of the two in the middle of a run (`docker compose stop app-b`): the other keeps enforcing
the same limit, because no instance holds any state.

### Step 6.5 · CI: build the image and smoke-test it
Add a `docker` job (matrix over tracks) to `ci.yml`: `docker/setup-buildx-action`,
`docker/build-push-action` with `load: true` and the GitHub Actions layer cache (`cache-from/to: type=gha`),
then `docker compose --profile <track> up -d --no-build --wait`, the smoke test, logs on failure and `down -v`.

### Step 6.6 · Continuous delivery to GHCR: `deploy.yml`
Trigger on `push: tags: ["v*.*.*"]`. The job needs `permissions: packages: write` and logs in to
`ghcr.io` with the built-in `GITHUB_TOKEN`; no personal token required. Image names must be lower-case
(the org is `AICanCode-org`), so compute `ghcr.io/${GITHUB_REPOSITORY_OWNER,,}/rateguard-<track>`.
`docker/metadata-action` turns tag `v1.0.0` into image tags `1.0.0`, `1.0`, `sha-<short>` and `latest`.
Read `docs/github-actions-explained.md` §9–§12 for every detail.

### Step 6.7 · Release v1.0.0
```bash
# update CHANGELOG.md: move "Unreleased" items under "## [1.0.0] - <date>"
git commit -am "chore(release): v1.0.0"
git tag -a v1.0.0 -m "RateGuard v1.0.0"
git push origin main --follow-tags
```
Watch **Actions → Deploy**: three images appear under your profile's **Packages**. New GHCR packages
are **private** by default. Make one public (Package settings → Change visibility) if a host should pull
it without credentials.

### Step 6.8 · OPTIONAL: deploy to a free tier
You do not need a cloud account to finish this course. If you want a public URL:

| Piece | Free option | Notes |
|---|---|---|
| App | **Render** web service from an *existing image* (`ghcr.io/<you>/rateguard-<track>:latest`) | Free instances spin down after 15 minutes without traffic; the next request takes about a minute (cold start). |
| Redis | **Upstash** Redis free tier, or Render Key Value (free) | Use the TLS URL (`rediss://…`) as `REDIS_URL`. Free plans limit commands per day and memory, and some do not persist data: read the limits, and remember tenants live in Redis. |
| Secrets | Render env vars | Set your own `ADMIN_TOKEN` (`openssl rand -hex 32`). Never reuse the Compose default. |
| LLM | none (rules), or a free Gemini / Groq key | `AI_PROVIDER=llm`, `LLM_BASE_URL`, `LLM_MODEL`, `LLM_API_KEY` as Render env vars. Only usage numbers are sent, but say so in your README. |

Free-tier limits change. Check each provider's pricing page before you rely on it. Fly.io is **not**
used: at the time of writing it needs a payment method and bills usage, with no free tier for new accounts.

To let CI deploy for you:
1. Render → your service → **Settings → Deploy Hook**: copy the URL.
2. GitHub → **Settings → Environments → New environment `production`** (optionally add yourself as a
   required reviewer). Add the **secret** `RENDER_DEPLOY_HOOK_URL` and the **variables** `RENDER_APP_URL`
   (e.g. `https://rateguard-xyz.onrender.com`) and `DEPLOY_TRACK` (`java`, `python` or `typescript`).
   Optionally add the secret `SMOKE_ADMIN_TOKEN` (your live `ADMIN_TOKEN`) so the full smoke test runs
   against the live app; without it only `/health` is checked.
3. Push a tag. The `render` job calls the hook with `imgURL=` set to the exact version, waits for
   `/health`, and runs the smoke test against the live URL. Without the secret the job prints a notice
   and **succeeds**, so forks without cloud accounts stay green.

## 4. AI-assist prompts

### Prompt 1: Review my Dockerfile
```text
Review this Dockerfile for a <Spring Boot 4 / FastAPI / Fastify> service that loads Redis Lua scripts
from LUA_DIR. Check: multi-stage, layer caching order, non-root user, HEALTHCHECK, image size, secrets
leaking into layers, pinned base image versions, and anything that would differ between my laptop
(amd64/arm64) and CI. <paste Dockerfile>
```
**Verify the output by:** rebuilding and measuring: `docker image ls` (size), `docker run --rm --entrypoint id`
(uid), `docker history` (no secrets, small layers), `docker run --rm --entrypoint ls rateguard-<track>:local /app/lua`
(both scripts present) and `docker compose up --wait` (health check passes).

### Prompt 2: Explain the deploy workflow's security model
```text
In this workflow <paste deploy.yml>, which token pushes to GHCR, what can it do, and for how long?
Are RENDER_DEPLOY_HOOK_URL and SMOKE_ADMIN_TOKEN exposed to pull requests from forks? What would
happen if someone pushed a tag from a fork?
```
**Verify the output by:** reading the GitHub docs pages "Automatic token authentication" and "Using secrets
in GitHub Actions" (secrets are not passed to workflows triggered from forks), and checking that
`packages: write` appears only on the image job.

### Prompt 3: Cold start and a free Redis
```text
My container works locally but on Render /health times out for the first minute after idle, then works,
and after a few days all my tenants are gone. Explain cold starts on free tiers, what my smoke test
should do about them, how to tell a cold start apart from a crash in the logs, and what a free Redis
plan may do with my data (eviction, persistence, command limits).
```
**Verify the output by:** reading your service's Render logs for the boot sequence (scripts loaded,
listening on port), timing `curl -w '%{time_total}' $URL/health` after 20 minutes idle, and checking
your Redis provider's documentation for persistence and eviction on the free plan.

## 5. Deliverables
- [ ] `tracks/<track>/Dockerfile` (multi-stage, non-root, HEALTHCHECK, Lua scripts in `/app/lua`).
- [ ] `docker compose --profile <track> up -d --build --wait` + `scripts/smoke-test.sh`: all checks pass,
  and `EXPECT_FALLBACK=true` passes with an unreachable LLM.
- [ ] The load test against the `pair` profile: `allowed` = the limit.
- [ ] CI `docker` job green; `deploy.yml` pushed images to GHCR for tag `v1.0.0`.
- [ ] `CHANGELOG.md` with `[1.0.0]`; `docs/runbook.md` with start/stop/rollback/rotate-token steps.
- [ ] Optional: a live URL in your README and a green `render` job.

## 6. Self-check quiz

### Q1. Why do the Dockerfiles copy the dependency manifest before the source code?
- [ ] Docker requires that order
- [x] So that the dependency layer is cached and only rebuilt when the manifest changes
- [ ] To make the image smaller
- [ ] To run tests faster
> **Answer:** Layers are cached top-down; anything after a changed layer is rebuilt. Source changes often, dependencies rarely.

### Q2. Inside Docker Compose, which `REDIS_URL` does the app use?
- [ ] `redis://localhost:6379/0`
- [x] `redis://redis:6379/0` (the service name)
- [ ] `redis://127.0.0.1:6379/0`
- [ ] `redis://host.docker.internal:6379/0`
> **Answer:** Each container has its own `localhost`. Compose gives services DNS names on a shared network.

### Q3. Why does the build context have to be the repository root?
- [ ] Docker cannot build from sub-folders
- [x] The image needs `spec/lua/` as well as the track's code, and a build can only copy files inside its context
- [ ] To include the `.env` file
- [ ] To speed up the build
> **Answer:** The scripts are shared by all tracks, so they live outside `tracks/<track>`. `.dockerignore` keeps everything else (and `.env`) out of the context.

### Q4. A fork has no `RENDER_DEPLOY_HOOK_URL` secret. What does the `render` job do on a tag push?
- [ ] Fails the workflow
- [ ] Deploys to a default account
- [x] Prints a notice and succeeds; the image is still pushed to the fork's GHCR
- [ ] Waits for someone to add the secret
> **Answer:** Optional infrastructure must not make the pipeline red. Secrets are read into `env:` and tested with `if: env.X == ''`.

### Q5. You stop one of the two `pair` instances during a load test. What happens to the limit?
- [ ] It doubles, because the other instance forgets the shared state
- [ ] It halves
- [x] Nothing: all state is in Redis, so the remaining instance enforces exactly the same limit
- [ ] Requests fail until both are back
> **Answer:** Stateless app instances can come and go (deploys, crashes, autoscaling) without changing any decision.

### Q6. Why must a deployment never use the `ADMIN_TOKEN` from `docker-compose.yml`?
- [ ] It is too short
- [x] It is public in the repository, so anyone could create tenants, read usage and reset limits on your service
- [ ] Render rejects it
- [ ] It expires after 30 days
> **Answer:** Defaults are for laptops; deployments get their own token from the host's environment variables.

## 7. What you learned
- Production Dockerfiles: multi-stage, layer caching, non-root, health checks, `.dockerignore`, shared files in the image.
- Compose profiles to run any track, or two instances of one, against the same Redis with one command.
- Black-box smoke tests that work for any implementation of a contract.
- Image builds with layer caching in CI, and delivery to GHCR with the built-in token on version tags.
- Optional, secret-gated deployment that degrades gracefully, plus free-tier trade-offs (cold starts, Redis limits).
- Semantic versioning and a CHANGELOG-driven release.

## 8. Catch-up git commands
```bash
git fetch upstream --tags
git switch -c my-phase-7 phase-6-end          # == v1.0.0
git checkout phase-6-end -- tracks/<track>/Dockerfile docker-compose.yml scripts/smoke-test.sh
git checkout phase-6-end -- .github/workflows/deploy.yml
```
