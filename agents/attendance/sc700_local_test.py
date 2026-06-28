#!/usr/bin/env python3
"""Scanner-only field test utility for the showroom ZKTeco SC700.

This script is intentionally backend-free. It never posts to the ERP API and
never clears attendance logs from the device. Use it first to prove network,
pyzk, historical pull, and live card taps before running showroom_agent.py.
"""

from __future__ import annotations

import argparse
import csv
import json
import logging
import os
import socket
import sys
import time
from dataclasses import dataclass
from datetime import date, datetime, time as datetime_time
from pathlib import Path
from typing import Any, TextIO
from zoneinfo import ZoneInfo

try:
    from zk import ZK
except ImportError:  # pragma: no cover - depends on field laptop/server setup
    ZK = None


LOGGER = logging.getLogger("sc700_local_test")


@dataclass(frozen=True)
class ScannerConfig:
    host: str
    port: int
    password: int
    timeout_seconds: int
    force_udp: bool
    omit_ping: bool
    timezone: str


class RecordWriter:
    def __init__(self, output_format: str, output_path: str | None):
        self.output_format = output_format
        self.output_path = Path(output_path) if output_path else None
        self.handle: TextIO | None = None
        self.csv_writer: csv.DictWriter[str] | None = None

    def __enter__(self) -> "RecordWriter":
        if self.output_path:
            self.output_path.parent.mkdir(parents=True, exist_ok=True)
            self.handle = self.output_path.open("w", encoding="utf-8", newline="")
        return self

    def __exit__(self, exc_type: object, exc: object, tb: object) -> None:
        if self.handle:
            self.handle.close()

    def write_record(self, record: dict[str, Any]) -> None:
        target = self.handle or sys.stdout
        if self.output_format == "jsonl":
            target.write(json.dumps(record, sort_keys=True) + "\n")
        elif self.output_format == "csv":
            self._write_csv(record, target)
        else:
            target.write(format_text_record(record) + "\n")
        target.flush()

    def _write_csv(self, record: dict[str, Any], target: TextIO) -> None:
        fields = [
            "badge_code",
            "punch_time",
            "work_date",
            "device_status",
            "punch_state",
            "work_code",
            "uid",
            "raw_user_id",
        ]
        if self.csv_writer is None:
            self.csv_writer = csv.DictWriter(target, fieldnames=fields, extrasaction="ignore")
            self.csv_writer.writeheader()
        self.csv_writer.writerow(record)


def configure_logging(verbose: bool) -> None:
    logging.basicConfig(
        level=logging.DEBUG if verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s - %(message)s",
    )


def require_pyzk() -> None:
    if ZK is None:
        raise RuntimeError("pyzk is not installed. Run: pip install -r agents/attendance/requirements.txt")


def build_zk(config: ScannerConfig) -> Any:
    require_pyzk()
    return ZK(
        config.host,
        port=config.port,
        timeout=config.timeout_seconds,
        password=config.password,
        force_udp=config.force_udp,
        ommit_ping=config.omit_ping,
    )


def socket_check(config: ScannerConfig) -> bool:
    LOGGER.info("Checking TCP reachability to %s:%s", config.host, config.port)
    try:
        with socket.create_connection((config.host, config.port), timeout=config.timeout_seconds):
            LOGGER.info("TCP check passed")
            return True
    except OSError:
        LOGGER.exception("TCP check failed")
        return False


def connect(config: ScannerConfig) -> Any:
    LOGGER.info(
        "Connecting with pyzk host=%s port=%s password=%s force_udp=%s",
        config.host,
        config.port,
        config.password,
        config.force_udp,
    )
    return build_zk(config).connect()


def disconnect_quietly(conn: Any) -> None:
    if conn is None:
        return
    try:
        conn.disconnect()
    except Exception:
        LOGGER.debug("Ignoring disconnect error", exc_info=True)


def safe_call(conn: Any, method_name: str) -> Any:
    try:
        method = getattr(conn, method_name)
    except AttributeError:
        return None
    try:
        return method()
    except Exception:
        LOGGER.debug("Device method failed: %s", method_name, exc_info=True)
        return None


