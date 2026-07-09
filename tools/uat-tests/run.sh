#!/usr/bin/env bash
# Reset the local uat stack to a fresh seed, wait for health, run pytest, tear down.
#
# Usage:
#   ./run.sh                      full deterministic suite (Mailpit-backed email capture, $0, no
#                                  network dependency) - the default, safe to run repeatedly/in CI
#   ./run.sh -k ot01               pytest args pass through, e.g. a single case
#   ./run.sh --live-email          fire real emails via Resend to one real inbox for you to check by
#                                  eye (test_live_email_smoke.py only - not part of the pass/fail
#                                  suite). Requires:
#                                    APP_MAIL_RESEND_API_KEY=re_...  (your Resend key)
#                                    LIVE_EMAIL_TO=you@example.com   (must be the Resend account's own
#                                                                     address until glr.co.th is
#                                                                     domain-verified in Resend)
#
# Point at a different backend with UAT_BASE_URL=... (default http://localhost:8099); skips the
# local reset/teardown so you can run against an already-running instance (e.g. live UAT smoke).
set -euo pipefail
cd "$(dirname "$0")"

BASE_URL="${UAT_BASE_URL:-http://localhost:8099}"
PYTHON="${PYTHON:-$(command -v python3 || command -v python)}"

LIVE_EMAIL=false
PYTEST_ARGS=()
for arg in "$@"; do
  if [ "$arg" = "--live-email" ]; then
    LIVE_EMAIL=true
  else
    PYTEST_ARGS+=("$arg")
  fi
done

if [ "$LIVE_EMAIL" = true ]; then
  COMPOSE="docker compose -f docker-compose.uat.yml -f docker-compose.live-email.yml"
  MARKER_EXPR="live_email"
  echo ">> live-email mode: real Resend sends to \${LIVE_EMAIL_TO:-<unset - will fail>}"
else
  COMPOSE="docker compose -f docker-compose.uat.yml"
  MARKER_EXPR="not live_email"
fi

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
UAT_BASE_URL="$BASE_URL" "$PYTHON" -m pytest -q -m "$MARKER_EXPR" \
  --junitxml=reports/junit.xml "${PYTEST_ARGS[@]}"
rc=$?
set -e

if [ "$BASE_URL" = "http://localhost:8099" ]; then
  $COMPOSE down -v --remove-orphans >/dev/null 2>&1 || true
fi

if [ "$LIVE_EMAIL" = true ]; then
  echo ">> live-email smoke done - check ${LIVE_EMAIL_TO:-your inbox} for the [UAT LIVE] messages"
else
  echo ">> report: $(pwd)/reports/uat-results.md"
fi
exit $rc
