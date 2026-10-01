#!/usr/bin/env bash
# Runs the M5 executor benchmark (design §14.1, #33) and writes benchmarks/target/results/report.md.
#
#   ./mvnw -B -pl benchmarks -am package -DskipTests && benchmarks/run.sh
#
# Passes: timing without JFR; connection-pool sensitivity; a shorter JFR pass for threads, heap
# and pinning; and Spring's default HTTP executor against the fix, timed and then recorded. Set LEDGERPAY_BENCH_JDBC_URL to use an existing PostgreSQL instead of
# a Testcontainers container per fork. Run on a quiet Linux host, not WSL: its clock jumps.
set -euo pipefail

cd "$(dirname "$0")/.."
JAR=benchmarks/target/benchmarks.jar
OUT=benchmarks/target/results
rm -rf "$OUT" && mkdir -p "$OUT/jfr" "$OUT/jfr-http"

JAVA_OPTS=()
if [[ -n "${LEDGERPAY_BENCH_JDBC_URL:-}" ]]; then
  # JMH passes the host JVM's arguments on to every fork.
  JAVA_OPTS+=("-Dledgerpay.bench.jdbc-url=$LEDGERPAY_BENCH_JDBC_URL")
  JAVA_OPTS+=("-Dledgerpay.bench.jdbc-user=${LEDGERPAY_BENCH_JDBC_USER:-ledgerpay}")
  JAVA_OPTS+=("-Dledgerpay.bench.jdbc-password=${LEDGERPAY_BENCH_JDBC_PASSWORD:-ledgerpay}")
fi

{
  echo "# Executor benchmark results"
  echo
  echo "- Commit: \`$(git rev-parse --short HEAD)\`"
  echo "- Date: $(date -u +%Y-%m-%dT%H:%MZ)"
  echo "- Host: $(uname -sr); $(nproc) CPUs ($(lscpu | sed -n 's/^Model name:[[:space:]]*//p' | head -1)); $(free -g | awk '/^Mem:/ {print $2}') GB RAM"
  echo "- JVM: $(java -version 2>&1 | head -1); default GC and heap"
  echo "- JMH: SampleTime, 3 × 2 s warmup, 5 × 2 s measurement, 1 fork per parameter set; one operation = one \`runOnce()\` over a claimed batch"
  echo "- PSP: stub in its own JVM, answering PENDING after the given delay; PostgreSQL 16"
} > "$OUT/environment.md"

# Virtual-thread start and end events are off in the stock profile; the peak count needs them.
jfr configure --input profile jdk.VirtualThreadStart#enabled=true jdk.VirtualThreadEnd#enabled=true \
  --output "$OUT/bench.jfc" > /dev/null

java "${JAVA_OPTS[@]}" -jar "$JAR" -rf json -rff "$OUT/timing.json" | tee "$OUT/timing.txt"

java "${JAVA_OPTS[@]}" -jar "$JAR" -rf json -rff "$OUT/pool.json" \
  -p executor=virtual,fixed-200 -p pspDelayMillis=20 -p batchSize=200 -p poolSize=2,5,10,50 \
  | tee "$OUT/pool.txt"

java "${JAVA_OPTS[@]}" -jar "$JAR" -rf json -rff "$OUT/jfr.json" -wi 1 -i 2 \
  -prof "jfr:configName=$PWD/$OUT/bench.jfc;dir=$PWD/$OUT/jfr" | tee "$OUT/jfr.txt"

HTTP=(-p executor=virtual,fixed-50 -p pspDelayMillis=20 -p batchSize=200 -p httpExecutor=virtual,spring-default)
java "${JAVA_OPTS[@]}" -jar "$JAR" -rf json -rff "$OUT/http.json" "${HTTP[@]}" | tee "$OUT/http.txt"
java "${JAVA_OPTS[@]}" -jar "$JAR" -rf json -rff "$OUT/http-jfr.json" -wi 1 -i 2 "${HTTP[@]}" \
  -prof "jfr:configName=$PWD/$OUT/bench.jfc;dir=$PWD/$OUT/jfr-http" | tee "$OUT/http-jfr.txt"

python3 benchmarks/analyze.py "$OUT"
