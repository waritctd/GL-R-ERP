#!/usr/bin/env python3
"""GL&R ZKTeco attendance agent (multi-site, dual transport).

The agent talks to a ZKTeco device over one of two selectable transports, chosen
by the ``ZK_TRANSPORT`` env var (default ``pullsdk``):

* ``pullsdk`` -- the ZKAccess3.5 Pull SDK (``plcommpro.dll``) via ctypes. This is
  what the *showroom* SC700 (a Pull-protocol access panel) requires. Windows +
  32-bit Python only (the DLL is 32-bit). Unchanged, default behaviour.
* ``pyzk``    -- pyzk's standalone ZK protocol. The *warehouse* unit
  (ZMM220_TFT, firmware 6.60/2017) accepts this but NOT the Pull SDK, which fails
  with an opaque ``PullLastError=-2``. Runs on any Python bitness; needs ``pyzk``.

Whichever transport is selected, both normalize device records into the same
``Punch`` dataclass, so all downstream logic (punch filtering, payload shape,
delivery/queue/state, backend dedup) is shared and identical.

Ingestion has two paths, both posting normalized punches to the GL&R Spring Boot
API (which dedups them via an upsert):

* Realtime  -> live capture while connected (LIVE_CAPTURE).
* Catch-up  -> read the device's stored attendance on startup and after every
  reconnect (CATCHUP_PULL).

Employees authenticate by PIN/fingerprint -- the device stores the employee
number as the user id -- so ``badge_code`` maps to it directly. On the Pull SDK
transport only verified-open events (``EventType == 0``) are treated as punches;
door/system events (7/8/100/255) and unregistered-card denials (27) are ignored.
pyzk's attendance table already contains only genuine person punches.
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
from typing import Any, Iterator
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
    timezone: str
    targets: tuple["Target", ...]
    reconnect_seconds: int
    post_timeout_seconds: int
    rtlog_poll_seconds: float
    catchup_overlap_minutes: int
    catchup_max_days: int
    dry_run: bool
    transport: str
    force_udp: bool
    omit_ping: bool

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
            timezone=os.getenv("ATTENDANCE_TIMEZONE", "Asia/Bangkok").strip(),
            targets=build_targets(base_dir),
            reconnect_seconds=env_int("ATTENDANCE_RECONNECT_SECONDS", 30),
            post_timeout_seconds=env_int("ATTENDANCE_POST_TIMEOUT_SECONDS", 10),
            rtlog_poll_seconds=env_float("ATTENDANCE_RTLOG_POLL_SECONDS", 1.5),
            catchup_overlap_minutes=env_int("ATTENDANCE_CATCHUP_OVERLAP_MINUTES", 5),
            catchup_max_days=env_int("ATTENDANCE_CATCHUP_MAX_DAYS", 3),
            dry_run=dry_run,
            transport=os.getenv("ZK_TRANSPORT", "pullsdk").strip().lower(),
            force_udp=env_bool("ZK_FORCE_UDP", False),
            omit_ping=env_bool("ZK_OMIT_PING", False),
        )


@dataclass(frozen=True)
class Target:
    """One backend the agent delivers punches to.

    Each target has its OWN agent token, delivery watermark (state file) and retry
    queue, so posting to prod and UAT is fully isolated: a punch that reaches prod
    but fails to reach UAT is queued for UAT alone and retried independently, and
    each backend's catch-up window is tracked separately."""
    name: str
    url: str
    token: str | None
    state_file: Path
    queue_file: Path


def _target_slug(name: str) -> str:
    """Filesystem-safe token derived from a target name (for its state/queue files)."""
    slug = "".join(ch.lower() if ch.isalnum() else "_" for ch in name).strip("_")
    return slug or "target"


