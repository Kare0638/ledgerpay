#!/usr/bin/env bash
# The reconciliation demo (design §12, #21): one merchant, one business day, a real break found and
# fixed through the normal path. Everything goes through the APIs and mock-psp's fault injection:
# no row is written by hand, so the break is one the system really produced.
#
#   demo/run.sh            run the whole story and check every number against demo/expected/
#   demo/run.sh --pause    wait for Enter before each step, for a recorded walkthrough
#
# It runs its own Compose project, ledgerpay-recon-demo, on fresh volumes each time, so the day's
# settlement report holds only this story. The name is fixed, never taken from the environment:
# the script deletes this project's volumes, and an inherited COMPOSE_PROJECT_NAME could name the
# main stack's. Stop the main stack first if it is up: both use the
# same ports. Needs Docker with Compose v2, curl 7.76+ and bash 4.4+.
#
#   P1  £100 captured, then R1 £30 refunded; two more refunds at once: one 409, one declined
#   P2  £200 captured at the PSP, but the webhook is lost (DROP_WEBHOOK)
#   P3  £50 captured, the outcome delivered 12 times (DUPLICATE_WEBHOOK)
#   P4  £40 authorisation declined (DECLINE)
cd "$(dirname "$0")/.."
export COMPOSE_PROJECT_NAME=ledgerpay-recon-demo
COMPOSE=(docker compose -f infra/docker-compose.yml)
[[ ${1:-} == --pause ]] && PAUSE=true
source scripts/lib.sh
MERCHANT=m_shop
EXPECTED=demo/expected
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

say() { printf '  %s\n' "$*"; }
ops() { http -H "Authorization: Bearer $OPS_KEY" "$@"; }

# A rule for this merchant's next operation of the type (design §9.4).
fault() {
  http -X POST "$PSP/_admin/faults" -H 'Content-Type: application/json' \
    -d "{\"merchant_id\":\"$MERCHANT\",\"type\":\"$1\",\"fault\":\"$2\",\"times\":1}" > /dev/null
  say "mock-psp will apply $2 to the next $1"
}

pay() {
  local ref="$1-$(random_hex)" body
  body=$(http -X POST "$API/v1/payments" -H "Authorization: Bearer $KEY" \
    -H "Idempotency-Key: $ref" -H 'Content-Type: application/json' \
    -d "{\"merchantReference\":\"$ref\",\"amountMinor\":$2,\"currency\":\"GBP\"}")
  field paymentId "$body"
}

capture() {
  http -X POST "$API/v1/payments/$1/capture" -H "Authorization: Bearer $KEY" \
    -H "Idempotency-Key: capture-$1" > /dev/null
}

# A refund that must be accepted: stops with the Problem otherwise. Prints the response.
refund_accepted() {
  local ref="refund-$(random_hex)"
  http -X POST "$API/v1/payments/$1/refunds" \
    -H "Authorization: Bearer $KEY" -H "Idempotency-Key: $ref" -H 'Content-Type: application/json' \
    -d "{\"merchantReference\":\"$ref\",\"amountMinor\":$2,\"reason\":\"$3\"}"
}

# Starts a refund; prints the HTTP status and the body, without failing on a 409.
refund() {
  local ref="refund-$(random_hex)"
  curl -sS -w '\n%{http_code}\n' -X POST "$API/v1/payments/$1/refunds" \
    -H "Authorization: Bearer $KEY" -H "Idempotency-Key: $ref" -H 'Content-Type: application/json' \
    -d "{\"merchantReference\":\"$ref\",\"amountMinor\":$2,\"reason\":\"$3\"}"
}

# The PSP reference of a payment's operation of this type (the latest, for refunds).
reference() {
  sql_value ledgerpay "SELECT psp_reference FROM psp_operations
                       WHERE payment_id = '$1' AND type = '$2' AND status = 'SUCCEEDED'
                       ORDER BY created_at DESC LIMIT 1"
}

