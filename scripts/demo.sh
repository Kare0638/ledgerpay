#!/usr/bin/env bash
# The happy path end to end on the Docker Compose stack, in one command (#16):
#
#   scripts/demo.sh            build and start the stack, then run the demo against it
#   scripts/demo.sh --fresh    first delete the stack's volumes (an older database included)
#
# It seeds merchant m_demo with a new API key and registers its webhook endpoint, then pays £100,
# captures it, refunds £30 and checks each money effect: the ledger, the balance, the Kafka event
# and the signed webhook the merchant received. Needs Docker with Compose v2, curl and bash.
set -euo pipefail

cd "$(dirname "$0")/.."
COMPOSE=(docker compose -f infra/docker-compose.yml)
API=http://localhost:8080
NOTIFY=http://localhost:8082
OPS_KEY=dev-only-ops-key   # matches OPS_API_KEY_HASH in infra/docker-compose.yml
MERCHANT=m_demo

step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
fail() { printf '\033[31mFAILED: %s\033[0m\n' "$*" >&2; exit 1; }
# Reads one string or number field from a flat JSON object, so the demo needs no jq.
field() { sed -n "s/.*\"$1\":\"\{0,1\}\([^,\"}]*\)\"\{0,1\}[,}].*/\1/p" <<< "$2"; }
# curl that, on an HTTP error, stops the demo with the response body (a Problem) rather than a bare code.
http() {
  local out
  out=$(curl -sS --fail-with-body "$@") || fail "$* -> $out"
  printf '%s' "$out"
}
sql() { "${COMPOSE[@]}" exec -T postgres psql -U ledgerpay -d "$1" -v ON_ERROR_STOP=1 -c "$2"; }
sql_value() { "${COMPOSE[@]}" exec -T postgres psql -U ledgerpay -d "$1" -At -v ON_ERROR_STOP=1 -c "$2"; }

# Polls a GET until its status field is the expected one, as a client waiting for a webhook would.
wait_for_status() {
  local url=$1 expected=$2 body status
  for _ in $(seq 1 60); do
    body=$(http "$url" -H "Authorization: Bearer $KEY")
    status=$(field status "$body")
    [[ $status == "$expected" ]] && { echo "  $status"; return; }
    sleep 1
  done
  fail "$url is $status after 60 s, expected $expected"
}

if [[ ${1:-} == --fresh ]]; then
  step "Deleting the stack and its volumes"
  "${COMPOSE[@]}" down -v --remove-orphans
fi

step "Building and starting the stack (the first build downloads Maven dependencies: a few minutes)"
"${COMPOSE[@]}" up --build -d --wait || {
  "${COMPOSE[@]}" ps
  fail "the stack did not become healthy. A volume from an older checkout can fail migrations: rerun with --fresh"
}

step "Seeding merchant $MERCHANT with a new API key (only its SHA-256 is stored)"
KEY="mk_demo_$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')"
HASH=$(printf '%s' "$KEY" | sha256sum | cut -d' ' -f1)
sql ledgerpay "INSERT INTO merchants (id, api_key_hash) VALUES ('$MERCHANT', '$HASH')
               ON CONFLICT (id) DO UPDATE SET api_key_hash = EXCLUDED.api_key_hash" > /dev/null
echo "  API key: $KEY"

step "Registering $MERCHANT's webhook endpoint with notification-service"
ENDPOINT=$(http -X PUT "$NOTIFY/ops/merchants/$MERCHANT/webhook-endpoint" \
  -H "Authorization: Bearer $OPS_KEY" -H 'Content-Type: application/json' \
  -d '{"url":"http://merchant-webhook-sink:8080/webhooks"}')
echo "  signing secret: $(field signingSecret "$ENDPOINT")"

balance() {
  local body
  body=$(http "$API/v1/accounts/merchant_payable:$MERCHANT/balance" -H "Authorization: Bearer $KEY")
  field balanceMinor "$body"
}
BALANCE_BEFORE=$(balance)

REF="order-$(date +%s)"
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
"${COMPOSE[@]}" logs --no-log-prefix merchant-webhook-sink | grep "$PAYMENT" | cut -c1-160

step "Done"
cat <<EOF
Everything matched. Try it yourself with the same key:

  export KEY=$KEY
  curl -s $API/v1/payments/$PAYMENT -H "Authorization: Bearer \$KEY"

Inject a fault into mock-psp (dev profile only), then create another payment:

  curl -s -X POST http://localhost:8081/_admin/faults -H 'Content-Type: application/json' \\
    -d '{"merchant_id":"$MERCHANT","type":"AUTHORIZE","fault":"DECLINE","times":1}'

Stop the stack with: docker compose -f infra/docker-compose.yml down
EOF