def build_targets(base_dir: Path) -> tuple[Target, ...]:
    """Resolve the delivery targets.

    ``ATTENDANCE_API_TARGETS`` (JSON array of ``{name,url,token?,state_file?,
    queue_file?}``) enables fan-out to multiple backends -- the prod + UAT
    dual-post. Each entry gets its own state/queue file under the data dir
    (``agent_state.<name>.json`` / ``agent_queue.<name>.jsonl``) unless overridden.

    When it is unset the agent stays single-target and backward compatible: it
    reads the original ``ATTENDANCE_API_URL`` / ``ATTENDANCE_AGENT_TOKEN`` and
    keeps the original ``showroom_agent_state.json`` / ``_queue.jsonl`` filenames,
    so existing deployments neither reset their watermark nor lose a queued punch."""
    raw = blank_to_none(os.getenv("ATTENDANCE_API_TARGETS"))
    if raw is None:
        return (
            Target(
                name="default",
                url=os.getenv("ATTENDANCE_API_URL", "http://127.0.0.1:8080/api/attendance/punch").strip(),
                token=blank_to_none(os.getenv("ATTENDANCE_AGENT_TOKEN")),
                state_file=Path(os.getenv("ATTENDANCE_STATE_FILE", str(base_dir / "showroom_agent_state.json"))),
                queue_file=Path(os.getenv("ATTENDANCE_QUEUE_FILE", str(base_dir / "showroom_agent_queue.jsonl"))),
            ),
        )

    try:
        specs = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise SystemExit(f"ATTENDANCE_API_TARGETS is not valid JSON: {exc}")
    if not isinstance(specs, list) or not specs:
        raise SystemExit("ATTENDANCE_API_TARGETS must be a non-empty JSON array of target objects")

    targets: list[Target] = []
    seen: set[str] = set()
    for index, spec in enumerate(specs):
        if not isinstance(spec, dict):
            raise SystemExit(f"ATTENDANCE_API_TARGETS[{index}] must be an object")
        name = str(spec.get("name") or f"target{index + 1}").strip()
        if name in seen:
            raise SystemExit(f"ATTENDANCE_API_TARGETS has a duplicate target name {name!r}")
        seen.add(name)
        url = str(spec.get("url") or "").strip()
        if not url:
            raise SystemExit(f"ATTENDANCE_API_TARGETS[{index}] ({name}) is missing 'url'")
        token_raw = spec.get("token")
        token = blank_to_none(str(token_raw)) if token_raw is not None else None
        slug = _target_slug(name)
        state_file = Path(spec.get("state_file") or (base_dir / f"agent_state.{slug}.json"))
        queue_file = Path(spec.get("queue_file") or (base_dir / f"agent_queue.{slug}.jsonl"))
        targets.append(Target(name=name, url=url, token=token, state_file=state_file, queue_file=queue_file))
    return tuple(targets)


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
    USER_BUFFER = 8 * 1024 * 1024  # ~8MB: the enrolled-user table is far smaller than transactions

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

    def get_user_rows(self) -> tuple[str | None, list[str]]:
        """Read the enrolled-user table (Pin, CardNo, Name, ...) via GetDeviceData.

        Returns the header line (comma-separated field names) and the data rows so
        callers can locate columns by name rather than by a fixed position.
        """
        buf = ctypes.create_string_buffer(self.USER_BUFFER)
        rc = self._dll.GetDeviceData(
            self._handle, buf, self.USER_BUFFER, b"user", b"*", b"", b""
        )
        if rc < 0:
            raise RuntimeError(
                f"GetDeviceData(user) failed rc={rc} "
                f"PullLastError={self._dll.PullLastError()}"
            )
        lines = [ln for ln in buf.value.decode("ascii", "replace").splitlines() if ln.strip()]
        if not lines:
            return None, []
        return lines[0], lines[1:]


# --------------------------------------------------------------------------- #
# Transport abstraction
#
# Both transports connect to a device and normalize its records into ``Punch``
# objects, so everything downstream (filtering, payloads, delivery) is shared.
# ``read_attendance`` returns EVERY identifier-bearing record (all event types)
# and lets each caller apply its own filter: the agent's catch-up keeps only
# verified-open punches, while ``export_transactions_dat`` keeps them all.
# --------------------------------------------------------------------------- #
class PullSdkTransport:
    """Pull SDK (plcommpro.dll) transport -- the showroom SC700 path."""

    def __init__(self, *, sdk_dir: str, host: str, port: int, password: str,
                 timeout_ms: int, poll_seconds: float = 1.5, **_ignored: Any) -> None:
        self._sdk = PullSDK(sdk_dir)
        self._host = host
        self._port = port
        self._password = password
        self._timeout_ms = timeout_ms
        self._poll_seconds = poll_seconds

    def connect(self) -> None:
        self._sdk.connect(self._host, self._port, self._password, self._timeout_ms)

    def disconnect(self) -> None:
        self._sdk.disconnect()

    def read_attendance(self, zone: ZoneInfo) -> list[Punch]:
        _header, rows = self._sdk.get_transaction_rows()
        return [p for row in rows if (p := txn_row_to_punch(row, zone)) is not None]

    def stream_live(self, zone: ZoneInfo) -> Iterator[Punch | None]:
        while True:
            idle = True
            for row in self._sdk.get_rt_log():
                punch = parse_rtlog_row(row, zone)
                if punch is not None:
                    idle = False
                    yield punch
            if idle:
                yield None  # idle tick: a good time for the caller to flush its queue
            time.sleep(self._poll_seconds)

    def read_user_mappings(self) -> list[dict[str, str]]:
        header, rows = self._sdk.get_user_rows()
        return pullsdk_user_mappings(header, rows)


