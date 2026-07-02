#!/usr/bin/env python3
"""Minimal ZKTeco SC700 test over the Pull SDK (plcommpro.dll).

The SC700 is a Pull-protocol access panel, so pyzk (standalone SDK) cannot talk
to it. This script calls plcommpro.dll directly via ctypes -- the exact same SDK
ZKAccess3.5 uses -- which is model-agnostic and does not need the SC700 to be a
formally supported pyzkaccess model.

Windows only. plcommpro.dll is 32-bit, so run this with 32-bit Python.

It does not touch the ERP backend and never clears device data.

Examples (PowerShell, from the repo root):

    # 1. Prove the SDK loads and the device answers.
    py -3-32 agents\\attendance\\sc700_pull_test.py --check

    # 2. Remotely open a door to generate a realtime event (no card tap needed),
    #    then watch it arrive. Run --poll in one window, --open in another,
    #    OR use --open-then-poll to do both in one process.
    py -3-32 agents\\attendance\\sc700_pull_test.py --open-then-poll --door 1

    # 3. Just watch the realtime log (tap a card / open a door elsewhere).
    py -3-32 agents\\attendance\\sc700_pull_test.py --poll

    # 4. Dump historical punches already stored on the device.
    py -3-32 agents\\attendance\\sc700_pull_test.py --pull --limit 20
"""

from __future__ import annotations

import argparse
import ctypes
import os
import sys
import time
from ctypes import c_char_p, c_int, c_void_p

DEFAULT_SDK_DIR = r"C:\Program Files (x86)\ZKTeco\ZKAccess3.5"
BUFFER_SIZE = 256 * 1024


