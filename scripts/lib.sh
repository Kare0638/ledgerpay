# Shared by scripts/demo.sh and demo/run.sh: sourced, not run. Needs Docker with Compose v2,
# curl 7.76+ and bash 4.4+ (macOS ships 3.2: brew install bash). The caller sets COMPOSE (the
# docker compose command as an array) and, before calling wait_for_status, KEY.
set -euo pipefail
if (( BASH_VERSINFO[0] * 100 + BASH_VERSINFO[1] < 404 )); then
  echo "$0 needs bash 4.4 or later, not $BASH_VERSION (macOS: brew install bash)" >&2
  exit 1
fi
# A failure inside $(...) stops the script too: without this, $(balance) would return an empty
# string after a failed request, and the checks would go on with it.
shopt -s inherit_errexit

API=http://localhost:8080
PSP=http://localhost:8081
NOTIFY=http://localhost:8082
OPS_KEY=dev-only-ops-key   # matches both OPS_API_KEY_HASH in infra/docker-compose.yml
PAUSE=${PAUSE:-false}      # true: wait for Enter before each step, for a recorded walkthrough
# With a Gemini key in the environment (scripts/with-gemini.sh), the stack also runs the
# reconciliation assistant, the key passed as a Compose secret (infra/docker-compose.assistant.yml).
if [[ -n ${GEMINI_API_KEY:-} ]]; then
  COMPOSE+=(-f infra/docker-compose.assistant.yml)
fi

step() {
  if [[ $PAUSE == true ]]; then
    read -r -p $'\n[Enter] for the next step ' < /dev/tty
  fi
  printf '\n\033[1m== %s\033[0m\n' "$*"
}
fail() { printf '\033[31mFAILED: %s\033[0m\n' "$*" >&2; exit 1; }
ok() { printf '  \033[32m✓\033[0m %s\n' "$*"; }
# Reads the first string or number field with this name from a JSON object, so the scripts need no
# jq. Empty when there is none; callers check.
field() {
  { grep -o "\"$1\":\"\{0,1\}[^,\"}]*" <<< "$2" || true; } | head -n 1 | sed 's/^"[^"]*":"\{0,1\}//'
}
# 32 random hex digits, for API keys and order numbers.
random_hex() { head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n'; }
# curl that, on an HTTP error, stops with the response body (a Problem) rather than a bare code.
http() {
  local out
  out=$(curl -sS --fail-with-body "$@") || fail "$* -> $out"
  printf '%s' "$out"
}
sql() { "${COMPOSE[@]}" exec -T postgres psql -U ledgerpay -d "$1" -v ON_ERROR_STOP=1 -c "$2"; }
sql_value() { "${COMPOSE[@]}" exec -T postgres psql -U ledgerpay -d "$1" -At -v ON_ERROR_STOP=1 -c "$2"; }

# Polls a GET until its status field is the expected one, as a client waiting for a webhook would.
# A different final status (a fault rule left from an earlier run, say) fails at once.
wait_for_status() {
  local url=$1 expected=$2 body status
  for _ in $(seq 1 60); do
    body=$(http "$url" -H "Authorization: Bearer $KEY")
    status=$(field status "$body")
    [[ $status == "$expected" ]] && { echo "  $status"; return; }
    case $status in
      DECLINED | VOIDED | FAILED)
        fail "$url is $status, expected $expected. Clear mock-psp's fault rules with: curl -X DELETE localhost:8081/_admin/faults" ;;
    esac
    sleep 1
  done
  fail "$url is $status after 60 s, expected $expected"
}

start_stack() {
  step "Building and starting the stack (the first build downloads Maven dependencies: a few minutes)"
  "${COMPOSE[@]}" up --build -d --wait || {
    "${COMPOSE[@]}" ps
    fail "the stack did not become healthy. A volume from an older checkout can fail migrations: rerun with --fresh"
  }
}
