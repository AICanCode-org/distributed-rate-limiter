# RateGuard runbook

Operational how-tos for RateGuard v1.x. Keep it short, keep it true: if a step here is wrong, fix it in
the same PR that taught you so.

| | |
|---|---|
| Service | RateGuard REST API (one track deployed: `DEPLOY_TRACK`, default `python`; any number of instances) |
| Images | `ghcr.io/aicancode-org/rateguard-{java,python,typescript}:<version>` (`latest` = newest release) |
| Health | `GET /health` → `200 {"status":"UP","checks":{"redis":"UP"}}`; `503` when Redis is unreachable (v1.1: `200` `DEGRADED`) |
| Dependencies | Redis 7 (required: tenants, keys, limiter state, usage), LLM endpoint (optional; rules fallback, advice only) |
| Configuration | environment variables only: see `.env.example`. A deployment MUST set its own `ADMIN_TOKEN` (`openssl rand -hex 32`); the code does not enforce a length |

## 1. Start and stop
**Laptop (Docker Compose):**
```bash
docker compose --profile python up -d --build --wait     # or --profile java / typescript
scripts/smoke-test.sh http://localhost:8080              # expect "... 0 failed"
docker compose --profile python logs -f app-python       # follow logs
docker compose --profile python down                     # stop (keeps Redis data)
docker compose --profile python down -v                  # stop AND delete the Redis volume (all tenants!)
```
**Two instances, one Redis:** `TRACK=python docker compose --profile pair up -d --build --wait`
(ports 8081 and 8082), then `python3 scripts/load-test.py`.

**Without Docker for the app:** `docker compose up -d redis`, then `cd tracks/<track> && make run`.
Port clash with another Redis or app? `REDIS_PORT=56379 APP_PORT=8081 docker compose …`, and point
`REDIS_URL` (and `TEST_REDIS_URL` for the tests) at the new port.

**Render (optional):** Dashboard → service → **Manual Deploy** / **Suspend** / **Resume**. A free
instance sleeps when idle; the first request can take up to a minute. Callers of `/limits/check` must
treat a slow or failed answer like a `503`: retry later, never "allowed".

## 2. Check that it works
1. `curl -s $URL/health`. `redis` must be `UP`.
2. `SMOKE_ADMIN_TOKEN=… scripts/smoke-test.sh $URL`. The smoke test creates its own tenants with unique
   ids (`smoke-…`), checks limits, rotates and resets, and **deletes them at the end**. If it is
   interrupted, list leftovers with `GET /api/v1/admin/tenants` and delete the `smoke-…` ones.
3. `docker compose exec redis redis-cli INFO keyspace` and `--scan --pattern 'rg:*' | head`: keys look like
   `rg:{<tenant>}:tb:<resource>`; limiter keys have a TTL (`TTL <key>` > 0).
4. From v1.1: `curl -s $URL/metrics | grep _total`.

## 3. Release
1. Make sure `solution`/`main` CI is green (`ci-ok`).
2. `CHANGELOG.md`: move *Unreleased* under `## [x.y.z] - YYYY-MM-DD`; bump the track versions.
3. `git tag -a vX.Y.Z -m "RateGuard vX.Y.Z" && git push origin vX.Y.Z`.
4. Watch **Actions → Deploy**: three `GHCR · <track>` jobs, then `Render (optional)`.

## 4. Roll back
Data in Redis only ever changes in a backward-compatible way (new hash fields are optional; a missing
field means the old behaviour), so the previous image runs fine against data written by a newer one.
v1.0.0 reading tenants created by v1.1 (with `failMode`) is tested, and v1.0.0 keeps the field when it
updates a policy.

**Render:** call the deploy hook with the previous image:
```bash
IMG="ghcr.io/aicancode-org/rateguard-python:1.0.0"
curl -fsS -X POST "$RENDER_DEPLOY_HOOK_URL&imgURL=$(python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1],safe=""))' "$IMG")"
```
Or Dashboard → **Events** → pick the previous deploy → **Rollback**. Then run the smoke test.

**Laptop:** `git switch --detach v1.0.0 && docker compose --profile python up -d --build --wait`.

Never "roll back" by deleting a git tag or a GHCR version. Releases are immutable; fix forward with a
new patch version. Never "fix" a limiter problem with `FLUSHALL`: it deletes every tenant and key, not
just limiter state. Use `POST /api/v1/admin/tenants/{id}/reset` for one tenant.

