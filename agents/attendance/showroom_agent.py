#!/usr/bin/env python3
"""Showroom ZKTeco SC700 attendance agent.

Reads attendance punches from the showroom SC700 and sends them to the GL&R
Spring Boot API. This agent is intentionally showroom-only for the first rollout.
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import socket
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

import requests

try:
    from zk import ZK
except ImportError:  # pragma: no cover - exercised on servers before install
    ZK = None


LOGGER = logging.getLogger("showroom_attendance_agent")


@dataclass(frozen=True)
class AgentConfig:
    zk_host: str
    zk_port: int
    zk_password: int
    zk_timeout_seconds: int
    zk_force_udp: bool
    site_code: str
    device_code: str
    api_url: str
    api_token: str | None
    timezone: str
    state_file: Path
    queue_file: Path
    reconnect_seconds: int
    post_timeout_seconds: int
    catchup_overlap_minutes: int
    dry_run: bool

    @classmethod
    def from_env(cls, dry_run_override: bool | None = None) -> "AgentConfig":
        base_dir = Path(os.getenv("ATTENDANCE_AGENT_DATA_DIR", ".")).resolve()
        dry_run = env_bool("ATTENDANCE_DRY_RUN", False)
        if dry_run_override is not None:
            dry_run = dry_run_override

        return cls(
            zk_host=os.getenv("ZK_HOST", "192.168.1.201").strip(),
            zk_port=env_int("ZK_PORT", 4370),
            zk_password=env_int("ZK_PASSWORD", 0),
            zk_timeout_seconds=env_int("ZK_TIMEOUT_SECONDS", 10),
            zk_force_udp=env_bool("ZK_FORCE_UDP", False),
            site_code=os.getenv("ATTENDANCE_SITE_CODE", "SHOWROOM").strip().upper(),
            device_code=os.getenv("ATTENDANCE_DEVICE_CODE", "SHOWROOM_SC700").strip().upper(),
            api_url=os.getenv("ATTENDANCE_API_URL", "http://127.0.0.1:8080/api/attendance/punch").strip(),
            api_token=blank_to_none(os.getenv("ATTENDANCE_AGENT_TOKEN")),
            timezone=os.getenv("ATTENDANCE_TIMEZONE", "Asia/Bangkok").strip(),
            state_file=Path(os.getenv("ATTENDANCE_STATE_FILE", str(base_dir / "showroom_agent_state.json"))),
            queue_file=Path(os.getenv("ATTENDANCE_QUEUE_FILE", str(base_dir / "showroom_agent_queue.jsonl"))),
            reconnect_seconds=env_int("ATTENDANCE_RECONNECT_SECONDS", 30),
            post_timeout_seconds=env_int("ATTENDANCE_POST_TIMEOUT_SECONDS", 10),
            catchup_overlap_minutes=env_int("ATTENDANCE_CATCHUP_OVERLAP_MINUTES", 5),
            dry_run=dry_run,
        )


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


def configure_logging() -> None:
    level_name = os.getenv("ATTENDANCE_LOG_LEVEL", "INFO").upper()
    logging.basicConfig(
        level=getattr(logging, level_name, logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s - %(message)s",
    )


def require_pyzk() -> None:
    if ZK is None:
        raise RuntimeError("pyzk is not installed. Run: pip install -r agents/attendance/requirements.txt")


def socket_check(config: AgentConfig) -> bool:
    LOGGER.info("Testing TCP connection to SC700 at %s:%s", config.zk_host, config.zk_port)
    try:
        with socket.create_connection((config.zk_host, config.zk_port), timeout=config.zk_timeout_seconds):
            LOGGER.info("TCP port check passed for %s:%s", config.zk_host, config.zk_port)
            return True
    except OSError:
        LOGGER.exception("TCP port check failed for %s:%s", config.zk_host, config.zk_port)
        return False


def build_zk(config: AgentConfig) -> Any:
    require_pyzk()
    return ZK(
        config.zk_host,
        port=config.zk_port,
        timeout=config.zk_timeout_seconds,
        password=config.zk_password,
        force_udp=config.zk_force_udp,
        ommit_ping=False,
    )


def pyzk_check(config: AgentConfig) -> bool:
    LOGGER.info("Testing pyzk connection to SC700")
    conn = None
    try:
        conn = build_zk(config).connect()
        serial = safe_call(conn, "get_serialnumber")
        firmware = safe_call(conn, "get_firmware_version")
        device_time = safe_call(conn, "get_time")
        LOGGER.info("pyzk connection passed serial=%s firmware=%s device_time=%s", serial, firmware, device_time)
        return True
    except Exception:
        LOGGER.exception("pyzk connection failed")
        return False
    finally:
        disconnect_quietly(conn)


def safe_call(conn: Any, method_name: str) -> Any:
    try:
        method = getattr(conn, method_name)
    except AttributeError:
        return None
    try:
        return method()
    except Exception:
        return None


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
            remaining.extend(records[index + 1 :])
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
    if delivered:
        mark_delivered(config, payload)
    return delivered


def normalize_timestamp(value: Any, zone: ZoneInfo) -> datetime:
    if not isinstance(value, datetime):
        raise ValueError(f"Attendance timestamp must be datetime, got {type(value).__name__}")
    if value.tzinfo is None:
        return value.replace(tzinfo=zone)
    return value.astimezone(zone)


def int_field(value: Any, default: int) -> int:
    if value is None:
        return default
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def attendance_to_payload(config: AgentConfig, attendance: Any, ingest_method: str) -> dict[str, Any]:
    zone = ZoneInfo(config.timezone)
    punch_time = normalize_timestamp(attendance.timestamp, zone)
    badge_code = str(attendance.user_id).strip()
    if not badge_code:
        raise ValueError("Attendance record has empty user_id/badge_code")

    raw_payload = {
        "uid": getattr(attendance, "uid", None),
        "user_id": getattr(attendance, "user_id", None),
        "timestamp": punch_time.isoformat(timespec="seconds"),
        "status": getattr(attendance, "status", None),
        "punch": getattr(attendance, "punch", None),
    }

    return {
        "site_code": config.site_code,
        "device_code": config.device_code,
        "badge_code": badge_code,
        "punch_time": punch_time.isoformat(timespec="seconds"),
        "work_date": punch_time.date().isoformat(),
        "device_status": int_field(getattr(attendance, "status", None), 1),
        "punch_state": int_field(getattr(attendance, "punch", None), 0),
        "work_code": str(getattr(attendance, "workcode", 0) or 0),
        "reserved_value": "0",
        "punch_source": "BIOMETRIC",
        "ingest_method": ingest_method,
        "raw_payload": raw_payload,
    }


def should_send_catchup(config: AgentConfig, payload: dict[str, Any]) -> bool:
    last_time = last_delivered_time(config)
    if last_time is None:
        return True
    cutoff = last_time - timedelta(minutes=config.catchup_overlap_minutes)
    punch_time = datetime.fromisoformat(payload["punch_time"])
    return punch_time >= cutoff


def run_catchup(config: AgentConfig) -> int:
    LOGGER.info("Starting catch-up pull from SC700")
    conn = None
    delivered_count = 0
    try:
        conn = build_zk(config).connect()
        attendances = conn.get_attendance()
        attendances.sort(key=lambda item: item.timestamp)
        LOGGER.info("Fetched %s attendance records from SC700", len(attendances))

        for attendance in attendances:
            try:
                payload = attendance_to_payload(config, attendance, "CATCHUP_PULL")
            except ValueError:
                LOGGER.exception("Skipping malformed catch-up attendance record")
                continue
            if not should_send_catchup(config, payload):
                continue
            if deliver_payload(config, payload):
                delivered_count += 1

        LOGGER.info("Catch-up finished delivered_count=%s", delivered_count)
        return delivered_count
    finally:
        disconnect_quietly(conn)


def run_live(config: AgentConfig) -> None:
    while True:
        conn = None
        try:
            run_catchup(config)
            LOGGER.info("Starting live capture from SC700")
            conn = build_zk(config).connect()

            for attendance in conn.live_capture():
                if attendance is None:
                    flush_queue(config)
                    continue
                try:
                    payload = attendance_to_payload(config, attendance, "LIVE_CAPTURE")
                except ValueError:
                    LOGGER.exception("Skipping malformed live attendance record")
                    continue
                deliver_payload(config, payload)
        except KeyboardInterrupt:
            LOGGER.info("Stopping agent")
            return
        except Exception:
            LOGGER.exception("Live capture loop failed; reconnecting in %s seconds", config.reconnect_seconds)
            time.sleep(config.reconnect_seconds)
        finally:
            disconnect_quietly(conn)


def disconnect_quietly(conn: Any) -> None:
    if conn is None:
        return
    try:
        conn.disconnect()
    except Exception:
        LOGGER.debug("Ignoring disconnect error", exc_info=True)


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="GL&R showroom SC700 attendance agent")
    parser.add_argument("--check", action="store_true", help="test TCP and pyzk connectivity to the SC700")
    parser.add_argument("--once-catchup", action="store_true", help="pull existing attendance logs once and exit")
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
        pyzk_ok = pyzk_check(config) if tcp_ok else False
        return 0 if tcp_ok and pyzk_ok else 2

    if args.once_catchup:
        run_catchup(config)
        return 0

    run_live(config)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
