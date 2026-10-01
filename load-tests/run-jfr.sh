#!/usr/bin/env bash
# Records payment-service with JFR under the k6 steady scenario (design §14.1, #34) and writes
# load-tests/target/report.md. RATE (payments/s), RAMP and HOLD tune the load; ANALYZE=false
# stops after recording, so CI can upload the raw files before the analysis runs.
#
#   load-tests/run-jfr.sh
#
# Run on a quiet Linux host, not WSL: its clock jumps.
set -euo pipefail

cd "$(dirname "$0")/.."
OUT=load-tests/target
K6_IMAGE=grafana/k6:2.3.0
RATE=${RATE:-50}
RAMP=${RAMP:-1m}
HOLD=${HOLD:-4m}
COMPOSE=(docker compose -f infra/docker-compose.yml -f infra/docker-compose.perf.yml)

rm -rf "$OUT" && mkdir -p "$OUT" && chmod 777 "$OUT"   # k6 runs as its own user

"${COMPOSE[@]}" down -v --remove-orphans > /dev/null 2>&1 || true
"${COMPOSE[@]}" up --build -d --wait postgres kafka mock-psp payment-service

KEY="mk_load_$(openssl rand -hex 16)"
HASH=$(printf '%s' "$KEY" | sha256sum | cut -d' ' -f1)
"${COMPOSE[@]}" exec -T postgres psql -U ledgerpay -d ledgerpay -qc \
  "INSERT INTO merchants (id, api_key_hash) VALUES ('m_load', '$HASH')"

{
  echo "# Load recording results"
  echo
  echo "- Commit: \`$(git rev-parse --short HEAD)\`"
  echo "- Date: $(date -u +%Y-%m-%dT%H:%MZ)"
  echo "- Host: $(uname -sr); $(nproc) CPUs ($(lscpu | sed -n 's/^Model name:[[:space:]]*//p' | head -1)); $(free -g | awk '/^Mem:/ {print $2}') GB RAM"
  echo "- payment-service: $(docker run --rm --entrypoint java "$("${COMPOSE[@]}" images -q payment-service)" -version 2>&1 | head -1); \`-Xmx1g\`, default GC; JFR \`profile\` settings"
  echo "- Load: k6 ${K6_IMAGE#*:}, steady scenario, ramp to ${RATE} payments/s over ${RAMP}, hold ${HOLD}; each payment is create → authorised → capture → captured, mock-psp settling at once; PostgreSQL, Kafka and mock-psp on the same host"
} > "$OUT/environment.md"

docker run --rm --network ledgerpay_default \
  -e API_KEY="$KEY" -e RATE="$RATE" -e RAMP="$RAMP" -e HOLD="$HOLD" \
  -v "$PWD/load-tests:/scripts:ro" -v "$PWD/$OUT:/out" \
  "$K6_IMAGE" run --quiet --summary-export /out/k6-summary.json /scripts/steady.js | tee "$OUT/k6.txt"

echo "Stopping payment-service to write the recording"
# A normal shutdown makes the JVM write the recording (dumponexit).
"${COMPOSE[@]}" stop -t 60 payment-service
docker cp "$("${COMPOSE[@]}" ps -aq payment-service):/tmp/payment-service.jfr" "$OUT/payment-service.jfr"
"${COMPOSE[@]}" logs --no-color payment-service > "$OUT/payment-service.log"
"${COMPOSE[@]}" exec -T postgres psql -U ledgerpay -d ledgerpay -At -c \
  "SELECT status, count(*) FROM payments GROUP BY status ORDER BY status" > "$OUT/payment-states.txt"
"${COMPOSE[@]}" down -v > /dev/null 2>&1

echo "Recording: $(du -h "$OUT/payment-service.jfr" | cut -f1)"
if [[ "${ANALYZE:-true}" == true ]]; then
  python3 load-tests/analyze_jfr.py "$OUT"
fi
