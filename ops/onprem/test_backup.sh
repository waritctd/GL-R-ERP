#!/usr/bin/env bash
# Plain-bash tests for backup.sh. Run: bash ops/onprem/test_backup.sh
# Fakes the dump, verify and uploads steps, so no Docker or Postgres is needed.
set -u
here="$(cd "$(dirname "$0")" && pwd)"
script="$here/backup.sh"
fails=0

pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; fails=$((fails + 1)); }
check() { if "$@"; then pass "$name"; else fail "$name"; fi; }

root="$(mktemp -d "${TMPDIR:-/tmp}/glr-backup-test.XXXXXX")"
new_dir() { mktemp -d "$root/case.XXXXXX"; }
count() { find "$1" -maxdepth 1 -name "$2" | wc -l | tr -d ' '; }
entries() { ls -A "$1" | wc -l | tr -d ' '; }
age_file() { touch -t "$(date -v-"$2"d +%Y%m%d%H%M 2>/dev/null || date -d "-$2 days" +%Y%m%d%H%M)" "$1"; }

# Fake uploads volume: a real tgz of one file, so the archive can be checked end to end.
src="$(new_dir)"; echo attachment > "$src/a.pdf"

run_backup() { # $1=BACKUP_DIR, rest = extra env assignments
  local dir="$1"; shift
  # The fake verify only succeeds if it actually receives the dump on stdin.
  env BACKUP_DIR="$dir" REQUIRE_MOUNT=0 KEEP_DAYS=30 \
    DUMP_CMD="echo fake-dump-bytes" VERIFY_CMD="grep -q fake-dump-bytes" \
    UPLOADS_CMD="tar czf - -C '$src' ." "$@" bash "$script" >/dev/null 2>&1
}

# Seeds one old and one recent file of each kind backup.sh manages, plus look-alikes it must keep.
seed_old() {
  touch "$1/glr-old.dump" "$1/glr-uploads-old.tgz"
  age_file "$1/glr-old.dump" 40; age_file "$1/glr-uploads-old.tgz" 40
}

name="happy path: one dump + one uploads archive, same timestamp, nothing else"
case_happy() {
  local d; d="$(new_dir)"
  run_backup "$d" || return 1
  local dump tgz; dump="$(ls "$d" | grep -E '^glr-[0-9]{8}-[0-9]{6}\.dump$')" || return 1
  tgz="$(ls "$d" | grep -E '^glr-uploads-[0-9]{8}-[0-9]{6}\.tgz$')" || return 1
  local ts="${dump#glr-}"; [ "glr-uploads-${ts%.dump}.tgz" = "$tgz" ] || return 1
  [ "$(cat "$d/$dump")" = "fake-dump-bytes" ] && tar tzf "$d/$tgz" | grep -q 'a.pdf' && [ "$(entries "$d")" = 2 ]
}
check case_happy

name="prunes old glr-*.dump, glr-uploads-*.tgz and stale .partial only"
case_prune() {
  local d; d="$(new_dir)"
  seed_old "$d"
  touch "$d/glr-recent.dump" "$d/glr-uploads-recent.tgz" "$d/other-old.dump" "$d/glr-old.txt" \
        "$d/.glr-stale.dump.partial" "$d/.glr-fresh.dump.partial"
  age_file "$d/glr-recent.dump" 5; age_file "$d/glr-uploads-recent.tgz" 5
  age_file "$d/other-old.dump" 40; age_file "$d/glr-old.txt" 40; age_file "$d/.glr-stale.dump.partial" 2
  run_backup "$d" || return 1
  [ ! -e "$d/glr-old.dump" ] && [ ! -e "$d/glr-uploads-old.tgz" ] && [ ! -e "$d/.glr-stale.dump.partial" ] \
    && [ -e "$d/glr-recent.dump" ] && [ -e "$d/glr-uploads-recent.tgz" ] && [ -e "$d/.glr-fresh.dump.partial" ] \
    && [ -e "$d/other-old.dump" ] && [ -e "$d/glr-old.txt" ] \
    && [ "$(count "$d" 'glr-*.dump')" = 2 ] && [ "$(count "$d" 'glr-uploads-*.tgz')" = 2 ]
}
check case_prune

# Each failure leaves the two seeded old files (nothing pruned), no .partial and no new tgz.
# $1 = expected glr-*.dump count: 1 (only the seeded one) when the DB step fails, 2 when only the
# uploads step fails (the verified dump from the same run is kept).
fails_cleanly() {
  local want_dumps="$1"; shift
  local d; d="$(new_dir)"
  seed_old "$d"
  ! run_backup "$d" "$@" && [ -e "$d/glr-old.dump" ] && [ -e "$d/glr-uploads-old.tgz" ] \
    && [ "$(count "$d" 'glr-uploads-*.tgz')" = 1 ] && [ -z "$(find "$d" -name '.*.partial')" ] \
    && [ "$(count "$d" 'glr-*.dump')" = "$want_dumps" ]
}

name="failing dump: non-zero, nothing written, nothing pruned"
check fails_cleanly 1 DUMP_CMD=false
name="empty dump: non-zero, nothing written, nothing pruned"
# A permissive verify isolates the empty-dump guard from the verify step.
check fails_cleanly 1 DUMP_CMD=true VERIFY_CMD=cat
name="verify rejects a dump it does not receive on stdin"
check fails_cleanly 1 DUMP_CMD="echo other-bytes"
name="failing verify: non-zero, nothing written, nothing pruned"
check fails_cleanly 1 VERIFY_CMD=false
name="failing uploads archive: non-zero, no tgz, nothing pruned (verified dump kept)"
check fails_cleanly 2 UPLOADS_CMD=false
name="unreadable uploads archive: non-zero, no tgz, nothing pruned (verified dump kept)"
check fails_cleanly 2 UPLOADS_CMD="echo not-a-tarball"

name="missing BACKUP_DIR: non-zero, dir not created"
case_missing() { local d; d="$(new_dir)"; rmdir "$d"; ! run_backup "$d" && [ ! -e "$d" ]; }
check case_missing

# Mount guard: a plain (unmounted) dir is refused when REQUIRE_MOUNT=1, the default, so an
# unmounted SMB share never silently fills the VM's own disk.
if command -v mountpoint >/dev/null; then
  name="unmounted BACKUP_DIR refused by default"
  case_unmounted() { local d; d="$(new_dir)"; ! run_backup "$d" REQUIRE_MOUNT=1 && [ -z "$(ls -A "$d")" ]; }
  check case_unmounted
else
  echo "SKIP: unmounted BACKUP_DIR refused by default (no mountpoint command here)"
fi

rm -rf "$root"
[ "$fails" = 0 ] && echo "ALL PASSED" || { echo "$fails FAILED"; exit 1; }
