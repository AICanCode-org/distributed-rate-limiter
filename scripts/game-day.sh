#!/usr/bin/env bash
# Game day (Phase 7): Redis freezes while tenants are calling the limiter. Measure the impact on one track.
# Usage:  scripts/game-day.sh [python|typescript|java]      (needs Docker Compose; run from the repo root)
# The fault is `docker pause` on the Redis container: connections are still accepted by the kernel, but Redis
# never answers (like a Redis stuck in a long command, a full disk or a network partition). Phases:
# baseline -> Redis frozen -> /health under load -> Redis back -> after the breaker cool-down.
# Three tenants are created with failMode CLOSED, OPEN and LOCAL (v1.0 ignores failMode: every tenant is CLOSED).
# Env:    N=10 (requests per tenant per phase)  N_PAR=40 (concurrent requests while probing /health)
#         REDIS_TIMEOUT_MS=1000  COOLDOWN_WAIT=6 (seconds; the v1.1 Redis breaker cools down after 5 s)
#         BASE_URL=http://localhost:8080  DC="docker compose"  DOCKER=docker
#         SMOKE_ADMIN_TOKEN (default: the local Compose value)
# With sudo, keep the environment: DC="sudo -E docker compose" DOCKER="sudo docker".
set -u
TRACK=${1:-python}
N=${N:-10}
N_PAR=${N_PAR:-40}
B=${BASE_URL:-http://localhost:8080}
export REDIS_TIMEOUT_MS=${REDIS_TIMEOUT_MS:-1000}
read -r -a DC <<< "${DC:-docker compose}"
read -r -a DOCKER <<< "${DOCKER:-docker}"
ADMIN_TOKEN="${SMOKE_ADMIN_TOKEN:-local-admin-token-change-me-0123456789abcdef}"
ts() { date -u '+%H:%M:%SZ'; }
field() { grep -o "\"$1\":[[:space:]]*\"[^\"]*\"" | head -1 | sed -E 's/.*"([^"]*)"$/\1/'; }
admin() { curl -s -X "$1" "$B$2" -H "Authorization: Bearer $ADMIN_TOKEN" ${3:+-H 'Content-Type: application/json' -d "$3"}; }

burst() { # $1 = label, $2 = requests per tenant
  local mode key total max r code t ms statuses degraded
  echo "$(ts) $1"
  for mode in CLOSED OPEN LOCAL; do
    key=$(eval echo "\$KEY_$mode"); total=0; max=0; statuses=""; degraded=0
    for _ in $(seq "$2"); do
      r=$(curl -s -o /dev/null -D /tmp/gd-headers.txt -w '%{http_code} %{time_total}' --max-time 30 \
        -H "X-API-Key: $key" "$B/api/v1/demo/hello")
      code=${r% *}; t=${r#* }
      statuses="$statuses $code"
      grep -qi '^x-ratelimit-degraded: true' /tmp/gd-headers.txt && degraded=$((degraded + 1))
      ms=$(awk -v t="$t" 'BEGIN { printf "%d", t * 1000 }'); [ "$ms" -gt "$max" ] && max=$ms
      total=$(awk -v a="$total" -v b="$t" 'BEGIN { print a + b }')
    done
    printf '  %-6s %s requests, statuses: %s| degraded: %s, avg %s ms, max %s ms\n' "$mode" "$2" \
      "$(echo "$statuses" | tr ' ' '\n' | sed '/^$/d' | sort | uniq -c | tr -s ' \n' ' ')" "$degraded" \
      "$(awk -v a="$total" -v n="$2" 'BEGIN { printf "%.0f", a / n * 1000 }')" "$max"
  done
}
health_under_load() { # N_PAR concurrent requests, then time /health while they are in flight
  local pids=()
  for _ in $(seq "$N_PAR"); do
    curl -s -o /dev/null --max-time 60 -H "X-API-Key: $KEY_OPEN" "$B/api/v1/demo/hello" & pids+=($!)
  done
  sleep 0.5
  echo "  /health while $N_PAR requests are in flight: $(curl -s -w ' -> %{http_code} in %{time_total}s' --max-time 60 "$B/health")"
  wait "${pids[@]}"
}
report() {
  local m; m=$(curl -s --max-time 10 "$B/metrics" | grep -E '^rateguard_(degraded_decisions|store_errors|store_short_circuits)_total' | tr '\n' ' ')
  echo "  metrics: ${m:-(no /metrics before v1.1)}"
}

echo "$(ts) game day: Redis freezes, track=$TRACK, REDIS_TIMEOUT_MS=$REDIS_TIMEOUT_MS"
"${DC[@]}" --profile "$TRACK" up -d --wait "app-$TRACK" >/dev/null 2>&1 || { echo "could not start app-$TRACK"; exit 1; }
REDIS=$("${DC[@]}" ps -q redis)
RUN="$(date +%s)$RANDOM"
for mode in CLOSED OPEN LOCAL; do
  id="gd-$(echo "$mode" | tr '[:upper:]' '[:lower:]')-$RUN"
  key=$(admin POST /api/v1/admin/tenants "{\"id\":\"$id\",\"name\":\"Game day $mode\",\"policy\":{\"algorithm\":\"TOKEN_BUCKET\",\"limit\":5,\"windowSeconds\":60,\"failMode\":\"$mode\"}}" | field apiKey)
  [ -n "$key" ] || { echo "could not create tenant $id (check SMOKE_ADMIN_TOKEN)"; exit 1; }
  eval "KEY_$mode=$key"
  eval "ID_$mode=$id"
done
echo "$(ts) tenants with limit 5/60 s: CLOSED, OPEN, LOCAL; health: $(curl -s "$B/health")"

burst "baseline (Redis up; 5 allowed, then 429)" "$N"; report
echo "$(ts) FAULT: docker pause redis (connections accepted, no answers)"
"${DOCKER[@]}" pause "$REDIS" >/dev/null
burst "Redis frozen" "$N"; report
health_under_load; report
echo "  recent store warnings:"
"${DC[@]}" logs --no-color --since 2m "app-$TRACK" 2>/dev/null | grep -iE 'store|redis|circuit' | tail -3 | cut -c1-240
echo "$(ts) RECOVERY: docker unpause redis"
"${DOCKER[@]}" unpause "$REDIS" >/dev/null
burst "Redis back, immediately" 2; report
echo "$(ts) waiting ${COOLDOWN_WAIT:-6} s"; sleep "${COOLDOWN_WAIT:-6}"
burst "Redis back, after ${COOLDOWN_WAIT:-6} s" 2; report
echo "  health: $(curl -s "$B/health")"
for mode in CLOSED OPEN LOCAL; do admin DELETE "/api/v1/admin/tenants/$(eval echo "\$ID_$mode")" >/dev/null; done
echo "$(ts) done. Write the numbers into docs/postmortem-<date>-redis-freeze.md"
