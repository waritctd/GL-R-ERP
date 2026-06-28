#!/usr/bin/env python3
"""Very small local ZKTeco SC700 test script.

This script is for field debugging only. It does not call the ERP backend and
does not clear logs. By default it also does not disable the device menu.
"""

from __future__ import annotations

import argparse
import os
import time
from datetime import datetime
from typing import Any

try:
    from zk import ZK
except ImportError:  # pragma: no cover - only happens before installing requirements
    ZK = None


def attendance_key(attendance: Any) -> tuple[str, str, str, str]:
    return (
        str(getattr(attendance, "user_id", "")),
        str(getattr(attendance, "timestamp", "")),
        str(getattr(attendance, "status", "")),
        str(getattr(attendance, "punch", "")),
    )


def print_attendance(attendance: Any, prefix: str = "Punch") -> None:
    print(prefix)
    print(f"  User ID : {getattr(attendance, 'user_id', None)}")
    print(f"  Time    : {getattr(attendance, 'timestamp', None)}")
    print(f"  Status  : {getattr(attendance, 'status', None)}")
    print(f"  Punch   : {getattr(attendance, 'punch', None)}")
    print(f"  UID     : {getattr(attendance, 'uid', None)}")
    print("-" * 30)


def connect(args: argparse.Namespace) -> Any:
    if ZK is None:
        raise RuntimeError("pyzk is not installed. Run: pip install -r agents/attendance/requirements.txt")
    print(f"Connecting to {args.host}:{args.port}")
    print(f"password={args.password} force_udp={args.force_udp} omit_ping={args.omit_ping}")
    zk = ZK(
        args.host,
        port=args.port,
        timeout=args.timeout,
        password=args.password,
        force_udp=args.force_udp,
        ommit_ping=args.omit_ping,
    )
    return zk.connect()


def print_device_info(conn: Any) -> None:
    print("Connected to ZKTeco device")
    for label, method_name in [
        ("Serial", "get_serialnumber"),
        ("Firmware", "get_firmware_version"),
        ("Platform", "get_platform"),
        ("Device time", "get_time"),
    ]:
        try:
            value = getattr(conn, method_name)()
        except Exception as exc:
            value = f"unavailable ({exc})"
        print(f"{label}: {value}")


def run_check(conn: Any, with_counts: bool) -> None:
    print_device_info(conn)
    if with_counts:
        users = conn.get_users()
        attendances = conn.get_attendance()
        print(f"Users: {len(users)}")
        print(f"Attendance records: {len(attendances)}")


def run_pull(conn: Any, limit: int) -> None:
    attendances = list(conn.get_attendance())
    attendances.sort(key=lambda item: getattr(item, "timestamp", datetime.min))
    if limit > 0:
        attendances = attendances[-limit:]
    print(f"Printing {len(attendances)} attendance records")
    for attendance in attendances:
        print_attendance(attendance)


def run_poll(conn: Any, interval: float) -> None:
    print("Polling get_attendance(). Tap a card now. Press Ctrl+C to stop.")
    seen = {attendance_key(item) for item in conn.get_attendance()}
    print(f"Baseline loaded: {len(seen)} existing records")
    while True:
        time.sleep(interval)
        for attendance in conn.get_attendance():
            key = attendance_key(attendance)
            if key in seen:
                continue
            seen.add(key)
            print_attendance(attendance, "New punch")


def run_live(conn: Any) -> None:
    print("Listening with live_capture(). Tap a card now. Press Ctrl+C to stop.")
    for attendance in conn.live_capture():
        if attendance is None:
            continue
        print_attendance(attendance, "Live punch")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Minimal local SC700 test")
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true", help="connect and print device info")
    mode.add_argument("--pull", action="store_true", help="print recent attendance records once")
    mode.add_argument("--poll", action="store_true", help="poll get_attendance and print only new records")
    mode.add_argument("--live", action="store_true", help="use pyzk live_capture")

    parser.add_argument("--host", default=os.getenv("ZK_HOST", "192.168.1.201"))
    parser.add_argument("--port", type=int, default=int(os.getenv("ZK_PORT", "4370")))
    parser.add_argument("--password", type=int, default=int(os.getenv("ZK_PASSWORD", "0")))
    parser.add_argument("--timeout", type=int, default=int(os.getenv("ZK_TIMEOUT_SECONDS", "10")))
    parser.add_argument("--force-udp", action="store_true", default=os.getenv("ZK_FORCE_UDP", "").lower() in {"1", "true", "yes"})
    parser.add_argument("--omit-ping", action="store_true", default=os.getenv("ZK_OMIT_PING", "").lower() in {"1", "true", "yes"})
    parser.add_argument("--disable-device", action="store_true", help="temporarily disable device menu while connected")
    parser.add_argument("--with-counts", action="store_true", help="include user/log counts in --check")
    parser.add_argument("--limit", type=int, default=20, help="record limit for --pull; 0 means all")
    parser.add_argument("--interval", type=float, default=1.0, help="poll interval seconds for --poll")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    conn = None
    disabled = False
    try:
        conn = connect(args)
        if args.disable_device:
            conn.disable_device()
            disabled = True

        if args.check:
            run_check(conn, args.with_counts)
        elif args.pull:
            run_pull(conn, args.limit)
        elif args.poll:
            run_poll(conn, args.interval)
        else:
            run_live(conn)
        return 0
    except KeyboardInterrupt:
        print("Stopped by user")
        return 0
    except Exception as exc:
        print(f"Error: {exc}")
        return 2
    finally:
        if conn is not None:
            if disabled:
                try:
                    conn.enable_device()
                except Exception as exc:
                    print(f"Could not re-enable device: {exc}")
            try:
                conn.disconnect()
            except Exception:
                pass
            print("Disconnected from device")


if __name__ == "__main__":
    raise SystemExit(main())
