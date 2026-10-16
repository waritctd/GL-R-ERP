#!/usr/bin/env python3
"""GL&R ZKBio CVAccess attendance agent (read-only poller).

The showroom's ZKTeco SenseFace 3A sends punches only to ZKBio CVAccess, which
stores them in its bundled PostgreSQL table ``acc_transaction``. This agent reads
new rows from that table (never writes) and posts each employee punch to the
ERP's ``POST /api/attendance/punch`` via the delivery/queue code shared with
``showroom_agent.py``. The ERP dedups a registered device's punch on
(device_id, badge_code, punch_time) with ON CONFLICT DO NOTHING, so a re-sent
punch is harmless.

The cursor is CVAccess's *insertion order* ``(create_time, id)``, not punch time:
a device that was offline uploads old punches later, and a punch-time cursor
would skip them.

    python3 cvaccess_agent.py --check               # confirm schema, list event_no values
    python3 cvaccess_agent.py --once --since 2026-09-01   # backfill from a date
    python3 cvaccess_agent.py                       # live: poll every CVACCESS_POLL_SECONDS

Env: CVACCESS_DB_HOST (127.0.0.1), CVACCESS_DB_PORT (5442), CVACCESS_DB_NAME and
CVACCESS_DB_USER (required), CVACCESS_DB_PASSWORD, CVACCESS_POLL_SECONDS (3),
CVACCESS_PUNCH_EVENTS (comma list of event_no counted as punches, default "0").
Plus the ATTENDANCE_* variables read by showroom_agent.AgentConfig.
"""

from __future__ import annotations

import argparse
import contextlib
import logging
import os
import sys
import time
from dataclasses import dataclass
from datetime import datetime
from typing import Any
from zoneinfo import ZoneInfo

import pg8000.dbapi

from showroom_agent import (
    AgentConfig, Punch, blank_to_none, configure_logging, deliver_payload, env_int,
    flush_queue, load_state, punch_to_payload, save_state,
)

LOGGER = logging.getLogger("cvaccess_attendance_agent")

PAGE_SIZE = 500
STATE_KEY = "cvaccess_cursor"
REQUIRED_COLUMNS = ("id", "create_time", "event_time", "pin", "event_no")


@dataclass(frozen=True)
class Cursor:
    create_time: datetime
    id: Any


def punch_events() -> set[int]:
    return {int(v) for v in os.getenv("CVACCESS_PUNCH_EVENTS", "0").split(",") if v.strip()}


def db_settings() -> dict[str, Any]:
    for name in ("CVACCESS_DB_NAME", "CVACCESS_DB_USER"):
        if not blank_to_none(os.getenv(name)):
            raise SystemExit(f"{name} is required (CVAccess PostgreSQL database / user).")
    return {
        "host": os.getenv("CVACCESS_DB_HOST", "127.0.0.1").strip(),
        "port": env_int("CVACCESS_DB_PORT", 5442),
        "database": os.environ["CVACCESS_DB_NAME"].strip(),
        "user": os.environ["CVACCESS_DB_USER"].strip(),
        "password": blank_to_none(os.getenv("CVACCESS_DB_PASSWORD")),
        "timeout": 30,  # pg8000 has none by default; a stalled database would hang the agent
    }


def connect(settings: dict[str, Any]) -> pg8000.dbapi.Connection:
    conn = pg8000.dbapi.connect(**settings)
    conn.autocommit = True  # no transaction stays open against CVAccess
    conn.cursor().execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY")
    return conn


def load_cursor(config: AgentConfig) -> Cursor | None:
    saved = load_state(config).get(STATE_KEY)
    if not saved:
        return None
    return Cursor(datetime.fromisoformat(saved["create_time"]), saved["id"])


def save_cursor(config: AgentConfig, cursor: Cursor) -> None:
    state = load_state(config)
    state[STATE_KEY] = {"create_time": cursor.create_time.isoformat(), "id": cursor.id if isinstance(cursor.id, (int, str)) else str(cursor.id)}
    save_state(config, state)


def row_to_punch(config: AgentConfig, row: tuple, events: set[int]) -> Punch | None:
    _id, _created, event_time, pin, event_no = row
    pin = (pin or "").strip()
    if not (pin.isascii() and pin.isdigit()) or event_no not in events or event_time is None:
        return None
    zone = ZoneInfo(config.timezone)
    when = event_time.astimezone(zone) if event_time.tzinfo else event_time.replace(tzinfo=zone)
    return Punch(badge=pin, punch_time=when, cardno="", pin=pin, verified=0, doorid=0,
                 eventtype=event_no, inoutstate=0, source="cvaccess")


def newest_cursor(conn: Any) -> Cursor | None:
    cur = conn.cursor()
    cur.execute("SELECT create_time, id FROM acc_transaction WHERE create_time IS NOT NULL ORDER BY create_time DESC, id DESC LIMIT 1")
    row = cur.fetchone()
    return Cursor(row[0], row[1]) if row else None