## 5. Rotate a secret
| Secret | Where it lives | Rotate | Effect |
|---|---|---|---|
| `ADMIN_TOKEN` | host env vars (Render) | generate `openssl rand -hex 32`, update **every** instance, redeploy | the old token stops working once every instance runs with the new one; update `SMOKE_ADMIN_TOKEN` in GitHub too |
| A tenant's API key | Redis (as SHA-256) | `POST /api/v1/admin/tenants/{id}/rotate-key`, hand the new key to the tenant | the old key gets `401` at once; the tenant's limiter state and usage are kept |
| `REDIS_URL` password | host env vars + Redis provider | change it at the provider, update, redeploy, smoke test | instances with the old password get `503` (v1.1: degraded) until redeployed |
| `LLM_API_KEY` | host env vars | revoke in the provider console, create a new one, update, redeploy | until then advice comes from the rules (`fallback: true`) |
| `RENDER_DEPLOY_HOOK_URL` | GitHub environment `production` | Render → Settings → Deploy Hook → **Regenerate**, then `gh secret set RENDER_DEPLOY_HOOK_URL --env production` | |

**Suspected leak of `ADMIN_TOKEN`:** rotate it immediately (anyone with it can create tenants, read
usage, reset limits and rotate other tenants' keys), then review `GET /api/v1/admin/tenants` for tenants
you did not create. **Suspected leak of a tenant key:** rotate that tenant's key. If a secret leaked
(committed, pasted into a prompt, printed in a log): **rotate first**, then clean up. Removing it from
git history does not un-leak it.

## 6. Common incidents
| Symptom | Likely cause | Action |
|---|---|---|
| `/health` → `503`, `redis: DOWN`; checks answer `503 limiter-unavailable` | Redis unreachable / wrong `REDIS_URL` | Check the Redis provider status and the URL (`rediss://` for TLS); app logs show the client error. Callers retry after `Retry-After: 1`. (v1.1: per-tenant `failMode`, see the post-mortem.) |
| Every check takes ~1 s, then `503` | Redis frozen or very slow (long command, snapshot fork, network) | `redis-cli --latency`, `SLOWLOG GET 10`. Look for `KEYS` or huge values. Restarting app instances does not help. |
| All tenants get `401 invalid-api-key` after a Redis restart | Redis lost its data (no persistence) | Restore from the AOF/RDB backup (§7). Compose enables `appendonly`; check your provider's persistence settings. |
| A tenant gets more than its limit | several RateGuard deployments with **different** Redis instances, or a client calling the protected API without asking RateGuard | Every instance must use the same `REDIS_URL`. RateGuard answers "may I?"; the caller must enforce the answer. |
| A tenant says "we are always limited" | the policy is too small for its real demand, or a retry loop | `GET …/usage?minutes=60` and `POST …/advice`. `INVESTIGATE` means a spike: talk to the tenant before raising anything. |
| `NOSCRIPT` errors in the logs | Redis restarted or failed over and lost its script cache | Handled: the store reloads the script and retries. Repeated errors mean `LUA_DIR` is wrong or the scripts are missing from the image. |
| Container restarts at boot | the Lua scripts cannot be read (`LUA_DIR`), or Redis is unreachable at start-up | Logs show the cause. The images ship the scripts in `/app/lua`. |
| Advice is slow | LLM slow or hanging: each call waits up to `LLM_TIMEOUT_MS` | Set `AI_PROVIDER=offline` and redeploy, or lower `LLM_TIMEOUT_MS`. Checks are never affected. (v1.1.0 adds a circuit breaker.) |
| Advice always `fallback: true` | LLM unreachable, slow or key invalid | Expected without an LLM. Otherwise check `LLM_BASE_URL`, `LLM_MODEL` (model names change) and the key. |
| First request after idle takes ~1 min | free-tier cold start | Expected. Upgrade or accept. |

## 7. Data
- What is in Redis: tenant records (`rg:tenant:<id>`), key lookups (`rg:key:<sha256>`), the tenant
  list (`rg:tenants`): **back these up**. Limiter state and usage expire on their own and can be lost.
- Back up (Compose): `docker compose exec redis redis-cli BGSAVE`, then copy `/data/dump.rdb` (and the
  `appendonlydir`) out of the `redisdata` volume. Hosted Redis: use the provider's backups.
- Restore: stop Redis, put the files back into the volume, start it. Keys rotated or tenants created
  since the backup are lost; tell the affected tenants.
- Backups contain no usable credentials (only SHA-256 hashes of keys), but they do contain tenant
  names and usage: limit access.
- Privacy: RateGuard stores no end-user data, no IPs and no request contents; usage is counts per
  minute per tenant. The LLM only ever sees the policy and those counts.
