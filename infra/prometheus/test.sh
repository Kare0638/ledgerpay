#!/usr/bin/env bash
# Checks the Prometheus config and runs the alert rules' unit tests, with the promtool of the
# Prometheus image the stack uses.
set -euo pipefail
cd "$(dirname "$0")"
IMAGE=prom/prometheus:v3.5.0
docker run --rm -v "$PWD:/rules:ro" -w /rules --entrypoint promtool "$IMAGE" check rules alerts.yml
docker run --rm -v "$PWD:/rules:ro" -w /rules --entrypoint promtool "$IMAGE" test rules alerts.test.yml
