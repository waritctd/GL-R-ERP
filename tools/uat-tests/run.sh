#!/usr/bin/env bash
# Reset the local uat stack to a fresh seed, wait for health, run pytest, tear down.
# Usage: ./run.sh            (full suite)
#        ./run.sh -k ot01    (pytest args pass through, e.g. a single case)
# Point at a different backend with UAT_BASE_URL=... (default http://localhost:8099).
set -euo pipefail
cd "$(dirname "$0")"

COMPOSE="docker compose -f docker-compose.uat.yml"
BASE_URL="${UAT_BASE_URL:-http://localhost:8099}"
PYTHON="${PYTHON:-$(command -v python3 || command -v python)}"

if [ "$BASE_URL" = "http://localhost:8099" ]; then
  echo ">> resetting local uat stack (fresh seed)..."
  $COMPOSE down -v --remove-orphans >/dev/null 2>&1 || true
  $COMPOSE up -d --build

  echo ">> waiting for backend health..."
  for _ in $(seq 1 60); do
    if curl -sf "$BASE_URL/actuator/health" >/dev/null 2>&1; then echo ">> backend up"; break; fi
    sleep 3
  done
fi

mkdir -p reports
set +e
UAT_BASE_URL="$BASE_URL" "$PYTHON" -m pytest -q --junitxml=reports/junit.xml "$@"
rc=$?
set -e

if [ "$BASE_URL" = "http://localhost:8099" ]; then
  $COMPOSE down -v --remove-orphans >/dev/null 2>&1 || true
fi

echo ">> report: $(pwd)/reports/uat-results.md"
exit $rc