class PyzkTransport:
    """pyzk standalone-protocol transport -- the warehouse ZMM220 path."""

    def __init__(self, *, host: str, port: int, password: str, timeout_ms: int,
                 force_udp: bool = False, omit_ping: bool = False, **_ignored: Any) -> None:
        try:
            from zk import ZK  # lazy: only the pyzk path needs the library installed
        except ImportError as exc:  # pragma: no cover - env-dependent
            raise RuntimeError(
                "pyzk is not installed but ZK_TRANSPORT=pyzk was requested. "
                'Run: pip install "pyzk>=0.9"'
            ) from exc
        self._ZK = ZK
        self._host = host
        self._port = port
        # pyzk takes an integer comm key; our env value is a string like "1".
        self._password = _int(password) if isinstance(password, str) else int(password or 0)
        self._timeout_s = max(1, round((timeout_ms or 4000) / 1000))
        self._force_udp = force_udp
        self._omit_ping = omit_ping
        self._conn: Any = None

    def connect(self) -> None:
        zk = self._ZK(
            self._host,
            port=self._port,
            timeout=self._timeout_s,
            password=self._password,
            force_udp=self._force_udp,
            ommit_ping=self._omit_ping,  # pyzk spells the kwarg "ommit_ping"
        )
        self._conn = zk.connect()

    def disconnect(self) -> None:
        if self._conn is not None:
            try:
                self._conn.disconnect()
            except Exception:  # pragma: no cover - best-effort cleanup
                LOGGER.debug("Ignoring pyzk disconnect error", exc_info=True)
            self._conn = None

    def read_attendance(self, zone: ZoneInfo) -> list[Punch]:
        punches = []
        for att in self._conn.get_attendance() or []:
            punch = attendance_to_punch(att, zone, "TRANSACTION")
            if punch is not None:
                punches.append(punch)
        return punches

    def stream_live(self, zone: ZoneInfo) -> Iterator[Punch | None]:
        # live_capture yields an Attendance for each punch, or None when the read
        # times out (~every self._timeout_s) -- which doubles as our idle tick.
        for att in self._conn.live_capture():
            if att is None:
                yield None
                continue
            punch = attendance_to_punch(att, zone, "RTLOG")
            if punch is not None:
                yield punch

    def read_user_mappings(self) -> list[dict[str, str]]:
        mappings: list[dict[str, str]] = []
        for user in self._conn.get_users() or []:
            code = str(getattr(user, "user_id", "") or "").strip()
            card = str(getattr(user, "card", "") or "").strip()
            if code and card and card != "0":
                mappings.append({"employee_code": code, "card_no": card})
        return mappings


def build_transport(transport: str, **params: Any) -> "PullSdkTransport | PyzkTransport":
    """Construct (but do not connect) the transport named by ``transport``."""
    name = (transport or "pullsdk").strip().lower()
    if name == "pyzk":
        return PyzkTransport(**params)
    if name in ("pullsdk", "pull", "dll", ""):
        return PullSdkTransport(**params)
    raise RuntimeError(f"Unknown ZK_TRANSPORT={transport!r}; use 'pullsdk' or 'pyzk'.")


def open_transport(config: AgentConfig) -> "PullSdkTransport | PyzkTransport":
    transport = build_transport(
        config.transport,
        sdk_dir=config.sdk_dir,
        host=config.zk_host,
        port=config.zk_port,
        password=config.comm_password,
        timeout_ms=config.connect_timeout_ms,
        force_udp=config.force_udp,
        omit_ping=config.omit_ping,
        poll_seconds=config.rtlog_poll_seconds,
    )
    transport.connect()
    return transport