def normalize_timestamp(value: Any, zone: ZoneInfo) -> datetime:
    if not isinstance(value, datetime):
        raise ValueError(f"Attendance timestamp must be datetime, got {type(value).__name__}")
    if value.tzinfo is None:
        return value.replace(tzinfo=zone)
    return value.astimezone(zone)


def safe_int(value: Any, default: int | None = None) -> int | None:
    if value is None:
        return default
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def attendance_to_record(attendance: Any, zone: ZoneInfo) -> dict[str, Any]:
    punch_time = normalize_timestamp(getattr(attendance, "timestamp", None), zone)
    raw_user_id = getattr(attendance, "user_id", None)
    badge_code = str(raw_user_id).strip()
    if not badge_code:
        raise ValueError("Attendance record has empty user_id/badge_code")
    return {
        "badge_code": badge_code,
        "punch_time": punch_time.isoformat(timespec="seconds"),
        "work_date": punch_time.date().isoformat(),
        "device_status": safe_int(getattr(attendance, "status", None), 1),
        "punch_state": safe_int(getattr(attendance, "punch", None), 0),
        "work_code": str(getattr(attendance, "workcode", 0) or 0),
        "uid": getattr(attendance, "uid", None),
        "raw_user_id": raw_user_id,
    }


def user_to_record(user: Any) -> dict[str, Any]:
    return {
        "uid": getattr(user, "uid", None),
        "user_id": getattr(user, "user_id", None),
        "name": getattr(user, "name", None),
        "privilege": getattr(user, "privilege", None),
        "card": getattr(user, "card", None),
        "group_id": getattr(user, "group_id", None),
    }


def format_text_record(record: dict[str, Any]) -> str:
    return (
        f"{record['punch_time']} "
        f"badge={record['badge_code']} "
        f"state={record['punch_state']} "
        f"status={record['device_status']} "
        f"uid={record.get('uid')}"
    )


def parse_since(value: str | None, zone: ZoneInfo) -> datetime | None:
    if not value:
        return None
    try:
        if len(value.strip()) == 10:
            parsed_date = date.fromisoformat(value)
            return datetime.combine(parsed_date, datetime_time.min, tzinfo=zone)
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise ValueError("--since must be YYYY-MM-DD or ISO datetime") from exc
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=zone)
    return parsed.astimezone(zone)


def run_check(config: ScannerConfig, include_counts: bool) -> int:
    tcp_ok = socket_check(config)
    if not tcp_ok and not config.force_udp:
        return 2

    conn = None
    try:
        conn = connect(config)
        info = {
            "serial_number": safe_call(conn, "get_serialnumber"),
            "firmware_version": safe_call(conn, "get_firmware_version"),
            "platform": safe_call(conn, "get_platform"),
            "device_name": safe_call(conn, "get_device_name"),
            "device_time": str(safe_call(conn, "get_time")),
        }
        if include_counts:
            users = safe_call(conn, "get_users") or []
            attendances = safe_call(conn, "get_attendance") or []
            info["user_count"] = len(users)
            info["attendance_count"] = len(attendances)
        LOGGER.info("pyzk check passed")
        print(json.dumps(info, indent=2, sort_keys=True))
        return 0
    except Exception:
        LOGGER.exception("pyzk check failed")
        return 2
    finally:
        disconnect_quietly(conn)


def run_pull(config: ScannerConfig, limit: int, since: str | None, writer: RecordWriter) -> int:
    zone = ZoneInfo(config.timezone)
    since_time = parse_since(since, zone)
    conn = None
    try:
        conn = connect(config)
        attendances = list(conn.get_attendance() or [])
        attendances.sort(key=lambda item: getattr(item, "timestamp", datetime.min))
        LOGGER.info("Fetched %s attendance records from device", len(attendances))

        records: list[dict[str, Any]] = []
        for attendance in attendances:
            try:
                record = attendance_to_record(attendance, zone)
            except ValueError:
                LOGGER.warning("Skipping malformed attendance record", exc_info=True)
                continue
            punch_time = datetime.fromisoformat(record["punch_time"])
            if since_time and punch_time < since_time:
                continue
            records.append(record)

        if limit > 0:
            records = records[-limit:]
        for record in records:
            writer.write_record(record)
        LOGGER.info("Printed %s attendance records", len(records))
        return 0
    except Exception:
        LOGGER.exception("Attendance pull failed")
        return 2
    finally:
        disconnect_quietly(conn)