def drain(conn: Any, config: AgentConfig, cursor: Cursor | None, floor: datetime | None,
          ingest_method: str, save: bool) -> Cursor | None:
    """Deliver every row after ``cursor``; return the new cursor. ``save`` persists it per row."""
    flush_queue(config)
    events = punch_events()
    while True:
        # ponytail: a row committed after we read past its create_time is skipped silently;
        # re-sweep with `--once --since <date>` (safe, the ERP dedups).
        keyset = "(create_time, id) > (%s, %s)" if cursor else "create_time IS NOT NULL"
        sql = (f"SELECT id, create_time, event_time, pin, event_no FROM acc_transaction "
               f"WHERE {keyset}{' AND event_time >= %s' if floor else ''} "
               f"ORDER BY create_time, id LIMIT %s")
        params = ([cursor.create_time, cursor.id] if cursor else []) + ([floor] if floor else []) + [PAGE_SIZE]
        cur = conn.cursor()
        cur.execute(sql, params)
        rows = cur.fetchall()
        for row in rows:
            punch = row_to_punch(config, row, events)
            if punch:
                deliver_payload(config, punch_to_payload(config, punch, ingest_method))
            cursor = Cursor(row[1], row[0])
            if save:
                save_cursor(config, cursor)
        if len(rows) < PAGE_SIZE:
            return cursor


def check(settings: dict[str, Any]) -> int:
    try:
        conn = connect(settings)
    except Exception as exc:
        print(f"connection failed: {exc}")
        return 2
    try:
        cur = conn.cursor()
        cur.execute("SELECT column_name, data_type FROM information_schema.columns "
                    "WHERE table_name = 'acc_transaction' AND table_schema = ANY (current_schemas(false)) "
                    "ORDER BY ordinal_position")
        typed = cur.fetchall()
        columns = [r[0] for r in typed]
        missing = [c for c in REQUIRED_COLUMNS if c not in columns]
        if missing:
            print(f"missing: {', '.join(missing)}")
            print(f"columns found: {', '.join(columns) or '(table not found)'}")
            return 2
        print("columns:")
        for name, data_type in typed:
            print(f"  {name} {data_type}")
        cur.execute("SELECT count(*) FROM acc_transaction WHERE create_time IS NULL")
        print(f"{cur.fetchone()[0]} rows have a NULL create_time (never delivered)")
        named = "event_name" in columns
        cur.execute(f"SELECT event_no{', event_name' if named else ''}, count(*) FROM acc_transaction "
                    "GROUP BY 1" + (", 2" if named else "") + " ORDER BY count(*) DESC")
        print("event_no counts (most frequent first):")
        for row in cur.fetchall():
            print("  " + "  ".join(str(v) for v in row))
        cur.execute("SELECT pin, event_time, create_time, event_no FROM acc_transaction "
                    "WHERE create_time IS NOT NULL ORDER BY create_time DESC LIMIT 5")
        print("5 newest rows (pin, event_time, create_time, event_no):")
        for row in cur.fetchall():
            print("  " + "  ".join(str(v) for v in row))
        events = punch_events()
        cur.execute("SELECT count(*) FROM acc_transaction WHERE event_no = ANY(%s)", [sorted(events)])
        print(f"CVACCESS_PUNCH_EVENTS={sorted(events)} matches {cur.fetchone()[0]} rows")
        return 0
    except (pg8000.dbapi.Error, OSError) as exc:
        print(f"query failed: {exc}")
        return 2
    finally:
        with contextlib.suppress(Exception):  # the connection is discarded either way
            conn.close()


def run(config: AgentConfig, settings: dict[str, Any], since: str | None, once: bool) -> int:
    try:
        return _run(config, settings, since, once)
    except KeyboardInterrupt:
        return 0


def _run(config: AgentConfig, settings: dict[str, Any], since: str | None, once: bool) -> int:
    poll = env_int("CVACCESS_POLL_SECONDS", 3)
    floor = datetime.strptime(since, "%Y-%m-%d") if since else None
    # a re-sweep (--since) must not move a position the service already saved; a first backfill hands it over
    save = not config.dry_run and (floor is None or load_cursor(config) is None)
    cursor: Cursor | None = None
    started = False
    first = True
    while True:
        conn = None
        try:
            conn = connect(settings)
            if not started:
                if floor:
                    cursor = None
                else:
                    cursor = load_cursor(config)
                    if cursor is None:
                        cursor = newest_cursor(conn)
                        LOGGER.info("No saved cursor: starting at the newest row, skipping history. "
                                    "Use --since YYYY-MM-DD to backfill.")
                        if cursor and not config.dry_run:
                            save_cursor(config, cursor)
                started = True
            while True:
                cursor = drain(conn, config, cursor, floor, "CATCHUP_PULL" if first else "LIVE_CAPTURE", save)
                first = False
                if once:
                    return 0
                time.sleep(poll)
        except Exception:
            LOGGER.exception("CVAccess poll failed")
            if once:
                return 1
            time.sleep(config.reconnect_seconds)
        finally:
            if conn is not None:
                with contextlib.suppress(Exception):  # a dropped server makes close() raise
                    conn.close()


def main(argv: list[str] | None = None) -> int:
    configure_logging()
    parser = argparse.ArgumentParser(description="GL&R ZKBio CVAccess attendance agent")
    parser.add_argument("--check", action="store_true", help="verify the CVAccess schema and event numbers")
    parser.add_argument("--once", action="store_true", help="drain once, then exit")
    parser.add_argument("--since", metavar="YYYY-MM-DD", help="backfill punches from this local date")
    parser.add_argument("--dry-run", action="store_true", help="log payloads, post nothing, keep the cursor")
    args = parser.parse_args(argv if argv is not None else sys.argv[1:])
    if args.since and not args.once:
        parser.error("--since needs --once: a service started with --since would re-send all history on every restart")
    settings = db_settings()
    if args.check:
        return check(settings)
    config = AgentConfig.from_env(dry_run_override=True if args.dry_run else None)
    return run(config, settings, args.since, args.once)


if __name__ == "__main__":
    raise SystemExit(main())