def socket_check(config: AgentConfig) -> bool:
    LOGGER.info("Testing TCP connection to device at %s:%s", config.zk_host, config.zk_port)
    try:
        with socket.create_connection((config.zk_host, config.zk_port), timeout=config.tcp_timeout_seconds):
            LOGGER.info("TCP port check passed for %s:%s", config.zk_host, config.zk_port)
            return True
    except OSError:
        LOGGER.exception("TCP port check failed for %s:%s", config.zk_host, config.zk_port)
        return False


def sdk_check(config: AgentConfig) -> bool:
    LOGGER.info("Testing %s connection to device", config.transport)
    transport = None
    try:
        transport = open_transport(config)
        records = transport.read_attendance(ZoneInfo(config.timezone))
        LOGGER.info("%s connection passed attendance_records=%s", config.transport, len(records))
        return True
    except Exception:
        LOGGER.exception("%s connection failed", config.transport)
        return False
    finally:
        if transport is not None:
            transport.disconnect()


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


def txn_row_to_punch(row: str, zone: ZoneInfo) -> Punch | None:
    """Parse ANY identifier-bearing transaction row, preserving its event type.

    Unlike ``parse_transaction_row`` this does NOT drop non-verified-open events,
    so callers that want the full backfill (e.g. export_transactions_dat) can keep
    them while catch-up filters to ``EventType == 0`` itself."""
    parts = row.split(",")
    if len(parts) < 7:
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
        eventtype=_int(parts[TXN_EVENT], -1),
        inoutstate=_int(parts[TXN_INOUT]),
        source="TRANSACTION",
    )


def parse_transaction_row(row: str, zone: ZoneInfo) -> Punch | None:
    """A transaction row, but only if it is a genuine verified-open punch."""
    punch = txn_row_to_punch(row, zone)
    if punch is None or punch.eventtype != EVENT_VERIFIED_OPEN:
        return None
    return punch


def attendance_to_punch(att: Any, zone: ZoneInfo, source: str) -> Punch | None:
    """Normalize a pyzk ``Attendance`` (user_id/timestamp/status/punch) into a Punch.

    pyzk's attendance table holds only genuine person punches, so these are always
    treated as verified-open (``eventtype = 0``); the user id is the enrolled PIN
    (= employee_code), which maps straight to ``badge_code``."""
    badge = str(getattr(att, "user_id", "") or "").strip()
    if not badge or badge == "0":
        return None
    timestamp = getattr(att, "timestamp", None)
    if timestamp is None:
        return None
    if timestamp.tzinfo is None:
        timestamp = timestamp.replace(tzinfo=zone)
    return Punch(
        badge=badge,
        punch_time=timestamp,
        cardno="0",
        pin=badge,
        verified=_int(str(getattr(att, "status", 0) or 0)),
        doorid=0,
        eventtype=EVENT_VERIFIED_OPEN,
        inoutstate=_int(str(getattr(att, "punch", 0) or 0)),
        source=source,
    )


def _user_column_index(fields: list[str], *names: str) -> int:
    lowered = [f.strip().lower() for f in fields]
    for name in names:
        if name.lower() in lowered:
            return lowered.index(name.lower())
    raise RuntimeError(f"Could not find any of {names} in the device user header: {fields}")


