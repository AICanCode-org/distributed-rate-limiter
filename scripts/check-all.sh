#!/usr/bin/env bash
# Run the same checks CI runs, for every track (or TRACKS="python" for one).
#   scripts/check-all.sh                 lint + unit tests
#   scripts/check-all.sh --integration   + integration tests and coverage gate
#                                        (needs: docker compose up -d redis; uses database 15 of TEST_REDIS_URL)
# Optional speed-ups for maintainers (used by scripts/verify-tags.sh):
#   PY_VENV=/path/to/venv          reuse an existing virtualenv for the Python track
#   NODE_MODULES=/path/node_modules reuse an installed node_modules for the TypeScript track
#   MVN_FLAGS="-o"                  pass extra flags to Maven (e.g. offline)
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INTEGRATION=0
[ "${1:-}" = "--integration" ] && INTEGRATION=1
TRACKS="${TRACKS:-java python typescript}"
RESULTS=()
FAILED=0

step() { # step <label> <command...>
  local label="$1"; shift
  echo; echo "──── $label"
  if "$@"; then RESULTS+=("PASS  $label"); else RESULTS+=("FAIL  $label"); FAILED=$((FAILED + 1)); fi
}

for track in $TRACKS; do
  dir="$ROOT/tracks/$track"
  [ -d "$dir" ] || { RESULTS+=("SKIP  $track (no tracks/$track)"); continue; }
  cd "$dir" || exit 1
  case "$track" in
    java)
      MVN="mvn -B -q ${MVN_FLAGS:-}"
      step "java: lint (checkstyle)" $MVN checkstyle:check
      step "java: unit tests" $MVN test
      [ "$INTEGRATION" = 1 ] && step "java: unit + integration + coverage gate" $MVN verify -Pcoverage
      ;;
    python)
      if [ ! -e .venv ]; then
        if [ -n "${PY_VENV:-}" ]; then ln -s "$PY_VENV" .venv; else make -s install; fi
      fi
      step "python: lint (ruff)" make -s lint
      step "python: unit tests" make -s test
      [ "$INTEGRATION" = 1 ] && step "python: unit + integration + coverage gate" make -s coverage
      ;;
    typescript)
      if [ ! -e node_modules ]; then
        if [ -n "${NODE_MODULES:-}" ]; then ln -s "$NODE_MODULES" node_modules; else make -s install; fi
      fi
      step "typescript: lint (eslint, prettier, tsc)" make -s lint
      step "typescript: unit tests" make -s test
      [ "$INTEGRATION" = 1 ] && step "typescript: unit + integration + coverage gate" make -s coverage
      ;;
  esac
done

echo; echo "════ Summary"
printf '%s\n' "${RESULTS[@]}"
[ "$FAILED" -eq 0 ]