def run_live(config: ScannerConfig, seconds: int, writer: RecordWriter) -> int:
    zone = ZoneInfo(config.timezone)
    deadline = time.monotonic() + seconds if seconds > 0 else None
    conn = None
    try:
        conn = connect(config)
        LOGGER.info("Live capture started. Tap a card now. Press Ctrl+C to stop.")
        for attendance in conn.live_capture():
            if deadline and time.monotonic() >= deadline:
                LOGGER.info("Live capture time limit reached")
                return 0
            if attendance is None:
                continue
            try:
                writer.write_record(attendance_to_record(attendance, zone))
            except ValueError:
                LOGGER.warning("Skipping malformed live attendance record", exc_info=True)
        return 0
    except KeyboardInterrupt:
        LOGGER.info("Live capture stopped by user")
        return 0
    except Exception:
        LOGGER.exception("Live capture failed")
        return 2
    finally:
        disconnect_quietly(conn)


def run_users(config: ScannerConfig, limit: int) -> int:
    conn = None
    try:
        conn = connect(config)
        users = list(conn.get_users() or [])
        users.sort(key=lambda item: str(getattr(item, "user_id", "")))
        LOGGER.info("Fetched %s users from device", len(users))
        if limit > 0:
            users = users[:limit]
        for user in users:
            print(json.dumps(user_to_record(user), sort_keys=True))
        return 0
    except Exception:
        LOGGER.exception("User pull failed")
        return 2
    finally:
        disconnect_quietly(conn)


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Backend-free SC700 scanner field test")
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true", help="test TCP and pyzk connection")
    mode.add_argument("--pull", action="store_true", help="pull existing attendance logs once")
    mode.add_argument("--live", action="store_true", help="listen for real-time card taps")
    mode.add_argument("--users", action="store_true", help="list users enrolled on the device")

    parser.add_argument("--host", default=os.getenv("ZK_HOST", "192.168.1.201"), help="SC700 IP address")
    parser.add_argument("--port", type=int, default=int(os.getenv("ZK_PORT", "4370")), help="SC700 port")
    parser.add_argument("--password", type=int, default=int(os.getenv("ZK_PASSWORD", "0")), help="SC700 comm password")
    parser.add_argument("--timeout", type=int, default=int(os.getenv("ZK_TIMEOUT_SECONDS", "10")), help="connection timeout seconds")
    parser.add_argument("--force-udp", action="store_true", default=os.getenv("ZK_FORCE_UDP", "").lower() in {"1", "true", "yes"})
    parser.add_argument("--omit-ping", action="store_true", default=os.getenv("ZK_OMIT_PING", "").lower() in {"1", "true", "yes"}, help="skip pyzk's pre-connect ping check")
    parser.add_argument("--timezone", default=os.getenv("ATTENDANCE_TIMEZONE", "Asia/Bangkok"))
    parser.add_argument("--limit", type=int, default=20, help="record/user limit; use 0 for no limit")
    parser.add_argument("--since", help="pull records from YYYY-MM-DD or ISO datetime")
    parser.add_argument("--seconds", type=int, default=0, help="live capture seconds; 0 means until Ctrl+C")
    parser.add_argument("--format", choices=["text", "jsonl", "csv"], default="text")
    parser.add_argument("--out", help="optional output file for pulled/live records")
    parser.add_argument("--with-counts", action="store_true", help="include user/log counts during --check")
    parser.add_argument("--verbose", action="store_true", help="show debug logging")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    configure_logging(args.verbose)
    config = ScannerConfig(
        host=args.host.strip(),
        port=args.port,
        password=args.password,
        timeout_seconds=args.timeout,
        force_udp=args.force_udp,
        omit_ping=args.omit_ping,
        timezone=args.timezone.strip(),
    )

    if args.check:
        return run_check(config, args.with_counts)
    if args.users:
        return run_users(config, args.limit)
    with RecordWriter(args.format, args.out) as writer:
        if args.pull:
            return run_pull(config, args.limit, args.since, writer)
        return run_live(config, args.seconds, writer)


if __name__ == "__main__":
    raise SystemExit(main())