def pullsdk_user_mappings(header: str | None, rows: list[str]) -> list[dict[str, str]]:
    """Build ``{employee_code=Pin, card_no=CardNo}`` pairs from the device user table."""
    if not header:
        raise RuntimeError("Device returned no user table header; nothing to sync.")
    fields = header.split(",")
    pin_idx = _user_column_index(fields, "Pin")
    card_idx = _user_column_index(fields, "CardNo", "Card")

    mappings: list[dict[str, str]] = []
    for row in rows:
        parts = row.split(",")
        if len(parts) <= max(pin_idx, card_idx):
            continue
        pin = parts[pin_idx].strip()
        card = parts[card_idx].strip()
        if not pin or not card or card == "0":
            continue
        mappings.append({"employee_code": pin, "card_no": card})
    return mappings


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
def load_state(target: Target) -> dict[str, Any]:
    if not target.state_file.exists():
        return {}
    try:
        return json.loads(target.state_file.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        LOGGER.warning("Ignoring unreadable state file %s", target.state_file)
        return {}


def save_state(target: Target, state: dict[str, Any]) -> None:
    target.state_file.parent.mkdir(parents=True, exist_ok=True)
    temp_file = target.state_file.with_suffix(".tmp")
    temp_file.write_text(json.dumps(state, indent=2, sort_keys=True), encoding="utf-8")
    temp_file.replace(target.state_file)


def last_delivered_time(target: Target) -> datetime | None:
    state = load_state(target)
    value = state.get("last_delivered_punch_time")
    if not value:
        return None
    try:
        return datetime.fromisoformat(value)
    except ValueError:
        LOGGER.warning("Ignoring invalid last_delivered_punch_time=%r", value)
        return None


def mark_delivered(config: AgentConfig, target: Target, payload: dict[str, Any]) -> None:
    state = load_state(target)
    state["target"] = target.name
    state["site_code"] = config.site_code
    state["device_code"] = config.device_code
    state["last_delivered_badge_code"] = payload["badge_code"]
    # Track the max punch_time we've delivered so catch-up never rewinds.
    previous = state.get("last_delivered_punch_time")
    if previous is None or payload["punch_time"] > previous:
        state["last_delivered_punch_time"] = payload["punch_time"]
    state["last_delivery_at"] = datetime.now(ZoneInfo(config.timezone)).isoformat(timespec="seconds")
    save_state(target, state)


def enqueue_payload(config: AgentConfig, target: Target, payload: dict[str, Any], reason: str) -> None:
    target.queue_file.parent.mkdir(parents=True, exist_ok=True)
    queue_record = {
        "queued_at": datetime.now(ZoneInfo(config.timezone)).isoformat(timespec="seconds"),
        "reason": reason,
        "payload": payload,
    }
    with target.queue_file.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(queue_record, sort_keys=True) + "\n")
    LOGGER.warning("Queued punch target=%s badge=%s time=%s reason=%s",
                   target.name, payload["badge_code"], payload["punch_time"], reason)


