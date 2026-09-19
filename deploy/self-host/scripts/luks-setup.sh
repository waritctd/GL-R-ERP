#!/usr/bin/env bash
# Phase 1a — format the 300GB data disk as LUKS, auto-unlock via keyfile, mount /srv/glr.
#
# ⚠️ DESTRUCTIVE: luksFormat wipes the target device. Double-check DEV with `lsblk`.
# Run as root. This is a reference script — read every step before running it.
set -euo pipefail

DEV="${1:-}"                              # e.g. /dev/sdb  (the RAW data disk, NOT a partition on the OS disk)
MAPPER="glrdata"
MOUNT="/srv/glr"
KEYFILE="/root/glrdata.key"               # keyfile on the (unencrypted) root disk — chmod 600

if [[ -z "$DEV" ]]; then
  echo "usage: $0 /dev/sdX   (the data disk to ENCRYPT AND WIPE)"; lsblk; exit 1
fi
echo "About to LUKS-format and WIPE $DEV. This destroys all data on it."
read -r -p "Type the device path again to confirm: " CONFIRM
[[ "$CONFIRM" == "$DEV" ]] || { echo "mismatch, aborting"; exit 1; }

apt-get update && apt-get install -y cryptsetup

# 1) A random 4KB keyfile unlocks the volume unattended (keyfile threat model: protects a
#    disk removed/disposed on its own, NOT theft of the whole machine — see runbook).
install -m 600 /dev/null "$KEYFILE"
dd if=/dev/urandom of="$KEYFILE" bs=4096 count=1

# 2) Encrypt the disk and register the keyfile as a key.
cryptsetup luksFormat --type luks2 "$DEV" "$KEYFILE"
cryptsetup open "$DEV" "$MAPPER" --key-file "$KEYFILE"

# 3) Filesystem + mount point + the three data subdirs the stack expects.
mkfs.ext4 -L glrdata "/dev/mapper/$MAPPER"
mkdir -p "$MOUNT"
mount "/dev/mapper/$MAPPER" "$MOUNT"
mkdir -p "$MOUNT"/{postgres,uploads,backup,www}
chmod 700 "$MOUNT"

# 4) Persist: crypttab unlocks with the keyfile at boot; fstab mounts after.
UUID="$(blkid -s UUID -o value "$DEV")"
grep -q "$MAPPER" /etc/crypttab 2>/dev/null || \
  echo "$MAPPER UUID=$UUID $KEYFILE luks,discard" >> /etc/crypttab
grep -q "$MOUNT" /etc/fstab 2>/dev/null || \
  echo "/dev/mapper/$MAPPER $MOUNT ext4 defaults,nofail 0 2" >> /etc/fstab

echo "Done. REBOOT NOW and verify $MOUNT auto-mounts (findmnt $MOUNT) before continuing."
