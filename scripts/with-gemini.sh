#!/usr/bin/env bash
# Runs one command with GEMINI_API_KEY set, and only that command:
#
#   scripts/with-gemini.sh ./mvnw -pl payment-service test -Dtest=…
#   scripts/with-gemini.sh docker compose -f infra/docker-compose.yml up -d
#
# The key stays in a file outside the repository (SECURITY.md), by default
# ~/.config/ledgerpay/gemini.env, holding one line: export GEMINI_API_KEY=…  Loading it here instead
# of in ~/.bashrc keeps it out of every other process started from a shell: build plugins, package
# install scripts and editor extensions all inherit the environment and could read it.
#
# The file must be yours and readable by you alone (chmod 600), and must not be inside the
# repository. The key is never printed.
set -euo pipefail

file=${LEDGERPAY_GEMINI_ENV:-$HOME/.config/ledgerpay/gemini.env}
repo=$(cd "$(dirname "$0")/.." && pwd)

fail() { echo "with-gemini: $*" >&2; exit 1; }

(( $# > 0 )) || fail "usage: scripts/with-gemini.sh <command> [args…]"
[[ -f $file ]] || fail "no key file at $file (see SECURITY.md)"
real=$(realpath "$file")
[[ $real != "$repo"/* ]] || fail "$file is inside the repository; keep the key outside it"
[[ $(stat -c '%u' "$real") == "$(id -u)" ]] || fail "$file is not owned by you"
[[ $(stat -c '%a' "$real") =~ ^[0-7]00$ ]] || fail "$file can be read by others; chmod 600 $file"

# A subshell reads the file, so nothing else it may define reaches the command; only the key does.
GEMINI_API_KEY=$(
  # shellcheck disable=SC1090
  . "$real"
  printf '%s' "${GEMINI_API_KEY:-}"
)
[[ -n $GEMINI_API_KEY ]] || fail "$file does not set GEMINI_API_KEY"
export GEMINI_API_KEY
exec "$@"
