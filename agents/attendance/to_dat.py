#!/usr/bin/env python3
"""Convert punches out of the new SenseFace 3A into the .dat the ERP already imports.

This is the GUARANTEED ERP bridge. It does not care how the punches got off the
device -- USB stick export, a ZKBio Time report, or a pyzk dump. If you can get
a file with a PIN and a timestamp in it, this turns it into the exact 6-field
tab-separated .dat that `import_dat.py` already POSTs to the backend, which
dedups on insert. So it is safe to re-run over overlapping exports.

    python3 to_dat.py DEVICE_EXPORT.csv -o showroom_2026-09-07.dat
    python3 import_dat.py showroom_2026-09-07.dat \
        --api-base-url https://gl-r-erp.onrender.com \
        --site-code SHOWROOM --device-code SHOWROOM_SC700

Keep --device-code SHOWROOM_SC700 for now even though the hardware changed:
attendance_punch.device_id is an FK to an existing attendance_device row, and
adding a new one is a migration -- which on this repo means a backend image
build and a manual Render deploy, not something to do tonight. Renaming the
device row later is cosmetic and touches no punch data.

Column detection is automatic and case-insensitive; override with --badge-col /
--time-col if the export uses names this does not recognise. Run without -o
first: it prints what it detected and the first few converted rows so you can
eyeball them before writing anything.
"""

from __future__ import annotations

import argparse
import csv
import io
import re
import sys
from datetime import datetime

DAT_TIME_FMT = "%Y-%m-%d %H:%M:%S"

BADGE_HINTS = ["personnel id", "user id", "userid", "pin", "employee", "badge",
               "emp no", "empno", "รหัส", "รหัสพนักงาน"]
TIME_HINTS = ["punch time", "time", "datetime", "date time", "check time",
              "attendance", "timestamp", "เวลา", "วันที่"]

# Timestamp layouts seen in ZKTeco exports, most specific first.
TIME_FORMATS = [
    "%Y-%m-%d %H:%M:%S", "%Y/%m/%d %H:%M:%S", "%d/%m/%Y %H:%M:%S",
    "%m/%d/%Y %H:%M:%S", "%Y-%m-%d %H:%M", "%Y/%m/%d %H:%M",
    "%d/%m/%Y %H:%M", "%m/%d/%Y %H:%M", "%Y%m%d%H%M%S",
]


def sniff_rows(path: str) -> list[list[str]]:
    raw = open(path, "rb").read()
    for enc in ("utf-8-sig", "utf-8", "cp874", "tis-620", "cp1252", "latin-1"):
        try:
            text = raw.decode(enc)
            break
        except UnicodeDecodeError:
            continue
    else:
        raise SystemExit("Could not decode the file in any known encoding.")
    sample = text[:8192]
    delim = "\t" if sample.count("\t") > sample.count(",") else ","
    return [r for r in csv.reader(io.StringIO(text), delimiter=delim) if any(c.strip() for c in r)]


def pick_col(header: list[str], hints: list[str], override: str | None) -> int:
    low = [h.strip().lower() for h in header]
    if override:
        if override.isdigit():
            return int(override)
        if override.lower() in low:
            return low.index(override.lower())
        raise SystemExit(f"Column {override!r} not found in header: {header}")
    for hint in hints:
        for i, h in enumerate(low):
            if hint in h:
                return i
    return -1


def parse_time(value: str) -> datetime | None:
    v = value.strip()
    if not v:
        return None
    for fmt in TIME_FORMATS:
        try:
            return datetime.strptime(v, fmt)
        except ValueError:
            continue
    return None


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("input")
    ap.add_argument("-o", "--out", help="write the .dat here (omit for a preview)")
    ap.add_argument("--badge-col", help="column name or 0-based index for the PIN")
    ap.add_argument("--time-col", help="column name or 0-based index for the timestamp")
    ap.add_argument("--no-header", action="store_true",
                    help="the file has no header row; use with --badge-col/--time-col indexes")
    args = ap.parse_args(argv)

    rows = sniff_rows(args.input)
    if not rows:
        raise SystemExit("File is empty.")

    if args.no_header:
        header, body = [f"col{i}" for i in range(len(rows[0]))], rows
    else:
        header, body = rows[0], rows[1:]

    bi = pick_col(header, BADGE_HINTS, args.badge_col)
    ti = pick_col(header, TIME_HINTS, args.time_col)
    if bi < 0 or ti < 0:
        print(f"Header: {header}", file=sys.stderr)
        raise SystemExit("Could not identify the PIN and/or timestamp column. "
                         "Pass --badge-col and --time-col (name or 0-based index).")
    print(f"detected  PIN column: [{bi}] {header[bi]!r}")
    print(f"detected time column: [{ti}] {header[ti]!r}")

    out_rows, skipped = [], 0
    for r in body:
        if max(bi, ti) >= len(r):
            skipped += 1
            continue
        badge = r[bi].strip()
        ts = parse_time(r[ti])
        # A punch with no PIN cannot be resolved to an employee; a punch with no
        # parseable time cannot be placed on a day. Either way it is unusable.
        if not badge or not re.fullmatch(r"\d+", badge) or ts is None:
            skipped += 1
            continue
        # Fields: badge, time, device_status(verified), punch_state(in/out),
        # work_code, reserved(event) -- matching export_transactions_dat.py.
        out_rows.append(f"{badge}\t{ts.strftime(DAT_TIME_FMT)}\t1\t0\t0\t0")

    print(f"\nconverted {len(out_rows)} punch(es), skipped {skipped} unusable row(s)")
    if not out_rows:
        raise SystemExit("Nothing converted -- check the detected columns above.")
    span = sorted(r.split('\t')[1] for r in out_rows)
    print(f"time span: {span[0]}  ->  {span[-1]}")
    print("\nfirst rows:")
    for r in out_rows[:5]:
        print("  " + r.replace("\t", " | "))

    if not args.out:
        print("\nPreview only -- pass -o FILE.dat to write it.")
        return 0
    with open(args.out, "w", encoding="utf-8", newline="") as f:
        f.write("\n".join(out_rows) + "\n")
    print(f"\nwrote {args.out} ({len(out_rows)} rows)")
    print("Next:  python3 import_dat.py "
          f"{args.out} --api-base-url https://gl-r-erp.onrender.com "
          "--site-code SHOWROOM --device-code SHOWROOM_SC700")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
