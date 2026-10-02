# Redis Lua scripts (Phase 2 deliverable)

Every track loads two scripts from this folder at startup (`LUA_DIR`, default `../../spec/lua`
from `tracks/<track>`, `/app/lua` in the Docker images):

| File | Algorithm | Returns |
|---|---|---|
| `token_bucket.lua` | Token bucket: `limit` tokens, refilled continuously over `window_ms` | `{allowed, remaining, retry_after_ms, reset_after_ms}` |
| `sliding_window.lua` | Sliding window counter: current + weighted previous fixed window | `{allowed, remaining, retry_after_ms, reset_after_ms}` |

The calling convention is fixed so all three tracks can share the scripts:

```text
KEYS[1] = limiter state key   rg:{<tenantId>}:tb:<resource>  or  rg:{<tenantId>}:sw:<resource>
KEYS[2] = usage ring key       rg:{<tenantId>}:usage
ARGV[1] = limit (integer > 0)
ARGV[2] = window_ms (integer > 0)
ARGV[3] = cost (integer >= 1)
ARGV[4] = now_ms (optional; tests only, otherwise the script reads redis.call('TIME'))
```

Write them in Phase 2 from the exact algorithm rules in `requirements.md` (FR-7, FR-8) and try
them by hand with `redis-cli EVAL` and a fixed `now_ms` (`docs/phase-2-design.md`, Step 2.4). In
Phase 3 M2 you write the same maths as pure functions in your track; in Phase 4 the integration tests
prove that both give identical answers step by step. Each script must:

1. Do its whole read-decide-write cycle in one call (a script runs atomically in Redis).
2. Use Redis `TIME` as the clock, so several app instances never disagree about "now".
3. Set a TTL on every key it writes, so idle tenants cost no memory.
4. Count the request in the per-minute usage ring (allowed and blocked separately).

Reference: `git show phase-2-end:spec/lua/token_bucket.lua`. Try on your own first!
