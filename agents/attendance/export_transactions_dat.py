#!/usr/bin/env python3
"""Export the SC700 transaction table to ZKTeco .dat file(s) for bulk import.

Reads the device transaction table over the Pull SDK, keeps rows that carry an
identifier (PIN or card number) within the last --days, and writes tab-separated
.dat file(s) in the format the GL&R backend's bulk import endpoint accepts
(POST /api/attendance/imports/dat, driven by import_dat.py).

This is the fast path for a historical backfill: the backend batch-inserts a
whole file in one request (with the same per-punch dedup as live capture),
instead of the agent POSTing punches one at a time.

Windows + 32-bit Python only (plcommpro.dll). Run it with ZKAccess closed and
the attendance service paused -- the SC700 allows only one Pull-SDK session:

    .\\pause-for-zkaccess.ps1
    py -3-32 export_transactions_dat.py --days 365
    # ...import each .dat (see printed commands)...
    .\\resume-agent.ps1

Output: <out-prefix>_001.dat, _002.dat, ... each <= --chunk rows (the backend
caps a single import at 100,000 rows). Each .dat line is 6 tab-separated fields:
badge_code, "yyyy-MM-dd HH:mm:ss" (device local / Bangkok), device_status,
punch_state, work_code, reserved_value.
"""

from __future__ import annotations

import argparse
import os
import sys
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

# Reuse the transport, time decode, and field layout from the agent (same package).
from showroom_agent import (
    DEFAULT_SDK_DIR,
    PullSDK,
    TXN_CARDNO,
    TXN_EVENT,
    TXN_INOUT,
    TXN_PIN,
    TXN_TIME,
    TXN_VERIFIED,
    _int,
    _short,
    decode_zk_time,
)

DAT_TIME_FMT = "%Y-%m-%d %H:%M:%S"


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--host", default=os.getenv("ZK_HOST", "192.168.1.202"))
    parser.add_argument("--port", type=int, default=int(os.getenv("ZK_PORT", "4370")))
    parser.add_argument("--password", default=os.getenv("ZK_COMM_PASSWORD", ""))
    parser.add_argument("--sdk-dir", default=os.getenv("ZK_SDK_DIR", DEFAULT_SDK_DIR))
    parser.add_argument("--timeout-ms", type=int, default=int(os.getenv("ZK_CONNECT_TIMEOUT_MS", "4000")))
    parser.add_argument("--timezone", default=os.getenv("ATTENDANCE_TIMEZONE", "Asia/Bangkok"))
    parser.add_argument("--days", type=int, default=365,
                        help="only export punches newer than this many days (default 365)")
    parser.add_argument("--chunk", type=int, default=50000,
                        help="max rows per .dat file (backend caps at 100000; default 50000)")
    parser.add_argument("--out-prefix", default="showroom_backfill",
                        help="output filename prefix (default showroom_backfill)")
    parser.add_argument("--api-base-url", default=os.getenv("ATTENDANCE_API_BASE_URL", "https://gl-r-erp.onrender.com"),
                        help="only used to print the import commands at the end")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    zone = ZoneInfo(args.timezone)
    cutoff = datetime.now(zone) - timedelta(days=args.days)

    sdk = PullSDK(args.sdk_dir)
    try:
        sdk.connect(args.host, args.port, args.password, args.timeout_ms)
        header, rows = sdk.get_transaction_rows()
    finally:
        sdk.disconnect()
    print(f"Read {len(rows)} transaction rows (header: {header})")

    kept: list[tuple[datetime, str, int, int, int]] = []
    skipped_no_id = 0
    skipped_old = 0
    for row in rows:
        parts = row.split(",")
        if len(parts) < 7:
            continue
        # "Everything with an ID": keep any row carrying a PIN or a card number.
        # Rows with neither (door-open / system events) can't be stored -- the
        # attendance_punch.badge_code column is NOT NULL.
        badge = next(
            (x for x in (parts[TXN_PIN].strip(), parts[TXN_CARDNO].strip()) if x and x != "0"),
            "",
        )
        if not badge:
            skipped_no_id += 1
            continue
        try:
            ts = decode_zk_time(int(parts[TXN_TIME].strip())).replace(tzinfo=zone)
        except (ValueError, OverflowError):
            continue
        if ts < cutoff:
            skipped_old += 1
            continue
        kept.append((
            ts,
            badge,
            _short(_int(parts[TXN_VERIFIED])),   # -> device_status
            _short(_int(parts[TXN_INOUT])),      # -> punch_state
            _int(parts[TXN_EVENT]),              # -> reserved_value (audit)
        ))

    kept.sort(key=lambda item: item[0])
    print(f"Kept {len(kept)} rows with an identifier newer than {cutoff.date()} "
          f"(last {args.days} days). Skipped {skipped_no_id} without an id, "
          f"{skipped_old} older than the window.")
    if not kept:
        print("Nothing to export.")
        return 0

    files: list[str] = []
    for start in range(0, len(kept), args.chunk):
        chunk = kept[start:start + args.chunk]
        path = f"{args.out_prefix}_{start // args.chunk + 1:03d}.dat"
        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            for ts, badge, device_status, punch_state, event in chunk:
                handle.write(
                    f"{badge}\t{ts.strftime(DAT_TIME_FMT)}\t"
                    f"{device_status}\t{punch_state}\t0\t{event}\n"
                )
        files.append(path)
        print(f"  wrote {path} ({len(chunk)} rows)")

    print("\nNext: import each file (HR login required), e.g.:")
    for path in files:
        print(f'  py -3-32 import_dat.py "{path}" --api-base-url {args.api_base_url}')
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
