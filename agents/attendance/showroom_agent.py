#!/usr/bin/env python3
"""Showroom ZKTeco SC700 attendance agent (Pull SDK transport).

The SC700 is a Pull-protocol access panel, so this agent talks to ``plcommpro.dll``
(the ZKAccess3.5 Pull SDK) directly via ctypes. pyzk's standalone protocol cannot
connect to this device. Windows + 32-bit Python only (the DLL is 32-bit).

Ingestion has two paths, both posting normalized punches to the GL&R Spring Boot
API (which dedups them via an upsert):

* Realtime  -> poll ``GetRTLog`` every ~1.5s while connected (LIVE_CAPTURE).
* Catch-up  -> read the device ``transaction`` table via ``GetDeviceData`` on
  startup and after every reconnect (CATCHUP_PULL).

Employees on this device authenticate by PIN/fingerprint -- the device stores
``CardNo = 0`` for everyone and the real employee number in ``Pin`` -- so
``badge_code`` maps to the transaction ``Pin`` field. Only verified-open events
(``EventType == 0``) are treated as punches; door/system events (7/8/100/255)
and unregistered-card denials (27) are ignored.
"""

from __future__ import annotations

import argparse
import ctypes
import json
import logging
import os
import socket
import sys
import time
from ctypes import c_char_p, c_int, c_void_p
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

import requests


LOGGER = logging.getLogger("showroom_attendance_agent")

DEFAULT_SDK_DIR = r"C:\Program Files (x86)\ZKTeco\ZKAccess3.5\NewSDK"

# Transaction table column order, confirmed from the device header row:
# Cardno,Pin,Verified,DoorID,EventType,InOutState,Time_second
TXN_CARDNO, TXN_PIN, TXN_VERIFIED, TXN_DOORID, TXN_EVENT, TXN_INOUT, TXN_TIME = range(7)

# GetRTLog column order, deduced by matching a known door event against its
# transaction row: time, Cardno, Pin, DoorID, EventType, InOutState, Verified.
RT_TIME, RT_CARDNO, RT_PIN, RT_DOORID, RT_EVENT, RT_INOUT, RT_VERIFIED = range(7)

# EventType of a normal verified door-open (a genuine person punch).
EVENT_VERIFIED_OPEN = 0


@dataclass(frozen=True)
class AgentConfig:
    zk_host: str
    zk_port: int
    comm_password: str
    connect_timeout_ms: int
    tcp_timeout_seconds: int
    sdk_dir: str
    site_code: str
    device_code: str
    api_url: str
    api_token: str | None
    timezone: str
    state_file: Path
    queue_file: Path
    reconnect_seconds: int
    post_timeout_seconds: int
    rtlog_poll_seconds: float
    catchup_overlap_minutes: int
    catchup_max_days: int
    dry_run: bool

    @classmethod
    def from_env(cls, dry_run_override: bool | None = None) -> "AgentConfig":
        base_dir = Path(os.getenv("ATTENDANCE_AGENT_DATA_DIR", ".")).resolve()
        dry_run = env_bool("ATTENDANCE_DRY_RUN", False)
        if dry_run_override is not None:
            dry_run = dry_run_override

        return cls(
            zk_host=os.getenv("ZK_HOST", "192.168.1.202").strip(),
            zk_port=env_int("ZK_PORT", 4370),
            comm_password=os.getenv("ZK_COMM_PASSWORD", "").strip(),
            connect_timeout_ms=env_int("ZK_CONNECT_TIMEOUT_MS", 4000),
            tcp_timeout_seconds=env_int("ZK_TIMEOUT_SECONDS", 10),
            sdk_dir=os.getenv("ZK_SDK_DIR", DEFAULT_SDK_DIR).strip(),
            site_code=os.getenv("ATTENDANCE_SITE_CODE", "SHOWROOM").strip().upper(),
            device_code=os.getenv("ATTENDANCE_DEVICE_CODE", "SHOWROOM_SC700").strip().upper(),
            api_url=os.getenv("ATTENDANCE_API_URL", "http://127.0.0.1:8080/api/attendance/punch").strip(),
            api_token=blank_to_none(os.getenv("ATTENDANCE_AGENT_TOKEN")),
            timezone=os.getenv("ATTENDANCE_TIMEZONE", "Asia/Bangkok").strip(),
            state_file=Path(os.getenv("ATTENDANCE_STATE_FILE", str(base_dir / "showroom_agent_state.json"))),
            queue_file=Path(os.getenv("ATTENDANCE_QUEUE_FILE", str(base_dir / "showroom_agent_queue.jsonl"))),
            reconnect_seconds=env_int("ATTENDANCE_RECONNECT_SECONDS", 30),
            post_timeout_seconds=env_int("ATTENDANCE_POST_TIMEOUT_SECONDS", 10),
            rtlog_poll_seconds=env_float("ATTENDANCE_RTLOG_POLL_SECONDS", 1.5),
            catchup_overlap_minutes=env_int("ATTENDANCE_CATCHUP_OVERLAP_MINUTES", 5),
            catchup_max_days=env_int("ATTENDANCE_CATCHUP_MAX_DAYS", 3),
            dry_run=dry_run,
        )


