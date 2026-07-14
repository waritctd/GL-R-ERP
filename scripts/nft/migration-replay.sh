#!/usr/bin/env bash
# scripts/nft/migration-replay.sh
#
# Opt-in local migration-replay smoke test. Builds the backend image, runs it against a
# throwaway, uniquely-named Postgres container so Flyway applies the full db/migration chain
# (V1..latest) to an empty schema, then loads a directory of ordered *.sql seed files on top via
# psql (not through Flyway — this is a one-off data load, not a tracked migration). This surfaces
# schema/constraint assumptions in the migration chain that only break against realistic,
# populated data (e.g. a UNIQUE index that's fine on an empty table but conflicts with real
# document-revision data) — a fresh/empty DB replay can never catch these.
#
# Local-only by design: uses a standalone, uniquely-named Docker container/volume/ports
# (never docker-compose's dev pgdata/backend_uploads), so it cannot corrupt or be corrupted by
# a normal local dev stack, and never touches any hosted/production database.
#
# Usage:
#   scripts/nft/migration-replay.sh --check
#   SEED_DIR=/path/to/ordered/seed/sql/files scripts/nft/migration-replay.sh
#
#   --check   Dry-run mode: validates argument parsing and the SEED_DIR guard only. Does not
#             build an image or start any container.
#
# Env vars:
#   SEED_DIR          Required (unless --check). Directory of *.sql files to apply, in
#                      lexicographic filename order, via `psql -f`, after the full Flyway
#                      chain has migrated an empty database. Not provided by this script —
#                      see scripts/nft/README.md for how to extract a prod-shaped seed.
#   REPLAY_DB          Default: replaydb
#   REPLAY_PGPORT       Default: 55432 (host port for the throwaway Postgres; must differ from
#                        any dev docker-compose Postgres to avoid a port clash)
#   REPLAY_APPPORT       Default: 8091 (host port for the throwaway backend; unused beyond
#                          startup — the migration itself is what's being tested)
#
# See scripts/nft/README.md for prerequisites and the seed-extraction step.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SEED_DIR="${SEED_DIR:-}"
REPLAY_DB="${REPLAY_DB:-replaydb}"
REPLAY_PGPORT="${REPLAY_PGPORT:-55432}"
REPLAY_APPPORT="${REPLAY_APPPORT:-8091}"

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

# --- Guard: SEED_DIR must be a real, non-empty, local directory. -----------------------------
# This is a local-only tool: it never accepts a remote path or URL, only a filesystem directory.
assert_valid_seed_dir() {
  local dir="$1"
  if [[ -z "$dir" ]]; then
    echo "ERROR: SEED_DIR is required (directory of ordered *.sql seed files)." >&2
    exit 1
  fi
  if [[ ! -d "$dir" ]]; then
    echo "ERROR: SEED_DIR does not exist or is not a directory: $dir" >&2
    exit 1
  fi
  if [[ -z "$(find "$dir" -maxdepth 1 -name '*.sql' -print -quit)" ]]; then
    echo "ERROR: SEED_DIR contains no *.sql files: $dir" >&2
    exit 1
  fi
}

if [[ "$CHECK_MODE" -eq 1 ]]; then
  if [[ -n "$SEED_DIR" ]]; then
    assert_valid_seed_dir "$SEED_DIR"
  fi
  echo "OK: --check passed. Argument parsing is valid."
  echo "  SEED_DIR=${SEED_DIR:-<unset, required for a live run>}"
  echo "  REPLAY_DB=$REPLAY_DB"
  echo "  REPLAY_PGPORT=$REPLAY_PGPORT"
  echo "  REPLAY_APPPORT=$REPLAY_APPPORT"
  exit 0
fi

assert_valid_seed_dir "$SEED_DIR"
command -v docker >/dev/null 2>&1 || { echo "ERROR: docker is required." >&2; exit 1; }

DB_CONTAINER="glr-replay-db"
APP_CONTAINER="glr-replay-app"
APP_IMAGE="glr-replay-backend:latest"
VOLUME="glr-replay-pgdata"

