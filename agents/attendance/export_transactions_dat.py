#!/usr/bin/env python3
"""Export the SC700 transaction table to ZKTeco .dat file(s) for bulk import.

Reads the device transaction table over the Pull SDK, keeps rows that carry an
identifier (PIN or card number) within the last --days, and writes tab-separated
.dat file(s) in the format the GL&R backend's bulk import endpoint accepts
(POST /api/attendance/imports/dat, driven by import_dat.py).

This is the fast path for a historical backfill: the backend batch-inserts a
whole file in one request (with the same per-punch dedup as live capture),
instead of the agent POSTing punches one at a time.

Transport follows ZK_TRANSPORT (--transport): ``pullsdk`` (showroom SC700, needs
Windows + 32-bit Python + plcommpro.dll) or ``pyzk`` (warehouse ZMM220). On the
Pull SDK the device allows only one session, so pause the agent first:

    .\\pause-for-zkaccess.ps1
    py -3-32 export_transactions_dat.py --days 365
    # ...import each .dat (see printed commands)...
    .\\resume-agent.ps1

For the warehouse (pyzk) just add --transport pyzk (no ZKAccess involved).

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

# Reuse the transport and field mapping from the agent (same package).
from showroom_agent import DEFAULT_SDK_DIR, _short, build_transport

DAT_TIME_FMT = "%Y-%m-%d %H:%M:%S"


def _env_flag(name: str) -> bool:
    return os.getenv(name, "").strip().lower() in {"1", "true", "yes", "y", "on"}


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--transport", default=os.getenv("ZK_TRANSPORT", "pullsdk"),
                        choices=["pullsdk", "pyzk"], help="device transport (default from ZK_TRANSPORT)")
    parser.add_argument("--host", default=os.getenv("ZK_HOST", "192.168.1.202"))
    parser.add_argument("--port", type=int, default=int(os.getenv("ZK_PORT", "4370")))
    parser.add_argument("--password", default=os.getenv("ZK_COMM_PASSWORD", ""))
    parser.add_argument("--sdk-dir", default=os.getenv("ZK_SDK_DIR", DEFAULT_SDK_DIR))
    parser.add_argument("--timeout-ms", type=int, default=int(os.getenv("ZK_CONNECT_TIMEOUT_MS", "4000")))
    parser.add_argument("--force-udp", action="store_true", default=_env_flag("ZK_FORCE_UDP"),
                        help="pyzk only: force UDP transport")
    parser.add_argument("--omit-ping", action="store_true", default=_env_flag("ZK_OMIT_PING"),
                        help="pyzk only: skip the pre-connect ping")
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

    transport = build_transport(
        args.transport,
        sdk_dir=args.sdk_dir,
        host=args.host,
        port=args.port,
        password=args.password,
        timeout_ms=args.timeout_ms,
        force_udp=args.force_udp,
        omit_ping=args.omit_ping,
    )
    transport.connect()
    try:
        # read_attendance keeps every identifier-bearing record (all event types);
        # for a broad backfill we deliberately keep them all, only filtering by age.
        punches = transport.read_attendance(zone)
    finally:
        transport.disconnect()
    print(f"Read {len(punches)} attendance records carrying an identifier.")

    kept = sorted(
        (p for p in punches if p.punch_time >= cutoff),
        key=lambda p: p.punch_time,
    )
    print(f"Kept {len(kept)} records newer than {cutoff.date()} (last {args.days} days); "
          f"skipped {len(punches) - len(kept)} older than the window.")
    if not kept:
        print("Nothing to export.")
        return 0

    files: list[str] = []
    for start in range(0, len(kept), args.chunk):
        chunk = kept[start:start + args.chunk]
        path = f"{args.out_prefix}_{start // args.chunk + 1:03d}.dat"
        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            for punch in chunk:
                device_status = _short(punch.verified)
                punch_state = _short(punch.inoutstate)
                event = punch.eventtype           # reserved_value (audit)
                handle.write(
                    f"{punch.badge}\t{punch.punch_time.strftime(DAT_TIME_FMT)}\t"
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
