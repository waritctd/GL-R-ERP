#!/usr/bin/env bash
# Phase 8 — rehearse (and later run) the data migration from Supabase into the new
# self-hosted Postgres. Dumps the FIVE app schemas + their flyway history, restores
# into the running `postgres` container. Idempotent-ish: refuses a non-empty target
# unless FORCE=1.
#
# Set SUPABASE_DUMP_URL to the Supabase connection string (keep it in your shell env,
# NOT in this file / not in git). Postgres MAJOR versions must match (Phase 0).
set -euo pipefail

cd "$(dirname "$0")/.."
set -a; . ./.env; set +a

: "${SUPABASE_DUMP_URL:?set SUPABASE_DUMP_URL=postgres://...@...supabase.co:5432/postgres}"
STAMP="$(date +%Y%m%d-%H%M%S)"
DUMP="/srv/glr/backup/supabase-$STAMP.dump"
SCHEMAS=(hr hr_restricted sales customers price_catalog)

# 1) Dump ONLY the app schemas (never Supabase's auth/storage/realtime/graphql internals).
#    flyway_schema_history lives in hr, so it comes across — the new app then sees the DB
#    as already-migrated and does NOT re-run migrations.
args=(); for s in "${SCHEMAS[@]}"; do args+=(-n "$s"); done
pg_dump "$SUPABASE_DUMP_URL" -Fc "${args[@]}" -f "$DUMP"
echo "dumped $(du -h "$DUMP" | cut -f1) → $DUMP"

# 2) Guard: don't clobber a target that already has app data unless FORCE=1.
EXIST="$(docker compose exec -T postgres psql -U "$SPRING_DATASOURCE_USERNAME" -d hris -tAc \
  "SELECT to_regclass('hr.employee') IS NOT NULL;")"
if [[ "$EXIST" == "t" && "${FORCE:-0}" != "1" ]]; then
  echo "target already has hr.employee — set FORCE=1 to overwrite"; exit 1
fi

# 3) Restore. --clean --if-exists lets a re-run replace objects; single txn = all-or-nothing.
docker compose exec -T postgres pg_restore -U "$SPRING_DATASOURCE_USERNAME" -d hris \
  --clean --if-exists --no-owner --no-privileges --single-transaction < "$DUMP"

# 4) Verify the Flyway SET (not just max) — expect V11 drift + the migration-demo versions.
echo "── flyway history on the restored DB ──"
docker compose exec -T postgres psql -U "$SPRING_DATASOURCE_USERNAME" -d hris -tAc \
  "SELECT string_agg(version, ',' ORDER BY version::numeric) FROM hr.flyway_schema_history;"
echo "── row sanity ──"
docker compose exec -T postgres psql -U "$SPRING_DATASOURCE_USERNAME" -d hris -tAc \
  "SELECT 'employees='||count(*) FROM hr.employee;"

echo "restore ok. Now boot the backend and confirm Flyway logs 'up to date' (no new applies)."