journals() {
  sql_value ledgerpay "SELECT count(*) FROM journal_entries j
                       JOIN psp_operations o ON o.id = j.psp_operation_id WHERE o.payment_id = '$1'"
}

# Waits for the run to finish and prints its id. A request is assigned before it is parsed: a failure
# inside $(...) passed as an argument would not stop the script, only one assigned does.
run_report() {
  local body run status
  [[ -n $1 ]] || fail "no report to reconcile"
  body=$(ops -X POST "$API/ops/recon-runs" -H 'Content-Type: application/json' \
    -d "{\"reportId\":\"$1\"}")
  run=$(field runId "$body")
  [[ -n $run ]] || fail "no runId in $body"
  for _ in $(seq 1 30); do
    body=$(ops "$API/ops/recon-runs/$run")
    status=$(field status "$body")
    [[ $status != RUNNING ]] && break
    sleep 1
  done
  [[ $status == COMPLETED ]] || fail "recon run $run is $status"
  printf '%s' "$run"
}

upload() {
  local body
  body=$(ops -X POST "$API/ops/settlement-reports?businessDate=$DAY" \
    -H 'Content-Type: text/csv' --data-binary "@$1")
  [[ $(field status "$body") == VALID ]] || fail "report $1 should import as VALID: $body"
  printf '%s' "$(field reportId "$body")"
}

