#!/usr/bin/env bash
# RateGuard language-agnostic smoke test. Works against ANY track (Java, Python, TypeScript).
# Usage:  scripts/smoke-test.sh [BASE_URL]        (default http://localhost:8080)
# Needs only bash, curl and xargs (Linux, macOS, WSL, Git Bash). Creates its own tenants with unique
# ids, so it is safe to run repeatedly, and deletes them at the end.
# Env:
#   SMOKE_ADMIN_TOKEN       ADMIN_TOKEN of the app (default: the local Compose value)
#   EXPECT_FALLBACK=true    the app runs with AI_PROVIDER=llm and an unreachable LLM: expect fallbacks
set -uo pipefail

BASE_URL="${1:-${BASE_URL:-http://localhost:8080}}"
BASE_URL="${BASE_URL%/}"
ADMIN_TOKEN="${SMOKE_ADMIN_TOKEN:-local-admin-token-change-me-0123456789abcdef}"
PASS=0
FAIL=0
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

ok()  { PASS=$((PASS + 1)); printf '  \033[32mPASS\033[0m %s\n' "$1"; }
bad() { FAIL=$((FAIL + 1)); printf '  \033[31mFAIL\033[0m %s\n' "$1"; [ -s "$TMP" ] && printf '       body: %s\n' "$(head -c 300 "$TMP")"; }

# request METHOD PATH [JSON_BODY] [AUTH: admin|<api key>|""] -> sets STATUS, HEADERS; body in $TMP
request() {
  local method="$1" path="$2" body="${3:-}" auth="${4:-}"
  local args=(-s -o "$TMP" -D - -X "$method" "$BASE_URL$path")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' --data "$body")
  case "$auth" in
    "") ;;
    admin) args+=(-H "Authorization: Bearer $ADMIN_TOKEN") ;;
    *) args+=(-H "X-API-Key: $auth") ;;
  esac
  HEADERS="$(curl "${args[@]}" | tr -d '\r')"
  STATUS="$(printf '%s\n' "$HEADERS" | awk 'NR==1{print $2}')"
}
header() { printf '%s\n' "$HEADERS" | awk -v h="$(echo "$1" | tr 'A-Z' 'a-z')" -F': ' 'tolower($1)==h{print $2}' | tail -1; }
# First occurrence of a key in the (single-line) JSON body.
json_str()  { grep -o "\"$1\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" "$TMP" | head -1 | sed -E 's/.*:[[:space:]]*"([^"]*)"$/\1/'; }
json_num()  { grep -oE "\"$1\"[[:space:]]*:[[:space:]]*[0-9]+" "$TMP" | head -1 | sed -E 's/.*:[[:space:]]*//'; }
json_bool() { grep -oE "\"$1\"[[:space:]]*:[[:space:]]*(true|false)" "$TMP" | head -1 | sed -E 's/.*:[[:space:]]*//'; }
expect() { if [ "$STATUS" = "$1" ]; then ok "$2 ($STATUS)"; else bad "$2 (expected $1, got ${STATUS:-no response})"; fi; }
is_problem() { case "$(header Content-Type)" in application/problem+json*) ok "$1 uses application/problem+json" ;; *) bad "$1 uses application/problem+json" ;; esac; }
check() { if [ "$1" = "$2" ]; then ok "$3"; else bad "$3 (expected '$2', got '$1')"; fi; }
policy() { printf '{"algorithm":"%s","limit":%s,"windowSeconds":%s}' "$1" "$2" "$3"; }

RUN="$(date +%s)$RANDOM"
A="smoke-a-$RUN"
B="smoke-b-$RUN"
C="smoke-c-$RUN"
echo "RateGuard smoke test against $BASE_URL (run $RUN)"

# ---- ops ----
request GET /health
expect 200 "GET /health"
grep -q '"redis":[[:space:]]*"UP"' "$TMP" && ok "redis is UP" || bad "redis is UP"
request GET /api/v1/nope
expect 404 "unknown route"
is_problem "404"