cleanup() {
  echo "Tearing down replay containers/volume/image..." >&2
  docker rm -f "$APP_CONTAINER" >/dev/null 2>&1 || true
  docker rm -f "$DB_CONTAINER" >/dev/null 2>&1 || true
  docker volume rm "$VOLUME" >/dev/null 2>&1 || true
  docker image rm "$APP_IMAGE" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "Starting throwaway Postgres ($DB_CONTAINER on port $REPLAY_PGPORT)..."
docker rm -f "$DB_CONTAINER" >/dev/null 2>&1 || true
docker volume rm "$VOLUME" >/dev/null 2>&1 || true
docker run -d --name "$DB_CONTAINER" \
  -e POSTGRES_DB="$REPLAY_DB" \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p "${REPLAY_PGPORT}:5432" \
  -v "${VOLUME}:/var/lib/postgresql/data" \
  postgres:16-alpine >/dev/null

echo "Waiting for Postgres to be ready..."
until docker exec "$DB_CONTAINER" pg_isready -U postgres -d "$REPLAY_DB" >/dev/null 2>&1; do
  sleep 1
done

echo "Building backend image (contains the full db/migration chain)..."
docker build -t "$APP_IMAGE" "$REPO_ROOT/backend" >/dev/null

echo "Running the backend once against the replay DB to apply Flyway migrations..."
echo "(default profile only — no demo/uat profile, so exactly db/migration applies)"
docker rm -f "$APP_CONTAINER" >/dev/null 2>&1 || true
docker run -d --name "$APP_CONTAINER" \
  -e SPRING_DATASOURCE_URL="jdbc:postgresql://host.docker.internal:${REPLAY_PGPORT}/${REPLAY_DB}" \
  -e SPRING_DATASOURCE_USERNAME=postgres \
  -e SPRING_DATASOURCE_PASSWORD=postgres \
  -e APP_FLYWAY_ENABLED=true \
  -p "${REPLAY_APPPORT}:8080" \
  "$APP_IMAGE" >/dev/null

echo "Waiting for migration to complete (watching for app startup)..."
for i in $(seq 1 30); do
  if docker logs "$APP_CONTAINER" 2>&1 | grep -q "Started HrBackendApplication"; then
    break
  fi
  if docker logs "$APP_CONTAINER" 2>&1 | grep -qi "FlywayException\|Application run failed"; then
    echo "FAIL: Flyway migration failed. Log:" >&2
    docker logs "$APP_CONTAINER" 2>&1 | tail -60 >&2
    exit 1
  fi
  sleep 1
done
docker logs "$APP_CONTAINER" 2>&1 | grep -iE "Migrating schema|Successfully applied|Started HrBackendApplication"
docker stop "$APP_CONTAINER" >/dev/null

echo ""
echo "Loading seed from $SEED_DIR (lexicographic order, via psql)..."
overall_exit=0
for f in "$SEED_DIR"/*.sql; do
  echo "== applying $(basename "$f") =="
  if ! docker exec -i "$DB_CONTAINER" psql -v ON_ERROR_STOP=1 -U postgres -d "$REPLAY_DB" < "$f"; then
    echo "FAIL: $(basename "$f") did not apply cleanly." >&2
    overall_exit=1
  fi
done

echo ""
echo "Flyway schema_history sanity check (seed files must NOT appear here):"
docker exec "$DB_CONTAINER" psql -U postgres -d "$REPLAY_DB" -c \
  "SELECT count(*) AS total, count(*) FILTER (WHERE NOT success) AS failed FROM hr.flyway_schema_history;"

if [[ "$overall_exit" -eq 0 ]]; then
  echo "PASS: full migration chain applied, seed loaded with 0 errors."
else
  echo "FAIL: see per-file output above. This is a real finding, not a script bug — do not" >&2
  echo "'fix' it by editing a committed db/migration file on this branch; flag it instead." >&2
fi

exit "$overall_exit"
