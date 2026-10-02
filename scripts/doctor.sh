#!/usr/bin/env bash
# Phase 0 environment check. Prints what is installed and what is missing for each track.
# Usage: scripts/doctor.sh            (Linux, macOS, WSL2, Git Bash)
set -u
ok()   { printf '  \033[32m✔\033[0m %-28s %s\n' "$1" "$2"; }
miss() { printf '  \033[31m✘\033[0m %-28s %s\n' "$1" "$2"; MISSING=$((MISSING + 1)); }
warn() { printf '  \033[33m!\033[0m %-28s %s\n' "$1" "$2"; }
MISSING=0
ver() { "$@" 2>&1 | head -1; }

echo "Common (every track)"
command -v git >/dev/null && ok git "$(ver git --version)" || miss git "https://git-scm.com/downloads"
if command -v docker >/dev/null; then
  ok docker "$(ver docker --version)"
  docker compose version >/dev/null 2>&1 && ok "docker compose" "$(ver docker compose version)" || miss "docker compose" "install Docker Desktop or the compose plugin"
  docker info >/dev/null 2>&1 && ok "docker daemon" "running" || warn "docker daemon" "not reachable: start Docker Desktop (or: sudo usermod -aG docker \$USER, then log out/in)"
else
  miss docker "https://docs.docker.com/get-docker/"
fi
command -v make >/dev/null && ok make "$(ver make --version)" || warn make "optional; Ubuntu/WSL: sudo apt install make · macOS: xcode-select --install"
command -v curl >/dev/null && ok curl "$(ver curl --version | cut -c1-40)" || miss curl "needed by scripts/smoke-test.sh"

echo; echo "Java track"
command -v java >/dev/null && ok java "$(ver java -version)" || warn java "JDK 21: https://adoptium.net (or: sdk install java 21-tem)"
command -v mvn >/dev/null && ok maven "$(ver mvn -v)" || warn maven "Maven 3.9+: https://maven.apache.org/install.html (or: sdk install maven)"

echo; echo "Python track"
PY=$(command -v python3 || command -v python || true)
[ -n "$PY" ] && ok python "$(ver "$PY" --version)" || warn python "Python 3.11+: https://www.python.org/downloads/"
[ -n "$PY" ] && { "$PY" -m venv --help >/dev/null 2>&1 && ok "python venv" "available" || warn "python venv" "Ubuntu/WSL: sudo apt install python3-venv"; }

echo; echo "TypeScript track"
command -v node >/dev/null && ok node "$(ver node --version)" || warn node "Node 20+ (22 LTS recommended): https://nodejs.org or nvm"
command -v npm >/dev/null && ok npm "$(ver npm --version)" || warn npm "comes with Node"

echo
case "$(uname -s 2>/dev/null)" in
  Linux*) grep -qi microsoft /proc/version 2>/dev/null && echo "Detected WSL — keep the repo inside the Linux filesystem (~/code), not /mnt/c (much faster)." ;;
  MINGW*|MSYS*) echo "Detected Git Bash on Windows — WSL2 is recommended (see README › Windows)." ;;
esac
[ "$MISSING" -eq 0 ] && echo "All required tools found. You need the tools for ONE track only." || { echo "$MISSING required tool(s) missing."; exit 1; }
