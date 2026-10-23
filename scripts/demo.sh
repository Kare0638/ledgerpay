#!/usr/bin/env bash
# The happy path end to end on the Docker Compose stack, in one command (#16):
#
#   scripts/demo.sh            build and start the stack, then run the demo against it
#   scripts/demo.sh --fresh    first delete the stack's volumes (an older database included)
#
# It seeds merchant m_demo with a new API key and registers its webhook endpoint, then pays £100,
# captures it, refunds £30 and checks each money effect: the ledger, the balance, the Kafka event,
# the signed webhook the merchant received, mock-psp's settlement report for the day, imported and
# reconciled against the books, and the ledger's integrity check.
# Needs Docker with Compose v2, curl 7.76+ and bash 4.4+ (macOS ships 3.2: brew install bash).
cd "$(dirname "$0")/.."
COMPOSE=(docker compose -f infra/docker-compose.yml)
MERCHANT=m_demo
source scripts/lib.sh

if [[ ${1:-} == --fresh ]]; then
  step "Deleting the stack and its volumes"
  "${COMPOSE[@]}" down -v --remove-orphans
fi

start_stack

step "Seeding merchant $MERCHANT with a new API key (only its SHA-256 is stored)"
KEY="mk_demo_$(random_hex)"
# Hashed by PostgreSQL, so the host needs no sha256sum. The fee is reset too: the balance check
# below assumes the default 1 %.
sql ledgerpay "INSERT INTO merchants (id, api_key_hash, fee_bps)
               VALUES ('$MERCHANT', encode(sha256(convert_to('$KEY', 'UTF8')), 'hex'), 100)
               ON CONFLICT (id) DO UPDATE SET api_key_hash = EXCLUDED.api_key_hash,
                                              fee_bps = EXCLUDED.fee_bps" > /dev/null
echo "  API key: $KEY"

step "Registering $MERCHANT's webhook endpoint with notification-service"
ENDPOINT=$(http -X PUT "$NOTIFY/ops/merchants/$MERCHANT/webhook-endpoint" \
  -H "Authorization: Bearer $OPS_KEY" -H 'Content-Type: application/json' \
  -d '{"url":"http://merchant-webhook-sink:8080/webhooks"}')
echo "  signing secret: $(field signingSecret "$ENDPOINT")"

balance() {
  local body
  body=$(http "$API/v1/accounts/merchant_payable:$MERCHANT/balance" -H "Authorization: Bearer $KEY")
  body=$(field balanceMinor "$body")
  [[ $body =~ ^-?[0-9]+$ ]] || fail "no balanceMinor in the balance response"
  printf '%s' "$body"
}
BALANCE_BEFORE=$(balance)

REF="order-$(random_hex)"
step "Creating a £100.00 payment ($REF)"
CREATED=$(http -X POST "$API/v1/payments" \
  -H "Authorization: Bearer $KEY" -H "Idempotency-Key: $REF" -H 'Content-Type: application/json' \
  -d "{\"merchantReference\":\"$REF\",\"amountMinor\":10000,\"currency\":\"GBP\"}")
echo "  $CREATED"
PAYMENT=$(field paymentId "$CREATED")
[[ -n $PAYMENT ]] || fail "no paymentId in the response"
wait_for_status "$API/v1/payments/$PAYMENT" AUTHORIZED

step "Capturing it"
http -X POST "$API/v1/payments/$PAYMENT/capture" \
  -H "Authorization: Bearer $KEY" -H "Idempotency-Key: $REF-capture" > /dev/null
wait_for_status "$API/v1/payments/$PAYMENT" CAPTURED

step "Refunding £30.00"
REFUND=$(http -X POST "$API/v1/payments/$PAYMENT/refunds" \
  -H "Authorization: Bearer $KEY" -H "Idempotency-Key: $REF-refund-1" -H 'Content-Type: application/json' \
  -d "{\"merchantReference\":\"$REF-refund-1\",\"amountMinor\":3000,\"reason\":\"demo\"}")