def flush_queue(config: AgentConfig, target: Target) -> None:
    if config.dry_run or not target.queue_file.exists():
        return

    records: list[dict[str, Any]] = []
    with target.queue_file.open("r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError:
                LOGGER.warning("Skipping malformed queue line")

    if not records:
        target.queue_file.unlink(missing_ok=True)
        return

    remaining: list[dict[str, Any]] = []
    for index, record in enumerate(records):
        payload = record.get("payload")
        if not isinstance(payload, dict):
            continue
        if post_payload(config, target, payload, enqueue_on_failure=False):
            mark_delivered(config, target, payload)
        else:
            remaining.append(record)
            remaining.extend(records[index + 1:])
            break

    if remaining:
        with target.queue_file.open("w", encoding="utf-8") as handle:
            for record in remaining:
                handle.write(json.dumps(record, sort_keys=True) + "\n")
    else:
        target.queue_file.unlink(missing_ok=True)


def post_payload(config: AgentConfig, target: Target, payload: dict[str, Any],
                 enqueue_on_failure: bool = True) -> bool:
    if config.dry_run:
        LOGGER.info("DRY RUN target=%s punch payload=%s", target.name, json.dumps(payload, sort_keys=True))
        return True

    headers = {"Content-Type": "application/json"}
    if target.token:
        headers["X-GLR-Agent-Token"] = target.token

    try:
        response = requests.post(
            target.url,
            data=json.dumps(payload),
            headers=headers,
            timeout=config.post_timeout_seconds,
        )
        if 200 <= response.status_code < 300:
            LOGGER.info("Delivered punch target=%s badge=%s time=%s",
                        target.name, payload["badge_code"], payload["punch_time"])
            return True

        reason = f"HTTP {response.status_code}: {response.text[:300]}"
        LOGGER.error("Backend rejected punch target=%s badge=%s time=%s %s",
                     target.name, payload["badge_code"], payload["punch_time"], reason)
    except requests.RequestException as exc:
        reason = str(exc)
        LOGGER.error("Backend request failed target=%s badge=%s time=%s %s",
                     target.name, payload["badge_code"], payload["punch_time"], reason)

    if enqueue_on_failure:
        enqueue_payload(config, target, payload, reason)
    return False


def deliver_payload(config: AgentConfig, payload: dict[str, Any]) -> bool:
    """Deliver one punch to every configured target, isolated per target.

    Returns True only if the punch reached ALL targets on this attempt; a target
    that fails has the punch queued for its own retry, so a single flaky backend
    never blocks the others and never drops the punch."""
    all_delivered = True
    for target in config.targets:
        flush_queue(config, target)
        delivered = post_payload(config, target, payload)
        # Dry runs must be side-effect free: never advance the delivered watermark,
        # or a later real run would skip everything the dry run "delivered".
        if delivered and not config.dry_run:
            mark_delivered(config, target, payload)
        all_delivered = all_delivered and delivered
    return all_delivered


# --------------------------------------------------------------------------- #
# Catch-up + live loops
# --------------------------------------------------------------------------- #
def catchup_cutoff(config: AgentConfig, target: Target) -> datetime:
    """Earliest punch_time we will backfill on catch-up, per target.

    Bounded by ATTENDANCE_CATCHUP_MAX_DAYS so the very first run does NOT replay
    the device's entire multi-year history; after that we resume from that target's
    own last delivered punch (minus a small overlap the backend dedups away). Each
    target has an independent watermark, so onboarding a new backend backfills it
    without re-flooding the others."""
    zone = ZoneInfo(config.timezone)
    now = datetime.now(zone)
    floor = now - timedelta(days=config.catchup_max_days)
    last = last_delivered_time(target)
    if last is None:
        return floor
    if last.tzinfo is None:
        last = last.replace(tzinfo=zone)
    return max(last - timedelta(minutes=config.catchup_overlap_minutes), floor)


def run_catchup(config: AgentConfig, transport: "PullSdkTransport | PyzkTransport | None" = None) -> int:
    owns_connection = transport is None
    if owns_connection:
        transport = open_transport(config)
    delivered_count = 0
    try:
        zone = ZoneInfo(config.timezone)
        # read_attendance returns every identifier-bearing record; catch-up keeps
        # only genuine verified-open punches. The device is read ONCE and each
        # target replays its own window from that single read.
        punches = [
            punch for punch in transport.read_attendance(zone)
            if punch.eventtype == EVENT_VERIFIED_OPEN
        ]
        punches.sort(key=lambda item: item.punch_time)

        for target in config.targets:
            flush_queue(config, target)
            cutoff = catchup_cutoff(config, target)
            window = [punch for punch in punches if punch.punch_time >= cutoff]
            LOGGER.info("Catch-up target=%s since %s -> %s punches",
                        target.name, cutoff.isoformat(), len(window))
            for punch in window:
                payload = punch_to_payload(config, punch, "CATCHUP_PULL")
                if post_payload(config, target, payload):
                    if not config.dry_run:
                        mark_delivered(config, target, payload)
                    delivered_count += 1

        LOGGER.info("Catch-up finished delivered_count=%s", delivered_count)
        return delivered_count
    finally:
        if owns_connection:
            transport.disconnect()


def run_live(config: AgentConfig) -> None:
    zone = ZoneInfo(config.timezone)
    while True:
        transport = None
        try:
            transport = open_transport(config)
            LOGGER.info("Connected to %s (%s transport). Running catch-up, then live.",
                        config.device_code, config.transport)
            run_catchup(config, transport)

            LOGGER.info("Starting live capture")
            for item in transport.stream_live(zone):
                if item is None:
                    for target in config.targets:  # idle tick: retry each target's queue
                        flush_queue(config, target)
                    continue
                deliver_payload(config, punch_to_payload(config, item, "LIVE_CAPTURE"))
        except KeyboardInterrupt:
            LOGGER.info("Stopping agent")
            return
        except Exception:
            LOGGER.exception("Live loop failed; reconnecting in %s seconds", config.reconnect_seconds)
            time.sleep(config.reconnect_seconds)
        finally:
            if transport is not None:
                transport.disconnect()


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #
def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="GL&R ZKTeco attendance agent (pullsdk/pyzk transport)")
    parser.add_argument("--check", action="store_true", help="test TCP and device (transport) connectivity")
    parser.add_argument("--once-catchup", action="store_true", help="pull the transaction table once and exit")
    parser.add_argument("--live", action="store_true", help="run persistent live capture loop")
    parser.add_argument("--dry-run", action="store_true", help="print payloads without posting to backend")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    configure_logging()
    args = parse_args(argv or sys.argv[1:])
    config = AgentConfig.from_env(dry_run_override=True if args.dry_run else None)

    LOGGER.info(
        "Agent config site=%s device=%s transport=%s device_addr=%s:%s targets=%s dry_run=%s",
        config.site_code,
        config.device_code,
        config.transport,
        config.zk_host,
        config.zk_port,
        ", ".join(f"{t.name}->{t.url}" for t in config.targets),
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
