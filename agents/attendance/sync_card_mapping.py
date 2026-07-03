#!/usr/bin/env python3
"""Backfill employee card numbers from the SC700's enrolled-user table.

Historical card taps are logged by the device with the raw *card serial* (Pin=0,
CardNo=<serial>), so those punches don't map to an employee until each employee's
card serial is recorded in hr.employee.badge_card_no. The device's ``user`` table
holds the link: User ID (Pin) -> CardNo -> Name.

This script reads that table over the Pull SDK and POSTs the
``{employee_code=Pin, card_no=CardNo}`` pairs to the GL&R backend, which updates
badge_card_no by matching employee_code. Once set, the attendance history resolves
those card punches to the right employee automatically.

Windows + 32-bit Python only (plcommpro.dll). Run it with ZKAccess closed and the
attendance service paused -- the SC700 allows only one Pull-SDK session:

    .\\pause-for-zkaccess.ps1
    py -3-32 sync_card_mapping.py --api-base-url https://gl-r-erp.onrender.com
    .\\resume-agent.ps1

Use --dry-run first to review the mapping without changing anything.
"""

from __future__ import annotations

import argparse
import getpass
import json
import os
import sys
from typing import Any

import requests

from showroom_agent import DEFAULT_SDK_DIR, PullSDK


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    # Device (Pull SDK)
    parser.add_argument("--host", default=os.getenv("ZK_HOST", "192.168.1.202"))
    parser.add_argument("--port", type=int, default=int(os.getenv("ZK_PORT", "4370")))
    parser.add_argument("--password", default=os.getenv("ZK_COMM_PASSWORD", ""))
    parser.add_argument("--sdk-dir", default=os.getenv("ZK_SDK_DIR", DEFAULT_SDK_DIR))
    parser.add_argument("--timeout-ms", type=int, default=int(os.getenv("ZK_CONNECT_TIMEOUT_MS", "4000")))
    # Backend API (mirrors import_dat.py)
    parser.add_argument("--api-base-url", default=os.getenv("GLR_API_BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--email", default=os.getenv("GLR_IMPORT_EMAIL"), help="HR/C-level email; defaults to GLR_IMPORT_EMAIL")
    parser.add_argument("--api-password", default=os.getenv("GLR_IMPORT_PASSWORD"), help="HR/C-level password; defaults to GLR_IMPORT_PASSWORD")
    parser.add_argument("--dry-run", action="store_true", help="pull and print the mapping without POSTing")
    return parser.parse_args(argv)


def column_index(header_fields: list[str], *names: str) -> int:
    lowered = [f.strip().lower() for f in header_fields]
    for name in names:
        if name.lower() in lowered:
            return lowered.index(name.lower())
    raise SystemExit(
        f"Could not find any of {names} in the device user header: {header_fields}"
    )


def build_mappings(header: str | None, rows: list[str]) -> list[dict[str, str]]:
    if not header:
        raise SystemExit("Device returned no user table header; nothing to sync.")
    fields = header.split(",")
    pin_idx = column_index(fields, "Pin")
    card_idx = column_index(fields, "CardNo", "Card")

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


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])

    sdk = PullSDK(args.sdk_dir)
    try:
        sdk.connect(args.host, args.port, args.password, args.timeout_ms)
        header, rows = sdk.get_user_rows()
    finally:
        sdk.disconnect()

    mappings = build_mappings(header, rows)
    print(f"Read {len(rows)} device users; {len(mappings)} carry a card number.")

    if args.dry_run:
        print(json.dumps({"mappings": mappings}, indent=2, ensure_ascii=False))
        return 0
    if not mappings:
        print("No card numbers to backfill; nothing to do.")
        return 0

    api_base_url = args.api_base_url.rstrip("/")
    email = args.email or input("HR email: ").strip()
    password = args.api_password or getpass.getpass("HR password / employee code: ")
    if not email or not password:
        print("Email and password are required.", file=sys.stderr)
        return 2

    session = requests.Session()
    login = post_json(session, f"{api_base_url}/api/auth/login", {"email": email, "password": password})
    user = login.get("user", {})
    print(f"Logged in as {user.get('name') or user.get('email') or email} ({user.get('role')})")

    csrf = csrf_cookie(session)
    if not csrf:
        session.get(f"{api_base_url}/api/auth/me", timeout=30)
        csrf = csrf_cookie(session)
    if not csrf:
        print("Backend did not issue XSRF-TOKEN cookie; cannot submit safely.", file=sys.stderr)
        return 1

    response = post_json(
        session,
        f"{api_base_url}/api/attendance/cards/backfill",
        {"mappings": mappings},
        headers={"X-XSRF-TOKEN": csrf},
    )
    print(json.dumps(response, indent=2, sort_keys=True))
    return 0


def csrf_cookie(session: requests.Session) -> str | None:
    value = session.cookies.get("XSRF-TOKEN")
    return value if value and value.strip() else None


def post_json(
        session: requests.Session,
        url: str,
        payload: dict[str, Any],
        headers: dict[str, str] | None = None) -> dict[str, Any]:
    request_headers = {"Content-Type": "application/json"}
    if headers:
        request_headers.update(headers)
    response = session.post(url, data=json.dumps(payload), headers=request_headers, timeout=60)
    if not response.ok:
        message = response.text
        try:
            message = response.json().get("message") or message
        except ValueError:
            pass
        raise SystemExit(f"POST {url} failed with HTTP {response.status_code}: {message}")
    try:
        return response.json()
    except ValueError as exc:
        raise SystemExit(f"POST {url} returned non-JSON response") from exc


if __name__ == "__main__":
    raise SystemExit(main())
