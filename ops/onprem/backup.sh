#!/usr/bin/env bash
# Nightly backup of the on-prem stack to the SMB share: a pg_dump of the db container plus a tar
# of the uploads volume (attachments stored on disk). See README.md "Backups".
#   BACKUP_DIR     where backups go (default /mnt/glr-backup; must be a mountpoint unless REQUIRE_MOUNT=0)
#   KEEP_DAYS      prune backups older than this, only after a fully successful run (default 30)
#   DUMP_CMD       writes a custom-format dump to stdout (overridable for tests)
#   VERIFY_CMD     reads a dump on stdin, fails if it is unreadable (overridable for tests)
#   UPLOADS_CMD    writes a .tgz of the uploads volume to stdout (overridable for tests)
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
BACKUP_DIR="${BACKUP_DIR:-/mnt/glr-backup}"
KEEP_DAYS="${KEEP_DAYS:-30}"
REQUIRE_MOUNT="${REQUIRE_MOUNT:-1}"
compose="docker compose --project-directory $here"
DUMP_CMD="${DUMP_CMD:-$compose exec -T db sh -c 'pg_dump -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -Fc'}"
VERIFY_CMD="${VERIFY_CMD:-$compose exec -T db pg_restore -l}"
UPLOADS_CMD="${UPLOADS_CMD:-docker run --rm -v onprem_uploads:/u:ro alpine:3.20 tar czf - -C /u .}"

die() { echo "backup: $*" >&2; exit 1; }

[ -d "$BACKUP_DIR" ] && [ -w "$BACKUP_DIR" ] || die "$BACKUP_DIR is not a writable directory"
if [ "$REQUIRE_MOUNT" = 1 ]; then
  mountpoint -q "$BACKUP_DIR" || die "$BACKUP_DIR is not mounted (is the SMB share up?)"
fi

ts="$(date +%Y%m%d-%H%M%S)"
dump="$BACKUP_DIR/glr-$ts.dump"
tgz="$BACKUP_DIR/glr-uploads-$ts.tgz"
dump_tmp="$BACKUP_DIR/.glr-$ts.dump.partial"
tgz_tmp="$BACKUP_DIR/.glr-uploads-$ts.tgz.partial"
trap 'rm -f "$dump_tmp" "$tgz_tmp"' EXIT

sh -c "$DUMP_CMD" > "$dump_tmp" || die "dump failed"
[ -s "$dump_tmp" ] || die "dump is empty"
sh -c "$VERIFY_CMD" < "$dump_tmp" > /dev/null || die "dump failed verification (pg_restore -l)"
mv "$dump_tmp" "$dump"

sh -c "$UPLOADS_CMD" > "$tgz_tmp" || die "uploads archive failed"
tar tzf "$tgz_tmp" > /dev/null || die "uploads archive is unreadable"
mv "$tgz_tmp" "$tgz"

find "$BACKUP_DIR" -maxdepth 1 -type f \( -name 'glr-*.dump' -o -name 'glr-uploads-*.tgz' \) \
  -mtime +"$KEEP_DAYS" -delete
find "$BACKUP_DIR" -maxdepth 1 -type f -name '.*.partial' -mtime +0 -delete
echo "backup: wrote $dump and $tgz"
