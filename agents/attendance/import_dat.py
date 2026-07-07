#!/usr/bin/env python3
"""Import a ZKTeco SC700 .dat export through the GL&R backend API."""

from __future__ import annotations

import argparse
import getpass
import json
import os
import sys
from pathlib import Path
from typing import Any

import requests


DEFAULT_API_BASE_URL = "http://127.0.0.1:8080"
DEFAULT_SITE_CODE = "SHOWROOM"
DEFAULT_DEVICE_CODE = "SHOWROOM_SC700"


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Import a ZKTeco SC700 .dat file via the GL&R backend")
    parser.add_argument("file", type=Path, help="Path to the SC700 .dat file")
    parser.add_argument("--api-base-url", default=os.getenv("GLR_API_BASE_URL", DEFAULT_API_BASE_URL))
    parser.add_argument("--email", default=os.getenv("GLR_IMPORT_EMAIL"), help="HR user email; defaults to GLR_IMPORT_EMAIL")
    parser.add_argument("--password", default=os.getenv("GLR_IMPORT_PASSWORD"), help="HR password; defaults to GLR_IMPORT_PASSWORD")
    parser.add_argument("--site-code", default=os.getenv("ATTENDANCE_SITE_CODE", DEFAULT_SITE_CODE))
    parser.add_argument("--device-code", default=os.getenv("ATTENDANCE_DEVICE_CODE", DEFAULT_DEVICE_CODE))
    parser.add_argument("--timeout", type=int, default=int(os.getenv("GLR_IMPORT_TIMEOUT", "180")),
                        help="HTTP read timeout in seconds (default 180; raise for large files / cold starts)")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    api_base_url = args.api_base_url.rstrip("/")
    email = args.email or input("HR email: ").strip()
    password = args.password or getpass.getpass("HR password / employee code: ")

    if not email or not password:
        print("Email and password are required.", file=sys.stderr)
        return 2
    if not args.file.exists():
        print(f"File not found: {args.file}", file=sys.stderr)
        return 2

    content = args.file.read_text(encoding="utf-8-sig")
    session = requests.Session()

    login_response = post_json(session, f"{api_base_url}/api/auth/login", {
        "email": email,
        "password": password,
    })
    user = login_response.get("user", {})
    role = user.get("role")
    name = user.get("name") or user.get("email") or email
    print(f"Logged in as {name} ({role})")

    csrf_token = csrf_cookie(session)
    if not csrf_token:
        # Login should issue it, but this keeps the script robust if middleware changes.
        session.get(f"{api_base_url}/api/auth/me", timeout=30)
        csrf_token = csrf_cookie(session)
    if not csrf_token:
        print("Backend did not issue XSRF-TOKEN cookie; cannot submit import safely.", file=sys.stderr)
        return 1

    payload = {
        "site_code": args.site_code.strip().upper(),
        "device_code": args.device_code.strip().upper(),
        "file_name": args.file.name,
        "content": content,
    }
    import_response = post_json(
        session,
        f"{api_base_url}/api/attendance/imports/dat",
        payload,
        headers={"X-XSRF-TOKEN": csrf_token},
        timeout=args.timeout,
    )

    print(json.dumps(import_response, indent=2, sort_keys=True))
    return 0


def csrf_cookie(session: requests.Session) -> str | None:
    value = session.cookies.get("XSRF-TOKEN")
    return value if value and value.strip() else None


def post_json(
        session: requests.Session,
        url: str,
        payload: dict[str, Any],
        headers: dict[str, str] | None = None,
        timeout: int = 60) -> dict[str, Any]:
    request_headers = {"Content-Type": "application/json"}
    if headers:
        request_headers.update(headers)
    response = session.post(url, data=json.dumps(payload), headers=request_headers, timeout=timeout)
    if not response.ok:
        message = response.text
        try:
            parsed = response.json()
            message = parsed.get("message") or message
        except ValueError:
            pass
        raise SystemExit(f"POST {url} failed with HTTP {response.status_code}: {message}")
    try:
        return response.json()
    except ValueError as exc:
        raise SystemExit(f"POST {url} returned non-JSON response") from exc


if __name__ == "__main__":
    raise SystemExit(main())
