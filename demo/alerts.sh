#!/usr/bin/env bash
# Alerts firing in a fault-injection scenario (design §13, #22). Run after demo/run.sh, on its
# stack: the reconciliation it ends with has a break, and this adds a webhook that contradicts our
# records. Prometheus must show both alerts firing, and the ledger's must stay quiet.
#
#   demo/alerts.sh                         check the alerts
#   demo/alerts.sh --screenshot FILE.png   also capture the Grafana dashboard
#
# Needs openssl to sign the webhook, besides what demo/run.sh needs.
cd "$(dirname "$0")/.."
export COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME:-ledgerpay-recon-demo}
COMPOSE=(docker compose -f infra/docker-compose.yml)
source scripts/lib.sh
PROMETHEUS=http://localhost:9090
WEBHOOK_SECRET=dev-only-webhook-secret   # PSP_WEBHOOK_SECRET in infra/docker-compose.yml

# The names of the alerts in this state, one per line.
# Each alert has one alertname (in its labels) and then one state, so the two pair up in order.
alerts() {
  http "$PROMETHEUS/api/v1/alerts" | grep -o '"alertname":"[^"]*"\|"state":"[^"]*"' | paste - - \
    | sed -n "s/^\"alertname\":\"\([^\"]*\)\"\t\"state\":\"$1\"$/\1/p" | sort -u
}

await_alert() {
  for _ in $(seq 1 60); do
    alerts firing | grep -qx "$1" && { ok "$1 is firing"; return; }
    sleep 2
  done
  fail "$1 did not fire within two minutes; firing: $(alerts firing | tr '\n' ' ')"
}

step "Prometheus scrapes both services with the ops key"
for _ in $(seq 1 30); do
  UP=$(http "$PROMETHEUS/api/v1/query?query=sum(up)" | sed -n 's/.*"value":\[[^,]*,"\([0-9]*\)".*/\1/p')
  [[ $UP == 2 ]] && break
  sleep 2
done
[[ $UP == 2 ]] || fail "expected 2 targets up, got ${UP:-none}"
ok "payment-service and notification-service up"

step "The last reconciliation run has a break: ReconciliationBreaks"
await_alert ReconciliationBreaks

step "A signed webhook for an operation we never asked for: quarantined, WebhookQuarantined"
TS=$(date +%s)
BODY="{\"event_id\":\"evt_alert_$TS\",\"event_type\":\"capture.succeeded\",\"psp_request_id\":\"req_unknown_$TS\",\"psp_reference\":\"psp_unknown_$TS\",\"merchant_id\":\"m_shop\",\"amount_minor\":100,\"currency\":\"GBP\",\"status\":\"SUCCEEDED\",\"occurred_at\":\"2026-01-01T00:00:00Z\",\"resource_version\":2}"
SIGNATURE=$(printf '%s' "$TS.$BODY" | openssl dgst -sha256 -hmac "$WEBHOOK_SECRET" | sed 's/^.*= //')
http -X POST "$API/webhooks/psp" -H 'Content-Type: application/json' \
  -H "X-PSP-Timestamp: $TS" -H "X-PSP-Signature: $SIGNATURE" -d "$BODY" > /dev/null
await_alert WebhookQuarantined

step "The books are sound, so the ledger alert stays quiet"
FIRING=$(alerts firing)
for quiet in LedgerInvariantViolated ServiceDown; do
  grep -qx "$quiet" <<< "$FIRING" && fail "$quiet should not fire"
  ok "$quiet not firing"
done
say_firing() { printf '  firing: %s\n' "$(alerts firing | tr '\n' ' ')"; }
say_firing

if [[ ${1:-} == --screenshot ]]; then
  step "The Grafana dashboard, captured to $2"
  OUT=$(cd "$(dirname "$2")" && pwd)/$(basename "$2")
  SHOTS=$(mktemp -d) && chmod 777 "$SHOTS"
  sleep 10   # a few more scrapes on the graphs
  docker run --rm --network "${COMPOSE_PROJECT_NAME}_default" -v "$SHOTS:/out" zenika/alpine-chrome:124 \
    --no-sandbox --hide-scrollbars --window-size=1600,2100 --virtual-time-budget=20000 \
    --screenshot=/out/dashboard.png "http://grafana:3000/d/ledgerpay/ledgerpay?orgId=1&kiosk&from=now-15m&to=now" \
    > /dev/null 2>&1
  cp "$SHOTS/dashboard.png" "$OUT" && rm -rf "$SHOTS"
  ok "saved $OUT"
fi

step "Done"
echo "Firing alerts: $PROMETHEUS/alerts · dashboard: http://localhost:3000"