# Compares a run with an expected-result file in SQL, so no JSON tool is needed on the host. Every
# key of the file but "description" must equal the run's: items by label, the categories with any
# items, the report's and our totals and, when given, the field diffs of AMOUNT_MISMATCH items.
check_run() {
  local run=$1 file=$2 mismatches
  mismatches=$("${COMPOSE[@]}" exec -T postgres psql -U ledgerpay -d ledgerpay -At \
    -v ON_ERROR_STOP=1 -v run="$run" -v labels="$LABELS" -v expected="$(cat "$file")" <<'SQL'
WITH labels AS (
    SELECT key AS label, value #>> '{}' AS ref FROM jsonb_each(:'labels'::jsonb)),
run AS (
    SELECT summary FROM recon_runs WHERE id = :'run' AND status = 'COMPLETED'),
items AS (
    SELECT coalesce(l.label, 'unlabelled ' || coalesce(i.psp_reference, i.item_no::text)) AS label,
           i.category, i.diff
      FROM recon_items i LEFT JOIN labels l ON l.ref = i.psp_reference
     WHERE i.run_id = :'run'),
actual AS (
    SELECT jsonb_build_object(
        'items', coalesce((SELECT jsonb_object_agg(label, category) FROM items), '{}'),
        'categories', coalesce(
            (SELECT jsonb_object_agg(key, (value ->> 'items')::int)
               FROM run, jsonb_each(run.summary -> 'categories')
              WHERE (value ->> 'items')::int > 0), '{}'),
        'external', (SELECT summary -> 'external' FROM run),
        'internal', (SELECT summary -> 'internal' FROM run),
        'diffs', coalesce(
            (SELECT jsonb_object_agg(label, diff) FROM items
              WHERE category = 'AMOUNT_MISMATCH' AND diff IS NOT NULL), '{}')) AS doc)
SELECT key || ': expected ' || (:'expected'::jsonb -> key)::text
           || ', got ' || coalesce((actual.doc -> key)::text, 'nothing')
  FROM actual, jsonb_object_keys(:'expected'::jsonb) AS key
 WHERE key <> 'description' AND (actual.doc -> key) IS DISTINCT FROM (:'expected'::jsonb -> key);
SQL
  )
  [[ -z $mismatches ]] || fail "run $run does not match $file:"$'\n'"$mismatches"
  ok "matches $file"
}

show_run() {
  sql ledgerpay "SELECT i.item_no AS item, i.category,
                        coalesce(l.key, i.psp_reference) AS txn,
                        array_to_string(i.external_line_nos, ',') AS report_lines,
                        i.internal ->> 'status' AS ours, i.diff
                   FROM recon_items i
                   LEFT JOIN jsonb_each_text('$LABELS'::jsonb) l ON l.value = i.psp_reference
                  WHERE i.run_id = '$1' ORDER BY i.item_no"
}

if [[ -n $(docker ps --filter publish=8080 --format '{{.Label "com.docker.compose.project"}}' \
           | grep -vx "$COMPOSE_PROJECT_NAME" || true) ]]; then
  fail "port 8080 is taken by another stack; stop it first (docker compose -f infra/docker-compose.yml down)"
fi

step "A fresh stack of its own ($COMPOSE_PROJECT_NAME), so the day's report holds only this story"
"${COMPOSE[@]}" down -v --remove-orphans > /dev/null 2>&1 || true
start_stack
http -X DELETE "$PSP/_admin/faults" > /dev/null

step "Merchant $MERCHANT, 1 % fee"
KEY="mk_shop_$(random_hex)"
sql ledgerpay "INSERT INTO merchants (id, api_key_hash, fee_bps)
               VALUES ('$MERCHANT', encode(sha256(convert_to('$KEY', 'UTF8')), 'hex'), 100)" > /dev/null
say "API key $KEY"

step "P1: £100 authorised and captured, then R1: £30 refunded"
P1=$(pay p1 10000)
wait_for_status "$API/v1/payments/$P1" AUTHORIZED
capture "$P1"
wait_for_status "$API/v1/payments/$P1" CAPTURED
R1=$(refund_accepted "$P1" 3000 returned)
R1_ID=$(field refundId "$R1")
[[ -n $R1_ID ]] || fail "no refundId in $R1"
wait_for_status "$API/v1/refunds/$R1_ID" SUCCEEDED

step "Two more refunds of P1 at once, £50 and £40, with £70 left: the reservation lets only one in"
fault REFUND DECLINE   # whichever is accepted, the PSP declines it, so R1 stays the only refund
refund "$P1" 5000 "duplicate order" > "$WORK/refund-a" &
refund "$P1" 4000 "duplicate order" > "$WORK/refund-b" &
wait
CODES=$(tail -qn 1 "$WORK/refund-a" "$WORK/refund-b" | sort | tr '\n' ' ')
[[ $CODES == "202 409 " ]] || fail "expected one 202 and one 409, got $CODES"
say "responses: $CODES"
say "the 409: $(grep -h -o '"code":"[A-Z_]*"' "$WORK/refund-a" "$WORK/refund-b")"
ACCEPTED=$(grep -h -o '"refundId":"[^"]*"' "$WORK/refund-a" "$WORK/refund-b" | cut -d'"' -f4)
wait_for_status "$API/v1/refunds/$ACCEPTED" FAILED
P1_VIEW=$(http "$API/v1/payments/$P1" -H "Authorization: Bearer $KEY")
[[ $(field refundableMinor "$P1_VIEW") == 7000 && $(field refundReservedMinor "$P1_VIEW") == 0 ]] \
  || fail "the declined refund should release its reservation: $P1_VIEW"
ok "declined by the PSP, reservation released: refundable back to £70, refunded £30"

step "P2: £200 captured at the PSP, but the webhook never arrives"
P2=$(pay p2 20000)
wait_for_status "$API/v1/payments/$P2" AUTHORIZED
fault CAPTURE DROP_WEBHOOK
capture "$P2"
P2_AT_PSP=
for _ in $(seq 1 30); do
  P2_REF=$(sql_value ledgerpay "SELECT psp_reference FROM psp_operations
                                WHERE payment_id = '$P2' AND type = 'CAPTURE'")
  if [[ -n $P2_REF ]]; then
    P2_AT_PSP=$(sql_value mockpsp "SELECT status FROM operations WHERE psp_reference = '$P2_REF'")
    [[ $P2_AT_PSP == SUCCEEDED ]] && break
  fi
  sleep 1
done
# Without this, "pending here" below would pass just as well if the PSP had done nothing.
[[ $P2_AT_PSP == SUCCEEDED ]] || fail "mock-psp should have P2's capture SUCCEEDED after 30 s, not '$P2_AT_PSP'"
sleep 2
P2_VIEW=$(http "$API/v1/payments/$P2" -H "Authorization: Bearer $KEY")
[[ $(field status "$P2_VIEW") == CAPTURE_PENDING ]] || fail "P2 should still be CAPTURE_PENDING: $P2_VIEW"
P2_JOURNALS=$(journals "$P2")
[[ $P2_JOURNALS == 0 ]] || fail "P2 should have no journal yet, has $P2_JOURNALS"
ok "mock-psp: SUCCEEDED. Ours: CAPTURE_PENDING, no journal"

step "P3: £50 captured, the outcome sent 12 times"
P3=$(pay p3 5000)
wait_for_status "$API/v1/payments/$P3" AUTHORIZED
fault CAPTURE DUPLICATE_WEBHOOK
capture "$P3"
wait_for_status "$API/v1/payments/$P3" CAPTURED
P3_REF=$(reference "$P3" CAPTURE)
[[ -n $P3_REF ]] || fail "P3's capture has no PSP reference"
for _ in $(seq 1 30); do
  SENT=$(sql_value mockpsp "SELECT coalesce(sum(copies), 0) FROM webhook_events
                            WHERE psp_reference = '$P3_REF' AND delivered_at IS NOT NULL")
  (( SENT >= 12 )) && break
  sleep 1
done
(( SENT >= 12 )) || fail "mock-psp should have delivered P3's outcome 12 times after 30 s, not $SENT"
EVENTS=$(sql_value ledgerpay "SELECT count(*) FROM inbox_events
                              WHERE payload ->> 'psp_reference' = '$P3_REF'")
# AT-07: one event ten times and the same outcome under two more IDs are three inbox rows.
[[ $EVENTS == 3 ]] || fail "the inbox should hold 3 distinct event IDs for P3, not $EVENTS"
P3_JOURNALS=$(journals "$P3")
[[ $P3_JOURNALS == 1 ]] || fail "P3 should have one journal, has $P3_JOURNALS"
ok "$SENT deliveries, $EVENTS distinct event IDs in the inbox, 1 journal"

step "P4: £40, the authorisation declined"
fault AUTHORIZE DECLINE
P4=$(pay p4 4000)
wait_for_status "$API/v1/payments/$P4" DECLINED
P4_JOURNALS=$(journals "$P4")
[[ $P4_JOURNALS == 0 ]] || fail "P4 should have no journal, has $P4_JOURNALS"
ok "DECLINED, no journal; it moved no money, so it is not in the report"

P1_REF=$(reference "$P1" CAPTURE)
R1_REF=$(reference "$P1" REFUND)
[[ -n $P1_REF && -n $R1_REF ]] || fail "P1's capture and R1 should both have succeeded"
LABELS=$(printf '{"P1":"%s","R1":"%s","P2":"%s","P3":"%s","UNKNOWN":"psp_demo_unknown"}' \
  "$P1_REF" "$R1_REF" "$P2_REF" "$P3_REF")
DAY=$(sql_value mockpsp "SELECT DISTINCT to_char(succeeded_at AT TIME ZONE 'UTC', 'YYYY-MM-DD')
                         FROM operations WHERE type IN ('CAPTURE', 'REFUND') AND status = 'SUCCEEDED'")
[[ $DAY =~ ^[0-9-]{10}$ ]] || fail "the story ran across midnight UTC ($DAY); run it again"

step "The PSP's settlement report for $DAY, imported"
http -o "$WORK/report.csv" "$PSP/reports/settlement?date=$DAY"
sed 's/^/  /' "$WORK/report.csv"
REPORT=$(upload "$WORK/report.csv")
ok "VALID, version 1"

step "Reconciliation, run 1: P2 is a STATUS_MISMATCH"
RUN1=$(run_report "$REPORT")
show_run "$RUN1"
check_run "$RUN1" "$EXPECTED/before-fix.json"
say "the report nets to 32000; our books to 12000 while P2 is pending. Nothing is booked from the report"

step "The fix goes through the normal path: an inquiry about P2's capture"
P2_OP=$(sql_value ledgerpay "SELECT id FROM psp_operations WHERE payment_id = '$P2' AND type = 'CAPTURE'")
ops -X POST "$API/ops/psp-operations/$P2_OP/inquiry" > /dev/null
wait_for_status "$API/v1/payments/$P2" CAPTURED
[[ $(journals "$P2") == 1 ]] || fail "P2 should be booked once"
ok "booked once by the money transaction"

step "Reconciliation, run 2 of the same report: everything matches, run 1 is kept as it was"
RUN2=$(run_report "$REPORT")
show_run "$RUN2"
check_run "$RUN2" "$EXPECTED/after-fix.json"
check_run "$RUN1" "$EXPECTED/before-fix.json"

step "The books: receivable 32000 = merchant payable 31650 + fee revenue 350"
sql ledgerpay "SELECT account_id, sum(amount_minor) AS balance FROM postings
               GROUP BY account_id ORDER BY account_id"
BALANCES=$(sql_value ledgerpay "SELECT string_agg(account_id || '=' || total, ' ' ORDER BY account_id)
                                FROM (SELECT account_id, sum(amount_minor) AS total
                                        FROM postings GROUP BY account_id) b")
[[ $BALANCES == "fee_revenue=-350 merchant_payable:$MERCHANT=-31650 psp_receivable:mock-psp=32000" ]] \
  || fail "unexpected balances: $BALANCES"
ok "debit positive: receivable 32000, payable −31650, revenue −350"
INTEGRITY=$(sql_value ledgerpay "SELECT status FROM ledger_integrity_checks WHERE recon_run_id = '$RUN2'")
[[ $INTEGRITY == PASSED ]] || fail "the ledger integrity check should pass"
ok "ledger integrity check with run 2: PASSED"

step "Four single-fault variants of the report, each reconciled against the fixed books"
LINE() { grep "^$1," "$WORK/report.csv"; }
P1_LINE=$(LINE "$P1_REF")
P3_LINE=$(LINE "$P3_REF")
{ cat "$WORK/report.csv"; printf '%s\n' "$P1_LINE"; } > "$WORK/duplicate-p1.csv"
grep -v "^$P3_REF," "$WORK/report.csv" > "$WORK/missing-p3.csv"
sed "s/^\($P3_REF,.*\),5000,GBP,/\1,5100,GBP,/" "$WORK/report.csv" > "$WORK/amount-p3.csv"
{ cat "$WORK/report.csv"
  printf 'psp_demo_unknown,req_demo_unknown,CAPTURE,%s,1500,GBP,%sT12:00:00Z\r\n' "$MERCHANT" "$DAY"
} > "$WORK/unknown-transaction.csv"
for variant in duplicate-p1 missing-p3 amount-p3 unknown-transaction; do
  file="$EXPECTED/variants/$variant.json"
  say "$variant: $(sed -n 's/.*"description": "\([^"]*\)".*/\1/p' "$file")"
  variant_report=$(upload "$WORK/$variant.csv")
  variant_run=$(run_report "$variant_report")
  check_run "$variant_run" "$file"
done

step "Done"
cat <<EOF
Every number matched demo/expected/. The stack is still up; to look around:

  docker compose -p $COMPOSE_PROJECT_NAME -f infra/docker-compose.yml exec postgres psql -U ledgerpay
  curl -s "$API/ops/recon-runs?reportId=$REPORT" -H "Authorization: Bearer $OPS_KEY"

Stop it with: docker compose -p $COMPOSE_PROJECT_NAME -f infra/docker-compose.yml down -v
EOF