# ---- admin API (FR-1..FR-6) ----
request GET /api/v1/admin/tenants
expect 401 "admin API without a token"
check "$(header WWW-Authenticate)" "Bearer" "401 carries WWW-Authenticate: Bearer"
request POST /api/v1/admin/tenants "{\"id\":\"$A\",\"name\":\"Smoke A\",\"policy\":$(policy TOKEN_BUCKET 3 60)}" admin
expect 201 "POST /admin/tenants (token bucket 3 per 60 s)"
KEY_A="$(json_str apiKey)"
[[ "$KEY_A" =~ ^rg_[0-9a-f]{32}$ ]] && ok "the API key looks like rg_<32 hex>" || bad "the API key looks like rg_<32 hex>"
check "$(header Location)" "/api/v1/admin/tenants/$A" "Location header"
request POST /api/v1/admin/tenants "{\"id\":\"$A\",\"name\":\"Again\",\"policy\":$(policy TOKEN_BUCKET 1 1)}" admin
expect 409 "duplicate tenant id"
request POST /api/v1/admin/tenants "{\"id\":\"$B\",\"name\":\"Bad\",\"policy\":$(policy TOKEN_BUCKET 0 60)}" admin
expect 400 "limit 0 is rejected"
is_problem "validation error"
request POST /api/v1/admin/tenants "{\"id\":\"Bad:Id\",\"name\":\"Bad\",\"policy\":$(policy TOKEN_BUCKET 1 60)}" admin
expect 400 "an id that could break a Redis key is rejected"
request GET "/api/v1/admin/tenants/$A" "" admin
expect 200 "GET /admin/tenants/{id}"
grep -q '"apiKey"' "$TMP" && bad "the key is never shown again" || ok "the key is never shown again"
request GET /api/v1/admin/tenants "" admin
expect 200 "GET /admin/tenants"
grep -q "\"$A\"" "$TMP" && ok "the list contains the new tenant" || bad "the list contains the new tenant"

# ---- the limiter (FR-7..FR-12) ----
request POST /api/v1/limits/check '{"resource":"smoke","cost":1}' "$KEY_A"
expect 200 "POST /limits/check"
check "$(json_bool allowed)" true "first check is allowed"
check "$(json_num remaining)" 2 "remaining = 2"
check "$(header X-RateLimit-Limit)" 3 "X-RateLimit-Limit"
check "$(header X-RateLimit-Remaining)" 2 "X-RateLimit-Remaining"
request POST /api/v1/limits/check '{"cost":4}' "$KEY_A"
expect 422 "a cost above the limit"
request POST /api/v1/limits/check "" "rg_00000000000000000000000000000000"
expect 401 "an unknown API key"
for _ in 1 2 3; do request GET /api/v1/demo/hello "" "$KEY_A"; done
expect 200 "GET /demo/hello three times"
request GET /api/v1/demo/hello "" "$KEY_A"
expect 429 "the fourth hello is rate limited"
is_problem "429"
[ "$(header Retry-After)" -ge 1 ] 2>/dev/null && ok "Retry-After = $(header Retry-After) s" || bad "Retry-After >= 1"
check "$(header X-RateLimit-Remaining)" 0 "X-RateLimit-Remaining = 0 on a 429"

request POST /api/v1/admin/tenants "{\"id\":\"$B\",\"name\":\"Smoke B\",\"policy\":$(policy SLIDING_WINDOW 2 60)}" admin
expect 201 "POST /admin/tenants (sliding window 2 per 60 s)"
KEY_B="$(json_str apiKey)"
ANSWERS=""
for _ in 1 2 3; do request POST /api/v1/limits/check "" "$KEY_B"; ANSWERS="$ANSWERS$(json_bool allowed) "; done
check "$ANSWERS" "true true false " "sliding window: allowed, allowed, denied"
expect 200 "a denied check is still a 200 answer"
[ "$(json_num retryAfterMs)" -gt 0 ] 2>/dev/null && ok "retryAfterMs = $(json_num retryAfterMs)" || bad "retryAfterMs > 0"

# ---- concurrency: 20 parallel requests against a limit of 5 ----
request POST /api/v1/admin/tenants "{\"id\":\"$C\",\"name\":\"Smoke C\",\"policy\":$(policy TOKEN_BUCKET 5 3600)}" admin
expect 201 "POST /admin/tenants (5 per hour)"
KEY_C="$(json_str apiKey)"
OKS="$(seq 20 | xargs -P 20 -I{} curl -s -o /dev/null -w '%{http_code}\n' -H "X-API-Key: $KEY_C" "$BASE_URL/api/v1/demo/hello" | grep -c '^200$')"
check "$OKS" 5 "20 parallel requests: exactly 5 allowed (atomic Lua)"