@dataclass(frozen=True)
class Punch:
    badge: str
    punch_time: datetime
    cardno: str
    pin: str
    verified: int
    doorid: int
    eventtype: int
    inoutstate: int
    source: str


# --------------------------------------------------------------------------- #
# Small env / logging helpers
# --------------------------------------------------------------------------- #
def blank_to_none(value: str | None) -> str | None:
    if value is None or not value.strip():
        return None
    return value.strip()


def env_bool(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "y", "on"}


def env_int(name: str, default: int) -> int:
    value = os.getenv(name)
    if value is None or not value.strip():
        return default
    try:
        return int(value)
    except ValueError as exc:
        raise ValueError(f"{name} must be an integer, got {value!r}") from exc


def env_float(name: str, default: float) -> float:
    value = os.getenv(name)
    if value is None or not value.strip():
        return default
    try:
        return float(value)
    except ValueError as exc:
        raise ValueError(f"{name} must be a number, got {value!r}") from exc


def configure_logging() -> None:
    level_name = os.getenv("ATTENDANCE_LOG_LEVEL", "INFO").upper()
    logging.basicConfig(
        level=getattr(logging, level_name, logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s - %(message)s",
    )


# --------------------------------------------------------------------------- #
# Pull SDK (plcommpro.dll) transport
# --------------------------------------------------------------------------- #
class PullSDK:
    """Thin ctypes wrapper over the ZKTeco Pull SDK (plcommpro.dll)."""

    RTLOG_BUFFER = 256 * 1024
    TXN_BUFFER = 32 * 1024 * 1024  # ~32MB: the transaction table can hold 60k+ rows

    def __init__(self, sdk_dir: str) -> None:
        if os.name != "nt":
            raise RuntimeError("The Pull SDK (plcommpro.dll) is Windows-only.")
        dll_path = os.path.join(sdk_dir, "plcommpro.dll")
        if not os.path.exists(dll_path):
            raise RuntimeError(
                f"plcommpro.dll not found in {sdk_dir!r}. Set ZK_SDK_DIR to the "
                "folder that contains it (e.g. ...\\ZKAccess3.5\\NewSDK)."
            )
        # Let Windows resolve plcommpro.dll's sibling DLLs from sdk_dir without
        # copying anything out of the ZKAccess install.
        if hasattr(os, "add_dll_directory"):
            os.add_dll_directory(sdk_dir)
        os.environ["PATH"] = sdk_dir + os.pathsep + os.environ.get("PATH", "")

        try:
            dll = ctypes.WinDLL(dll_path)
        except OSError as exc:  # almost always the 32/64-bit mismatch
            raise RuntimeError(
                f"Failed to load plcommpro.dll ({exc}). The DLL is 32-bit -- "
                "run this agent with 32-bit Python."
            ) from exc

        dll.Connect.restype = c_void_p
        dll.Connect.argtypes = [c_char_p]
        dll.Disconnect.argtypes = [c_void_p]
        dll.PullLastError.restype = c_int
        dll.GetRTLog.restype = c_int
        dll.GetRTLog.argtypes = [c_void_p, c_char_p, c_int]
        dll.GetDeviceData.restype = c_int
        dll.GetDeviceData.argtypes = [
            c_void_p, c_char_p, c_int, c_char_p, c_char_p, c_char_p, c_char_p
        ]
        self._dll = dll
        self._handle: Any = None

    def connect(self, host: str, port: int, password: str, timeout_ms: int) -> None:
        conn_str = (
            f"protocol=TCP,ipaddress={host},port={port},"
            f"timeout={timeout_ms},passwd={password}"
        ).encode("ascii")
        handle = self._dll.Connect(conn_str)
        if not handle:
            err = self._dll.PullLastError()
            raise RuntimeError(
                f"Pull SDK Connect failed (PullLastError={err}). Check IP/port "
                "reachability, the device comm password, and that ZKAccess3.5 is "
                "CLOSED (it holds an exclusive session)."
            )
        self._handle = handle

    def disconnect(self) -> None:
        if self._handle:
            try:
                self._dll.Disconnect(self._handle)
            except Exception:  # pragma: no cover - best-effort cleanup
                LOGGER.debug("Ignoring Pull SDK disconnect error", exc_info=True)
            self._handle = None

    def get_rt_log(self) -> list[str]:
        buf = ctypes.create_string_buffer(self.RTLOG_BUFFER)
        rc = self._dll.GetRTLog(self._handle, buf, self.RTLOG_BUFFER)
        if rc < 0:
            raise RuntimeError(
                f"GetRTLog failed rc={rc} PullLastError={self._dll.PullLastError()}"
            )
        if rc == 0:
            return []
        return [ln.strip() for ln in buf.value.decode("ascii", "replace").splitlines() if ln.strip()]

    def get_transaction_rows(self) -> tuple[str | None, list[str]]:
        buf = ctypes.create_string_buffer(self.TXN_BUFFER)
        rc = self._dll.GetDeviceData(
            self._handle, buf, self.TXN_BUFFER, b"transaction", b"*", b"", b""
        )
        if rc < 0:
            raise RuntimeError(
                f"GetDeviceData(transaction) failed rc={rc} "
                f"PullLastError={self._dll.PullLastError()}"
            )
        lines = [ln for ln in buf.value.decode("ascii", "replace").splitlines() if ln.strip()]
        if not lines:
            return None, []
        return lines[0], lines[1:]


def open_sdk(config: AgentConfig) -> PullSDK:
    sdk = PullSDK(config.sdk_dir)
    sdk.connect(config.zk_host, config.zk_port, config.comm_password, config.connect_timeout_ms)
    return sdk


def socket_check(config: AgentConfig) -> bool:
    LOGGER.info("Testing TCP connection to SC700 at %s:%s", config.zk_host, config.zk_port)
    try:
        with socket.create_connection((config.zk_host, config.zk_port), timeout=config.tcp_timeout_seconds):
            LOGGER.info("TCP port check passed for %s:%s", config.zk_host, config.zk_port)
            return True
    except OSError:
        LOGGER.exception("TCP port check failed for %s:%s", config.zk_host, config.zk_port)
        return False


def sdk_check(config: AgentConfig) -> bool:
    LOGGER.info("Testing Pull SDK connection to SC700")
    sdk = None
    try:
        sdk = open_sdk(config)
        header, rows = sdk.get_transaction_rows()
        LOGGER.info("Pull SDK connection passed transaction_rows=%s header=%s", len(rows), header)
        return True
    except RuntimeError:
        LOGGER.exception("Pull SDK connection failed")
        return False
    finally:
        if sdk is not None:
            sdk.disconnect()


# --------------------------------------------------------------------------- #
# Punch parsing
# --------------------------------------------------------------------------- #
def decode_zk_time(value: int) -> datetime:
    """Decode a ZKTeco-packed Time_second into a naive datetime (device local).

    Not Unix epoch: the value packs Y/M/D/h/m/s relative to 2000-01-01.
    """
    second = value % 60
    value //= 60
    minute = value % 60
    value //= 60
    hour = value % 24
    value //= 24
    day = value % 31 + 1
    value //= 31
    month = value % 12 + 1
    value //= 12
    year = value + 2000
    return datetime(year, month, day, hour, minute, second)


def _int(value: str, default: int = 0) -> int:
    try:
        return int(value.strip())
    except (TypeError, ValueError):
        return default


def _badge_from(id_a: str, id_b: str) -> str:
    """Return the populated identifier (PIN preferred). Employees have CardNo=0,
    so exactly one of the two id fields is a real number for a person punch."""
    for candidate in (id_a, id_b):
        candidate = (candidate or "").strip()
        if candidate and candidate != "0":
            return candidate
    return ""


def parse_transaction_row(row: str, zone: ZoneInfo) -> Punch | None:
    parts = row.split(",")
    if len(parts) < 7:
        return None
    if _int(parts[TXN_EVENT], -1) != EVENT_VERIFIED_OPEN:
        return None
    badge = _badge_from(parts[TXN_PIN], parts[TXN_CARDNO])
    if not badge:
        return None
    try:
        punch_time = decode_zk_time(int(parts[TXN_TIME].strip())).replace(tzinfo=zone)
    except (ValueError, OverflowError):
        return None
    return Punch(
        badge=badge,
        punch_time=punch_time,
        cardno=parts[TXN_CARDNO].strip(),
        pin=parts[TXN_PIN].strip(),
        verified=_int(parts[TXN_VERIFIED]),
        doorid=_int(parts[TXN_DOORID]),
        eventtype=EVENT_VERIFIED_OPEN,
        inoutstate=_int(parts[TXN_INOUT]),
        source="TRANSACTION",
    )


def parse_rtlog_row(row: str, zone: ZoneInfo) -> Punch | None:
    parts = row.split(",")
    if len(parts) < 7:
        return None
    if _int(parts[RT_EVENT], -1) != EVENT_VERIFIED_OPEN:
        return None
    badge = _badge_from(parts[RT_PIN], parts[RT_CARDNO])
    if not badge:
        return None
    try:
        punch_time = datetime.strptime(parts[RT_TIME].strip(), "%Y-%m-%d %H:%M:%S").replace(tzinfo=zone)
    except ValueError:
        return None
    return Punch(
        badge=badge,
        punch_time=punch_time,
        cardno=parts[RT_CARDNO].strip(),
        pin=parts[RT_PIN].strip(),
        verified=_int(parts[RT_VERIFIED]),
        doorid=_int(parts[RT_DOORID]),
        eventtype=EVENT_VERIFIED_OPEN,
        inoutstate=_int(parts[RT_INOUT]),
        source="RTLOG",
    )


def _short(value: int) -> int:
    return max(0, min(255, value))


def punch_to_payload(config: AgentConfig, punch: Punch, ingest_method: str) -> dict[str, Any]:
    punch_time_iso = punch.punch_time.isoformat(timespec="seconds")
    raw_payload = {
        "cardno": punch.cardno,
        "pin": punch.pin,
        "verified": punch.verified,
        "door_id": punch.doorid,
        "event_type": punch.eventtype,
        "in_out_state": punch.inoutstate,
        "source": punch.source,
        "punch_time": punch_time_iso,
    }
    return {
        "site_code": config.site_code,
        "device_code": config.device_code,
        "badge_code": punch.badge,
        "punch_time": punch_time_iso,
        "work_date": punch.punch_time.date().isoformat(),
        "device_status": _short(punch.verified),
        "punch_state": _short(punch.inoutstate),
        "work_code": "0",
        "reserved_value": str(punch.eventtype),
        "punch_source": "BIOMETRIC",
        "ingest_method": ingest_method,
        "raw_payload": raw_payload,
    }


# --------------------------------------------------------------------------- #
# State + delivery queue (unchanged behaviour, backend dedups)
# --------------------------------------------------------------------------- #
def load_state(config: AgentConfig) -> dict[str, Any]:
    if not config.state_file.exists():
        return {}
    try:
        return json.loads(config.state_file.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        LOGGER.warning("Ignoring unreadable state file %s", config.state_file)
        return {}


def save_state(config: AgentConfig, state: dict[str, Any]) -> None:
    config.state_file.parent.mkdir(parents=True, exist_ok=True)
    temp_file = config.state_file.with_suffix(".tmp")
    temp_file.write_text(json.dumps(state, indent=2, sort_keys=True), encoding="utf-8")
    temp_file.replace(config.state_file)


def last_delivered_time(config: AgentConfig) -> datetime | None:
    state = load_state(config)
    value = state.get("last_delivered_punch_time")
    if not value:
        return None
    try:
        return datetime.fromisoformat(value)
    except ValueError:
        LOGGER.warning("Ignoring invalid last_delivered_punch_time=%r", value)
        return None


def mark_delivered(config: AgentConfig, payload: dict[str, Any]) -> None:
    state = load_state(config)
    state["site_code"] = config.site_code
    state["device_code"] = config.device_code
    state["last_delivered_badge_code"] = payload["badge_code"]
    # Track the max punch_time we've delivered so catch-up never rewinds.
    previous = state.get("last_delivered_punch_time")
    if previous is None or payload["punch_time"] > previous:
        state["last_delivered_punch_time"] = payload["punch_time"]
    state["last_delivery_at"] = datetime.now(ZoneInfo(config.timezone)).isoformat(timespec="seconds")
    save_state(config, state)


def enqueue_payload(config: AgentConfig, payload: dict[str, Any], reason: str) -> None:
    config.queue_file.parent.mkdir(parents=True, exist_ok=True)
    queue_record = {
        "queued_at": datetime.now(ZoneInfo(config.timezone)).isoformat(timespec="seconds"),
        "reason": reason,
        "payload": payload,
    }
    with config.queue_file.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(queue_record, sort_keys=True) + "\n")
    LOGGER.warning("Queued punch badge=%s time=%s reason=%s", payload["badge_code"], payload["punch_time"], reason)


def flush_queue(config: AgentConfig) -> None:
    if config.dry_run or not config.queue_file.exists():
        return

    records: list[dict[str, Any]] = []
    with config.queue_file.open("r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError:
                LOGGER.warning("Skipping malformed queue line")

    if not records:
        config.queue_file.unlink(missing_ok=True)
        return

    remaining: list[dict[str, Any]] = []
    for index, record in enumerate(records):
        payload = record.get("payload")
        if not isinstance(payload, dict):
            continue
        if post_payload(config, payload, enqueue_on_failure=False):
            mark_delivered(config, payload)
        else:
            remaining.append(record)
            remaining.extend(records[index + 1:])
            break

    if remaining:
        with config.queue_file.open("w", encoding="utf-8") as handle:
            for record in remaining:
                handle.write(json.dumps(record, sort_keys=True) + "\n")
    else:
        config.queue_file.unlink(missing_ok=True)


def post_payload(config: AgentConfig, payload: dict[str, Any], enqueue_on_failure: bool = True) -> bool:
    if config.dry_run:
        LOGGER.info("DRY RUN punch payload=%s", json.dumps(payload, sort_keys=True))
        return True

    headers = {"Content-Type": "application/json"}
    if config.api_token:
        headers["X-GLR-Agent-Token"] = config.api_token

    try:
        response = requests.post(
            config.api_url,
            data=json.dumps(payload),
            headers=headers,
            timeout=config.post_timeout_seconds,
        )
        if 200 <= response.status_code < 300:
            LOGGER.info("Delivered punch badge=%s time=%s", payload["badge_code"], payload["punch_time"])
            return True

        reason = f"HTTP {response.status_code}: {response.text[:300]}"
        LOGGER.error("Backend rejected punch badge=%s time=%s %s", payload["badge_code"], payload["punch_time"], reason)
    except requests.RequestException as exc:
        reason = str(exc)
        LOGGER.error("Backend request failed badge=%s time=%s %s", payload["badge_code"], payload["punch_time"], reason)

    if enqueue_on_failure:
        enqueue_payload(config, payload, reason)
    return False


def deliver_payload(config: AgentConfig, payload: dict[str, Any]) -> bool:
    flush_queue(config)
    delivered = post_payload(config, payload)
    # Dry runs must be side-effect free: never advance the delivered watermark,
    # or a later real run would skip everything the dry run "delivered".
    if delivered and not config.dry_run:
        mark_delivered(config, payload)
    return delivered


# --------------------------------------------------------------------------- #
# Catch-up + live loops
# --------------------------------------------------------------------------- #
def catchup_cutoff(config: AgentConfig) -> datetime:
    """Earliest punch_time we will backfill on catch-up.

    Bounded by ATTENDANCE_CATCHUP_MAX_DAYS so the very first run does NOT replay
    the device's entire multi-year history; after that we resume from the last
    delivered punch (minus a small overlap the backend dedups away)."""
    zone = ZoneInfo(config.timezone)
    now = datetime.now(zone)
    floor = now - timedelta(days=config.catchup_max_days)
    last = last_delivered_time(config)
    if last is None:
        return floor
    if last.tzinfo is None:
        last = last.replace(tzinfo=zone)
    return max(last - timedelta(minutes=config.catchup_overlap_minutes), floor)


def run_catchup(config: AgentConfig, sdk: PullSDK | None = None) -> int:
    owns_connection = sdk is None
    if owns_connection:
        sdk = open_sdk(config)
    delivered_count = 0
    try:
        zone = ZoneInfo(config.timezone)
        cutoff = catchup_cutoff(config)
        _header, rows = sdk.get_transaction_rows()
        LOGGER.info("Catch-up scanning %s transaction rows since %s", len(rows), cutoff.isoformat())

        punches = [
            punch for row in rows
            if (punch := parse_transaction_row(row, zone)) is not None and punch.punch_time >= cutoff
        ]
        punches.sort(key=lambda item: item.punch_time)

        for punch in punches:
            payload = punch_to_payload(config, punch, "CATCHUP_PULL")
            if deliver_payload(config, payload):
                delivered_count += 1

        LOGGER.info("Catch-up finished delivered_count=%s", delivered_count)
        return delivered_count
    finally:
        if owns_connection:
            sdk.disconnect()


def run_live(config: AgentConfig) -> None:
    zone = ZoneInfo(config.timezone)
    while True:
        sdk = None
        try:
            sdk = open_sdk(config)
            LOGGER.info("Connected to SC700 (Pull SDK). Running catch-up, then live poll.")
            run_catchup(config, sdk)

            LOGGER.info("Starting live GetRTLog poll every %ss", config.rtlog_poll_seconds)
            while True:
                delivered_any = False
                for row in sdk.get_rt_log():
                    punch = parse_rtlog_row(row, zone)
                    if punch is None:
                        continue
                    deliver_payload(config, punch_to_payload(config, punch, "LIVE_CAPTURE"))
                    delivered_any = True
                if not delivered_any:
                    flush_queue(config)
                time.sleep(config.rtlog_poll_seconds)
        except KeyboardInterrupt:
            LOGGER.info("Stopping agent")
            return
        except Exception:
            LOGGER.exception("Live loop failed; reconnecting in %s seconds", config.reconnect_seconds)
            time.sleep(config.reconnect_seconds)
        finally:
            if sdk is not None:
                sdk.disconnect()


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #
def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="GL&R showroom SC700 attendance agent (Pull SDK)")
    parser.add_argument("--check", action="store_true", help="test TCP and Pull SDK connectivity to the SC700")
    parser.add_argument("--once-catchup", action="store_true", help="pull the transaction table once and exit")
    parser.add_argument("--live", action="store_true", help="run persistent live capture loop")
    parser.add_argument("--dry-run", action="store_true", help="print payloads without posting to backend")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    configure_logging()
    args = parse_args(argv or sys.argv[1:])
    config = AgentConfig.from_env(dry_run_override=True if args.dry_run else None)

    LOGGER.info(
        "Agent config site=%s device=%s sc700=%s:%s api=%s dry_run=%s",
        config.site_code,
        config.device_code,
        config.zk_host,
        config.zk_port,
        config.api_url,
        config.dry_run,
    )

    if args.check:
        tcp_ok = socket_check(config)
        sdk_ok = sdk_check(config) if tcp_ok else False
        return 0 if tcp_ok and sdk_ok else 2

    if args.once_catchup:
        run_catchup(config)
        return 0

    run_live(config)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