REFUND_ID=$(field refundId "$REFUND")
[[ -n $REFUND_ID ]] || fail "no refundId in the response: $REFUND"
wait_for_status "$API/v1/refunds/$REFUND_ID" SUCCEEDED
PAYMENT_VIEW=$(http "$API/v1/payments/$PAYMENT" -H "Authorization: Bearer $KEY")
echo "  $PAYMENT_VIEW"
[[ $(field refundableMinor "$PAYMENT_VIEW") == 7000 ]] || fail "refundable should be 7000"

step "Ledger: one capture journal and one refund journal, each balanced"
sql ledgerpay "SELECT j.entry_type, p.account_id, p.amount_minor
               FROM journal_entries j JOIN postings p ON p.entry_id = j.id
               JOIN psp_operations o ON o.id = j.psp_operation_id
               WHERE o.payment_id = '$PAYMENT' ORDER BY j.posted_at, p.id"
JOURNALS=$(sql_value ledgerpay "SELECT count(*) FROM journal_entries j
                                JOIN psp_operations o ON o.id = j.psp_operation_id
                                WHERE o.payment_id = '$PAYMENT'")
[[ $JOURNALS == 2 ]] || fail "expected 2 journals, found $JOURNALS"

step "Merchant balance: −9900 on capture (1% fee kept), +3000 back on refund"
BALANCE_AFTER=$(balance)
echo "  merchant_payable:$MERCHANT $BALANCE_BEFORE → $BALANCE_AFTER (debit positive: negative is owed to the merchant)"
(( BALANCE_AFTER - BALANCE_BEFORE == -6900 )) || fail "the balance should have moved by −6900"

step "Events: published to Kafka by the outbox relay"
for _ in $(seq 1 30); do
  UNPUBLISHED=$(sql_value ledgerpay "SELECT count(*) FROM outbox_events
                                     WHERE aggregate_id = '$PAYMENT' AND published_at IS NULL")
  [[ $UNPUBLISHED == 0 ]] && break
  sleep 1
done
[[ $UNPUBLISHED == 0 ]] || fail "$UNPUBLISHED event(s) still unpublished after 30 s"
sql ledgerpay "SELECT seq, event_type, published_at FROM outbox_events
               WHERE aggregate_id = '$PAYMENT' ORDER BY seq"

step "Webhooks: delivered to the merchant, signed"
EVENTS=$(sql_value ledgerpay "SELECT count(*) FROM outbox_events WHERE aggregate_id = '$PAYMENT'")
for _ in $(seq 1 30); do
  DELIVERED=$(sql_value notification "SELECT count(*) FROM webhook_deliveries
                                      WHERE payment_id = '$PAYMENT' AND status = 'DELIVERED'")
  (( DELIVERED == EVENTS )) && break
  sleep 1
done
(( DELIVERED == EVENTS )) || fail "$DELIVERED of $EVENTS webhooks delivered after 30 s"
sql notification "SELECT event_type, status, response_status FROM webhook_deliveries
                  WHERE payment_id = '$PAYMENT' ORDER BY delivered_at"
"${COMPOSE[@]}" logs --no-log-prefix merchant-webhook-sink | { grep "$PAYMENT" || true; } | cut -c1-160

step "Settlement: mock-psp's report for the day imports with this payment's capture and refund"
psp_reference() {
  sql_value ledgerpay "SELECT psp_reference FROM psp_operations
                       WHERE payment_id = '$PAYMENT' AND type = '$1'"
}
success_day() {
  sql_value mockpsp "SELECT to_char(succeeded_at AT TIME ZONE 'UTC', 'YYYY-MM-DD')
                     FROM operations WHERE psp_reference = '$1'"
}
CAPTURE_REF=$(psp_reference CAPTURE)
REFUND_REF=$(psp_reference REFUND)
DAY=$(success_day "$CAPTURE_REF")
# A file, not $(...): the report's CRLF line ends must reach the import byte for byte.
REPORT_FILE=$(mktemp)
trap 'rm -f "$REPORT_FILE"' EXIT
http -o "$REPORT_FILE" "$PSP/reports/settlement?date=$DAY"
REPORT=$(http -X POST "$API/ops/settlement-reports?businessDate=$DAY" \
  -H "Authorization: Bearer $OPS_KEY" -H 'Content-Type: text/csv' --data-binary "@$REPORT_FILE")
echo "  $REPORT"
[[ $(field status "$REPORT") == VALID ]] || fail "the report should be VALID"
REPORT_ID=$(field reportId "$REPORT")
# Both are in the day's report unless the refund succeeded after midnight UTC.
EXPECTED_ROWS=$([[ $(success_day "$REFUND_REF") == "$DAY" ]] && echo 2 || echo 1)
sql ledgerpay "SELECT line_no, psp_reference, txn_type, amount_minor, succeeded_at
               FROM settlement_rows WHERE report_id = '$REPORT_ID'
               AND psp_reference IN ('$CAPTURE_REF', '$REFUND_REF') ORDER BY line_no"
FOUND=$(sql_value ledgerpay "SELECT count(*) FROM settlement_rows WHERE report_id = '$REPORT_ID'
                             AND psp_reference IN ('$CAPTURE_REF', '$REFUND_REF')")
(( FOUND == EXPECTED_ROWS )) || fail "expected $EXPECTED_ROWS of this payment's rows, found $FOUND"

step "Reconciliation: the report against the books, item by item"
RUN=$(http -X POST "$API/ops/recon-runs" -H "Authorization: Bearer $OPS_KEY" \
  -H 'Content-Type: application/json' -d "{\"reportId\":\"$REPORT_ID\"}")
RUN_ID=$(field runId "$RUN")
for _ in $(seq 1 30); do
  RUN=$(http "$API/ops/recon-runs/$RUN_ID" -H "Authorization: Bearer $OPS_KEY")
  [[ $(field status "$RUN") != RUNNING ]] && break
  sleep 1
done
[[ $(field status "$RUN") == COMPLETED ]] || fail "recon run $RUN_ID is $(field status "$RUN")"
sql ledgerpay "SELECT category, count(*) AS items FROM recon_items WHERE run_id = '$RUN_ID'
               GROUP BY category ORDER BY category"
OURS=$(sql_value ledgerpay "SELECT count(*) FROM recon_items WHERE run_id = '$RUN_ID'
                            AND category = 'MATCHED'
                            AND psp_reference IN ('$CAPTURE_REF', '$REFUND_REF')")
(( OURS == EXPECTED_ROWS )) || fail "this payment's capture and refund should be MATCHED"

step "Ledger integrity: the books against themselves, with the run and on demand"
CHECK=$(http -X POST "$API/ops/ledger-integrity-checks" -H "Authorization: Bearer $OPS_KEY")
echo "  $(field status "$CHECK"): $(field journals "$CHECK") journals, $(field postings "$CHECK") postings, $(field violationCount "$CHECK") violations"
[[ $(field status "$CHECK") == PASSED ]] || fail "the ledger integrity check should pass"
[[ $(sql_value ledgerpay "SELECT status FROM ledger_integrity_checks WHERE recon_run_id = '$RUN_ID'") == PASSED ]] \
  || fail "the check that ran with the reconciliation should pass"
http "$API/actuator/prometheus" -H "Authorization: Bearer $OPS_KEY" | grep '^ledger_invariant_violations[{ ]' | sed 's/^/  /'

step "Done"
cat <<EOF
Everything matched. Try it yourself with the same key:

  export KEY=$KEY
  curl -s $API/v1/payments/$PAYMENT -H "Authorization: Bearer \$KEY"

Inject a fault into mock-psp (dev profile only), then create another payment:

  curl -s -X POST $PSP/_admin/faults -H 'Content-Type: application/json' \\
    -d '{"merchant_id":"$MERCHANT","type":"AUTHORIZE","fault":"DECLINE","times":1}'

Stop the stack with: docker compose -f infra/docker-compose.yml down
EOF