# ---- keys, policies, usage, advice ----
request POST "/api/v1/admin/tenants/$A/reset" "" admin
expect 204 "POST /admin/tenants/{id}/reset"
request GET /api/v1/demo/hello "" "$KEY_A"
expect 200 "after a reset the bucket is full again"
request POST "/api/v1/admin/tenants/$A/rotate-key" "" admin
expect 200 "POST /admin/tenants/{id}/rotate-key"
NEW_KEY_A="$(json_str apiKey)"
request GET /api/v1/demo/hello "" "$KEY_A"
expect 401 "the old key stops working"
request GET /api/v1/demo/hello "" "$NEW_KEY_A"
expect 200 "the new key works"
request PUT "/api/v1/admin/tenants/$A/policy" "$(policy SLIDING_WINDOW 10 60)" admin
expect 200 "PUT /admin/tenants/{id}/policy"
check "$(json_num limit)" 10 "the new limit is 10"
request GET "/api/v1/admin/tenants/$C/usage?minutes=5" "" admin
expect 200 "GET /admin/tenants/{id}/usage"
check "$(json_num allowed)" 5 "usage counted 5 allowed requests"
check "$(json_num denied)" 15 "usage counted 15 denied requests"
request GET "/api/v1/admin/tenants/$C/usage?minutes=500" "" admin
expect 400 "minutes above 120"
request POST "/api/v1/admin/tenants/$C/advice?minutes=5" "" admin
expect 200 "POST /admin/tenants/{id}/advice"
if [ "${EXPECT_FALLBACK:-false}" = true ]; then
  check "$(json_str provider)/$(json_bool fallback)" "rules/true" "advice falls back to the rules (LLM unreachable)"
else
  check "$(json_str provider)/$(json_bool fallback)" "rules/false" "advice comes from the offline rules"
fi
check "$(json_str action)" RAISE_LIMIT "75 % denied -> RAISE_LIMIT"
[ -n "$(json_str summary)" ] && ok "advice has a summary" || bad "advice has a summary"
request GET "/api/v1/admin/tenants/$C" "" admin
check "$(json_num limit)" 5 "advice is never applied automatically"

# ---- v1.1 resilience + observability (Phase 7) ----
HEADERS="$(curl -s -o /dev/null -D - -H 'X-Request-ID: smoke-req-1' "$BASE_URL/health" | tr -d '\r')"
[ "$(header X-Request-ID)" = "smoke-req-1" ] && ok "X-Request-ID is echoed (v1.1)" || bad "X-Request-ID is echoed (v1.1)"
HEADERS="$(curl -s -o /dev/null -D - -H 'X-Request-ID: bad id with spaces' "$BASE_URL/health" | tr -d '\r')"
RID="$(header X-Request-ID)"
[ -n "$RID" ] && [ "$RID" != "bad id with spaces" ] && ok "an unsafe X-Request-ID is replaced (v1.1)" || bad "an unsafe X-Request-ID is replaced (v1.1)"
request GET "/api/v1/admin/tenants/$B" "" admin
check "$(json_str failMode)" CLOSED "a policy without failMode is CLOSED (v1.1)"
request PUT "/api/v1/admin/tenants/$B/policy" '{"algorithm":"SLIDING_WINDOW","limit":2,"windowSeconds":60,"failMode":"LOCAL"}' admin
expect 200 "PUT policy with failMode LOCAL (v1.1)"
check "$(json_str failMode)" LOCAL "failMode is stored (v1.1)"
request PUT "/api/v1/admin/tenants/$B/policy" '{"algorithm":"SLIDING_WINDOW","limit":2,"windowSeconds":60,"failMode":"MAYBE"}' admin
expect 400 "an unknown failMode is rejected (v1.1)"
request POST /api/v1/limits/check '{"resource":"smoke-v11"}' "$NEW_KEY_A"
check "$(json_bool degraded)" false "a normal answer is not degraded (v1.1)"
[ -z "$(header X-RateLimit-Degraded)" ] && ok "no X-RateLimit-Degraded header while Redis is up (v1.1)" || bad "no X-RateLimit-Degraded header while Redis is up (v1.1)"
request GET /metrics
expect 200 "GET /metrics (v1.1)"
ALLOWED="$(sed -n 's/^rateguard_decisions_allowed_total \([0-9][0-9]*\)$/\1/p' "$TMP")"
[ "${ALLOWED:-0}" -ge 1 ] 2>/dev/null && ok "rateguard_decisions_allowed_total=$ALLOWED" || bad "rateguard_decisions_allowed_total >= 1"
CREATED="$(sed -n 's/^rateguard_tenants_created_total \([0-9][0-9]*\)$/\1/p' "$TMP")"
[ "${CREATED:-0}" -ge 3 ] 2>/dev/null && ok "rateguard_tenants_created_total=$CREATED" || bad "rateguard_tenants_created_total >= 3"
# ---- end v1.1 ----

# ---- clean up ----
for t in "$A" "$B" "$C"; do request DELETE "/api/v1/admin/tenants/$t" "" admin; done
expect 204 "DELETE /admin/tenants/{id}"
request GET /api/v1/demo/hello "" "$KEY_B"
expect 401 "a deleted tenant's key is revoked"

echo
echo "Result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
