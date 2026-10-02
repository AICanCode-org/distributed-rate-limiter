#!/usr/bin/env bash
# Maintainer tool: check out every phase tag in a temporary git worktree and run scripts/check-all.sh.
# Tags from phase-4-end on also run integration tests + coverage (Redis must be up:
# docker compose up -d redis). The integration tests use database 15 of TEST_REDIS_URL
# (default redis://localhost:6379/15) and empty it themselves; it is also flushed before each tag.
#   scripts/verify-tags.sh                    all phase-*/v* tags
#   scripts/verify-tags.sh phase-3-end v1.0.0 only these
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
TAGS=("$@")
[ ${#TAGS[@]} -eq 0 ] && mapfile -t TAGS < <(git tag -l 'phase-*' 'v*' --sort=creatordate)
WORK="$(mktemp -d)"
SUMMARY=()
REDIS_CLI="${REDIS_CLI:-docker compose exec -T redis redis-cli}"

needs_integration() { case "$1" in phase-[0-3]-end) return 1 ;; *) return 0 ;; esac; }

for tag in "${TAGS[@]}"; do
  echo; echo "████ $tag ($(git rev-parse --short "$tag^{commit}"))"
  wt="$WORK/$tag"
  git worktree add --quiet --detach "$wt" "$tag" || { SUMMARY+=("FAIL  $tag (worktree)"); continue; }
  args=()
  if needs_integration "$tag"; then
    args=(--integration)
    $REDIS_CLI -n 15 FLUSHDB >/dev/null || echo "warning: could not flush Redis database 15"
  fi
  if (cd "$wt" && bash scripts/check-all.sh "${args[@]}") > "$WORK/$tag.log" 2>&1; then
    SUMMARY+=("PASS  $tag ${args[*]:-(lint + unit)}")
  else
    SUMMARY+=("FAIL  $tag ${args[*]:-(lint + unit)}  → see $WORK/$tag.log")
  fi
  sed -n '/════ Summary/,$p' "$WORK/$tag.log"
  git worktree remove --force "$wt"
done

echo; echo "████ verify-tags summary"
printf '%s\n' "${SUMMARY[@]}"
! printf '%s\n' "${SUMMARY[@]}" | grep -q '^FAIL'