def load_sdk(sdk_dir: str) -> ctypes.WinDLL:
    """Load plcommpro.dll and let Windows find its sibling DLLs in sdk_dir.

    We do NOT copy the DLL out of the ZKAccess folder: plcommpro.dll depends on
    several other DLLs that live next to it, so we add that directory to the DLL
    search path instead. Copying only plcommpro.dll would fail at load time.
    """
    if os.name != "nt":
        raise RuntimeError("The Pull SDK (plcommpro.dll) is Windows-only.")

    dll_path = os.path.join(sdk_dir, "plcommpro.dll")
    if not os.path.exists(dll_path):
        raise RuntimeError(
            f"plcommpro.dll not found in {sdk_dir!r}. "
            "Pass the real ZKAccess3.5 install folder with --sdk-dir."
        )

    # Python 3.8+: make the sibling DLLs discoverable without copying anything.
    if hasattr(os, "add_dll_directory"):
        os.add_dll_directory(sdk_dir)
    os.environ["PATH"] = sdk_dir + os.pathsep + os.environ.get("PATH", "")

    try:
        dll = ctypes.WinDLL(dll_path)
    except OSError as exc:  # almost always the 32/64-bit mismatch
        raise RuntimeError(
            f"Failed to load plcommpro.dll ({exc}). "
            "This DLL is 32-bit -- run with 32-bit Python (py -3-32)."
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
    dll.GetDeviceDataCount.restype = c_int
    dll.GetDeviceDataCount.argtypes = [c_void_p, c_char_p, c_char_p, c_char_p]
    dll.ControlDevice.restype = c_int
    dll.ControlDevice.argtypes = [
        c_void_p, c_int, c_int, c_int, c_int, c_int, c_char_p
    ]
    return dll


def connect(dll: ctypes.WinDLL, host: str, port: int, password: str,
            timeout_ms: int) -> c_void_p:
    conn_str = (
        f"protocol=TCP,ipaddress={host},port={port},"
        f"timeout={timeout_ms},passwd={password}"
    ).encode("ascii")
    print(f"Connecting: protocol=TCP,ipaddress={host},port={port} "
          f"passwd={'***' if password else '<none>'}")
    handle = dll.Connect(conn_str)
    if not handle:
        err = dll.PullLastError()
        raise RuntimeError(
            f"Connect failed (PullLastError={err}). "
            "Check IP/port reachability, comm password, and that ZKAccess3.5 "
            "is CLOSED (it holds an exclusive session)."
        )
    print(f"Connected. handle={handle}")
    return handle


def open_door(dll: ctypes.WinDLL, handle: c_void_p, door: int,
              duration: int) -> None:
    """ControlDevice output operation -> pulses the door lock.

    operationid=1 (output), param1=door number, param2=1 (door/lock address),
    param3=duration seconds (1-254), param4=0.
    """
    print(f"Opening door {door} for {duration}s (remote unlock)...")
    rc = dll.ControlDevice(handle, 1, door, 1, duration, 0, b"")
    if rc < 0:
        print(f"  ControlDevice returned {rc} (PullLastError={dll.PullLastError()})")
    else:
        print("  Command accepted -- this should produce a realtime event.")


def poll_rtlog(dll: ctypes.WinDLL, handle: c_void_p, seconds: float,
               interval: float) -> None:
    print(f"Polling GetRTLog every {interval}s for {seconds}s. "
          "Tap a card or open a door to see events.\n")
    buf = ctypes.create_string_buffer(BUFFER_SIZE)
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        count = dll.GetRTLog(handle, buf, BUFFER_SIZE)
        if count < 0:
            print(f"  GetRTLog error {count} (PullLastError={dll.PullLastError()})")
        elif count > 0:
            raw = buf.value.decode("ascii", errors="replace")
            for line in raw.splitlines():
                line = line.strip()
                if line:
                    print(f"  [{time.strftime('%H:%M:%S')}] RTLOG: {line}")
        time.sleep(interval)
    print("\nDone polling.")


# Candidate names for the stored access/attendance log across ZKTeco device
# families. C3/access panels use "transaction"; standalone terminals often
# use "AttLog"/"attlog". We probe each so we don't have to guess.
CANDIDATE_TABLES = ["transaction", "AttLog", "attlog", "TransactionLog", "rtlog"]


def pull_transactions(dll: ctypes.WinDLL, handle: c_void_p, limit: int) -> None:
    print("Probing which stored-log table this device serves over Pull SDK...\n")

    # 1. Which tables answer a count query at all?
    servable = []
    for table in CANDIDATE_TABLES:
        count = dll.GetDeviceDataCount(handle, table.encode(), b"", b"")
        if count >= 0:
            print(f"  table {table!r:16} -> count = {count}")
            servable.append((table, count))
        else:
            print(f"  table {table!r:16} -> not served (rc={count}, "
                  f"PullLastError={dll.PullLastError()})")

    if not servable:
        print("\nNo stored-log table is served over Pull SDK on this device. "
              "We'll map fields from a live tap / GetRTLog instead.")
        return

    # 2. Dump rows from the first table that actually has records.
    target = next((t for t, c in servable if c > 0), servable[0][0])
    print(f"\nReading rows from {target!r} ...\n")
    buf = ctypes.create_string_buffer(BUFFER_SIZE)
    for fields in (b"*", b"Cardno,Pin,Verified,DoorID,EventType,InOutState,Time_second"):
        rc = dll.GetDeviceData(handle, buf, BUFFER_SIZE, target.encode(),
                               fields, b"", b"")
        if rc >= 0:
            lines = buf.value.decode("ascii", errors="replace").splitlines()
            if lines:
                print(f"Header: {lines[0]}")
            for line in lines[1:limit + 1]:
                if line.strip():
                    print(f"  {line}")
            print(f"\nShown up to {limit} of {max(len(lines) - 1, 0)} records "
                  f"(fields={fields.decode()}).")
            return
        print(f"  GetDeviceData(fields={fields.decode()!r}) error {rc} "
              f"(PullLastError={dll.PullLastError()})")
    print("\nCould not read rows even though the table reported a count.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default=os.getenv("ZK_HOST", "192.168.1.201"))
    parser.add_argument("--port", type=int, default=int(os.getenv("ZK_PORT", "4370")))
    parser.add_argument("--password", default=os.getenv("ZK_COMM_PASSWORD", ""))
    parser.add_argument("--timeout-ms", type=int, default=4000)
    parser.add_argument("--sdk-dir", default=os.getenv("ZK_SDK_DIR", DEFAULT_SDK_DIR))
    parser.add_argument("--door", type=int, default=1, help="door number for --open")
    parser.add_argument("--open-duration", type=int, default=5)
    parser.add_argument("--poll-seconds", type=float, default=30.0)
    parser.add_argument("--poll-interval", type=float, default=1.0)
    parser.add_argument("--limit", type=int, default=20)

    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true", help="connect only")
    mode.add_argument("--open", action="store_true", help="remotely open a door")
    mode.add_argument("--poll", action="store_true", help="watch realtime log")
    mode.add_argument("--open-then-poll", action="store_true",
                      help="open a door, then watch the event arrive")
    mode.add_argument("--pull", action="store_true", help="dump stored punches")
    args = parser.parse_args()

    try:
        dll = load_sdk(args.sdk_dir)
    except RuntimeError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2

    handle = None
    try:
        handle = connect(dll, args.host, args.port, args.password, args.timeout_ms)

        if args.check:
            print("OK: SDK loaded and device answered.")
        elif args.open:
            open_door(dll, handle, args.door, args.open_duration)
        elif args.poll:
            poll_rtlog(dll, handle, args.poll_seconds, args.poll_interval)
        elif args.open_then_poll:
            open_door(dll, handle, args.door, args.open_duration)
            poll_rtlog(dll, handle, args.poll_seconds, args.poll_interval)
        elif args.pull:
            pull_transactions(dll, handle, args.limit)
        return 0
    except RuntimeError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    finally:
        if handle:
            dll.Disconnect(handle)
            print("Disconnected.")


if __name__ == "__main__":
    raise SystemExit(main())
