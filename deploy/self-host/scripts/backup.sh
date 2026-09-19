#!/usr/bin/env bash
# Phase 7 — nightly backup: Postgres (all app schemas) + uploads → off-box storage/NAS.
# Schedule via cron/systemd-timer. A backup you have never restored is not a backup —
# see restore verification in the runbook (Phase 7).
set -euo pipefail

cd "$(dirname "$0")/.."
set -a; . ./.env; set +a                  # SPRING_DATASOURCE_USERNAME / _PASSWORD

STAMP="$(date +%Y%m%d-%H%M%S)"
LOCAL="/srv/glr/backup"
REMOTE="${BACKUP_REMOTE:-nas:glr-erp}"    # rclone remote (or set to an rsync target)
KEEP_DAYS="${BACKUP_KEEP_DAYS:-14}"

mkdir -p "$LOCAL"

# 1) DB — custom-format dump of the five app schemas only (skips Postgres/Supabase internals).
#    Restores with pg_restore; -Fc is compressed and selective.
PGPASSWORD="$SPRING_DATASOURCE_PASSWORD" docker compose exec -T postgres \
  pg_dump -U "$SPRING_DATASOURCE_USERNAME" -d hris -Fc \
    -n hr -n hr_restricted -n sales -n customers -n price_catalog \
  > "$LOCAL/db-$STAMP.dump"

# 2) Uploads — the file store lives on the data disk.
tar -C /srv/glr -cf - uploads | zstd -q -o "$LOCAL/uploads-$STAMP.tar.zst"

# 3) Ship BOTH off the VM (a backup on the same disk dies with the disk).
rclone copy "$LOCAL/db-$STAMP.dump"        "$REMOTE/db/"
rclone copy "$LOCAL/uploads-$STAMP.tar.zst" "$REMOTE/uploads/"

# 4) Local retention (remote retention is configured on the NAS/rclone side).
find "$LOCAL" -type f -mtime "+$KEEP_DAYS" -delete

echo "backup ok: db-$STAMP.dump + uploads-$STAMP.tar.zst → $REMOTE"
