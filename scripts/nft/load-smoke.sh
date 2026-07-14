#!/usr/bin/env bash
# scripts/nft/load-smoke.sh
#
# Opt-in local load/perf smoke test for GL-R-ERP hot read paths. Bash + curl only, zero new
# infra/dependencies. Logs in via POST /api/auth/login (cookie jar), then loops N GET requests
# over a handful of hot read endpoints, records per-request latency, and prints p50/p95/max plus
# a failure count. Exits non-zero on any request error or if p95 exceeds the configured threshold.
#
# Local-only by design: BASE_URL is hard-restricted to localhost/127.0.0.1 so this can never be
# pointed at the Render demo or any hosted/production environment.
#
# Usage:
#   scripts/nft/load-smoke.sh [--check]
#
#   --check   Dry-run mode: validates argument parsing and the BASE_URL guard only. Does not
#             require a running server. Used for CI-free reviewability of this script.
#
# Env vars (all optional):
#   BASE_URL          Backend base URL. Default: http://127.0.0.1:8080. MUST be localhost/127.0.0.1.
#   LOGIN_EMAIL        Email used to log in. Default: hr@glr.co.th
#   LOGIN_PASSWORD      Password used to log in. Default: password
#   REQUEST_COUNT       Number of requests per endpoint. Default: 50
#   P95_THRESHOLD_MS  Max acceptable p95 latency in milliseconds. Default: 2000
#
# See scripts/nft/README.md for prerequisites (local docker-compose stack + a seeded login).

set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
LOGIN_EMAIL="${LOGIN_EMAIL:-hr@glr.co.th}"
LOGIN_PASSWORD="${LOGIN_PASSWORD:-password}"
REQUEST_COUNT="${REQUEST_COUNT:-50}"
P95_THRESHOLD_MS="${P95_THRESHOLD_MS:-2000}"

CHECK_MODE=0
for arg in "$@"; do
  case "$arg" in
    --check)
      CHECK_MODE=1
      ;;
    *)
      echo "Unknown argument: $arg" >&2
      echo "Usage: $0 [--check]" >&2
      exit 2
      ;;
  esac
done

# --- Hard guard: never allow a non-local BASE_URL. -------------------------------------------
# This script must never be able to hit the Render demo, Vercel, Supabase, or any hosted/prod
# system. Only http(s)://localhost or http(s)://127.0.0.1 (with an optional :port) are accepted.
assert_local_base_url() {
  local url="$1"
  if [[ ! "$url" =~ ^https?://(localhost|127\.0\.0\.1)(:[0-9]+)?(/.*)?$ ]]; then
    echo "ERROR: BASE_URL must be localhost/127.0.0.1 only (local-only NFT smoke). Got: $url" >&2
    exit 1
  fi
}

assert_local_base_url "$BASE_URL"

if [[ "$CHECK_MODE" -eq 1 ]]; then
  echo "OK: --check passed. BASE_URL guard and argument parsing are valid."
  echo "  BASE_URL=$BASE_URL"
  echo "  REQUEST_COUNT=$REQUEST_COUNT"
  echo "  P95_THRESHOLD_MS=$P95_THRESHOLD_MS"
  exit 0
fi

command -v curl >/dev/null 2>&1 || { echo "ERROR: curl is required." >&2; exit 1; }

COOKIE_JAR="$(mktemp)"
trap 'rm -f "$COOKIE_JAR"' EXIT

echo "Logging in as $LOGIN_EMAIL at $BASE_URL ..."
LOGIN_STATUS="$(curl -s -o /dev/null -w '%{http_code}' \
  -c "$COOKIE_JAR" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"${LOGIN_EMAIL}\",\"password\":\"${LOGIN_PASSWORD}\"}" \
  "${BASE_URL}/api/auth/login")"

if [[ "$LOGIN_STATUS" != "200" ]]; then
  echo "ERROR: login failed with HTTP $LOGIN_STATUS (check LOGIN_EMAIL/LOGIN_PASSWORD and that the local stack is seeded)." >&2
  exit 1
fi
echo "Login OK."

# Hot read paths to smoke. Format: "label|method|path"
CURRENT_MONTH="$(date +%Y-%m)"
ENDPOINTS=(
  "employees-list|GET|/api/employees"
  "attendance-punches|GET|/api/attendance/punches?limit=20"
  "payroll-current|GET|/api/payroll?payrollMonth=${CURRENT_MONTH}"
)

overall_failures=0
overall_exit=0

for entry in "${ENDPOINTS[@]}"; do
  IFS='|' read -r label method path <<< "$entry"
  url="${BASE_URL}${path}"
  echo ""
  echo "== ${label} (${method} ${path}) — ${REQUEST_COUNT} requests =="

  latencies=()
  failures=0

  for ((i = 1; i <= REQUEST_COUNT; i++)); do
    result="$(curl -s -o /dev/null -b "$COOKIE_JAR" -w '%{http_code} %{time_total}' -X "$method" "$url" || echo "000 0")"
    status="$(echo "$result" | awk '{print $1}')"
    time_total="$(echo "$result" | awk '{print $2}')"
    latency_ms="$(awk -v t="$time_total" 'BEGIN { printf "%.0f", t * 1000 }')"

    if [[ "$status" -lt 200 || "$status" -ge 400 ]]; then
      failures=$((failures + 1))
    fi
    latencies+=("$latency_ms")
  done

  # Sort latencies ascending for percentile math.
  IFS=$'\n' sorted=($(sort -n <<<"${latencies[*]}")); unset IFS
  count="${#sorted[@]}"
  p50_idx=$(( (count * 50 + 99) / 100 - 1 ))
  p95_idx=$(( (count * 95 + 99) / 100 - 1 ))
  (( p50_idx < 0 )) && p50_idx=0
  (( p95_idx < 0 )) && p95_idx=0
  (( p50_idx >= count )) && p50_idx=$((count - 1))
  (( p95_idx >= count )) && p95_idx=$((count - 1))

  p50="${sorted[$p50_idx]}"
  p95="${sorted[$p95_idx]}"
  max="${sorted[$((count - 1))]}"

  echo "  p50=${p50}ms  p95=${p95}ms  max=${max}ms  failures=${failures}/${REQUEST_COUNT}"

  overall_failures=$((overall_failures + failures))

  if [[ "$failures" -gt 0 ]]; then
    echo "  FAIL: ${failures} request(s) returned a non-2xx/3xx status." >&2
    overall_exit=1
  fi
  if [[ "$p95" -gt "$P95_THRESHOLD_MS" ]]; then
    echo "  FAIL: p95 (${p95}ms) exceeds threshold (${P95_THRESHOLD_MS}ms)." >&2
    overall_exit=1
  fi
done

echo ""
if [[ "$overall_exit" -eq 0 ]]; then
  echo "PASS: all endpoints within latency threshold, ${overall_failures} total failures."
else
  echo "FAIL: see per-endpoint output above."
fi

exit "$overall_exit"
