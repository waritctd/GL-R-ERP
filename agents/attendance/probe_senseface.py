#!/usr/bin/env python3
"""Probe the new showroom SenseFace/SenseX 3A and report which integration path it supports.

READ-ONLY. This script never writes to the device: no set_user, no delete, no
clear, no door control. Safe to run on the live scanner.

Why this exists
---------------
The old showroom scanner was an SC700 -- a Pull-SDK access panel (plcommpro.dll).
The replacement is a SenseFace 3 series terminal: a Linux, *push-protocol* device
(ZKTeco datasheet: "AC Push and TA Push protocol switch", ADMS listed as a
standard function). Nothing about the SC700 integration carries over, and the
warehouse's pyzk path is only *proven* on old ZEM-platform firmware (6.60/6.70).

The specific trap this probe is built to catch: on the same Linux platform
(SpeedFace V5L, pyzk issue #126) connect() succeeds and get_users() succeeds
while get_attendance() silently returns an EMPTY LIST. An agent built on that
would look healthy and deliver zero punches forever. So stage 3 below is the
stage that actually decides whether the pyzk transport is usable.

Usage (on the office server / any machine on the 192.168.1.0/24 LAN):

    python3 probe_senseface.py --host 192.168.1.202

    # deeper stages need pyzk:
    pip install pyzk
    python3 probe_senseface.py --host 192.168.1.202 --password 0

IMPORTANT before running: make at least one real punch on the device (any
person, face or card) within the last few minutes. Stage 3 cannot tell
"attendance reads are broken" apart from "nobody has punched yet" unless there
is a punch on the device to find.
"""

from __future__ import annotations

import argparse
import socket
import sys
import traceback

# Ports worth knowing about on a SenseFace 3.
#   4370 - ZKTeco standalone SDK (what pyzk speaks). Its presence is necessary
#          but NOT sufficient -- see stage 3.
#   80/443 - device web/backend ("HTTPs / SSH Backend Access" is a listed feature)
#   8080 - alternate http
#   22   - ssh backend
PORTS = [(4370, "ZKTeco standalone SDK (pyzk)"),
         (80, "HTTP device backend"),
         (443, "HTTPS device backend"),
         (8080, "HTTP alt"),
         (22, "SSH backend")]


def hr(title: str) -> None:
    print("\n" + "=" * 68)
    print(title)
    print("=" * 68)


def check_port(host: str, port: int, timeout: float = 3.0) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(timeout)
        try:
            return s.connect_ex((host, port)) == 0
        except OSError:
            return False


def stage0_ports(host: str) -> bool:
    hr("STAGE 0 - TCP reachability (no dependencies)")
    open_ports = []
    for port, label in PORTS:
        ok = check_port(host, port)
        print(f"  {host}:{port:<5} {'OPEN  ' if ok else 'closed'}  {label}")
        if ok:
            open_ports.append(port)
    if not open_ports:
        print("\n  !! Nothing open. Wrong IP, wrong VLAN, or a firewall in between.")
        print("     Confirm the device screen still shows this IP and that this")
        print("     machine is on the same 192.168.1.0/24 subnet.")
    return 4370 in open_ports


def stage1_connect(host: str, port: int, password: int, timeout: int,
                   force_udp: bool, omit_ping: bool):
    hr("STAGE 1 - standalone SDK handshake (pyzk)")
    try:
        from zk import ZK
    except ImportError:
        print("  pyzk not installed -- stages 1-4 skipped.")
        print("  Install it and re-run:  pip install pyzk")
        return None
    zk = ZK(host, port=port, timeout=timeout, password=password,
            force_udp=force_udp, ommit_ping=omit_ping)  # pyzk spells it "ommit_ping"
    try:
        conn = zk.connect()
    except Exception as exc:
        print(f"  CONNECT FAILED: {type(exc).__name__}: {exc}")
        print("\n  If port 4370 was OPEN in stage 0 but this fails, the most likely")
        print("  causes are a non-zero comm key (try --password <key>) or the")
        print("  device serving push/ADMS only. Try --force-udp as well.")
        return None
    print("  CONNECT OK")
    for label, fn in (("device name ", "get_device_name"),
                      ("firmware    ", "get_firmware_version"),
                      ("serial no   ", "get_serialnumber"),
                      ("platform    ", "get_platform"),
                      ("mac         ", "get_mac"),
                      ("device time ", "get_time")):
        try:
            print(f"  {label}: {getattr(conn, fn)()}")
        except Exception as exc:
            print(f"  {label}: <unsupported: {type(exc).__name__}>")
    return conn


def stage2_users(conn) -> int:
    hr("STAGE 2 - read enrolled users  (get_users)")
    try:
        users = conn.get_users() or []
    except Exception as exc:
        print(f"  FAILED: {type(exc).__name__}: {exc}")
        return -1
    print(f"  users on device: {len(users)}")
    for u in users[:40]:
        print(f"    uid={getattr(u,'uid','?'):<6} pin={getattr(u,'user_id','?'):<12} "
              f"card={getattr(u,'card','?'):<12} name={getattr(u,'name','')!r}")
    if len(users) > 40:
        print(f"    ... and {len(users)-40} more")
    if not users:
        print("  (empty -- expected on a factory-fresh device)")
    return len(users)


def stage3_attendance(conn) -> int:
    hr("STAGE 3 - read punches  (get_attendance)  <-- THE DECIDING TEST")
    try:
        att = conn.get_attendance() or []
    except Exception as exc:
        print(f"  FAILED: {type(exc).__name__}: {exc}")
        print("\n  VERDICT: pyzk CANNOT read punches from this device.")
        print("  Do not build the showroom agent on the pyzk transport.")
        return -1
    print(f"  punch records returned: {len(att)}")
    for a in att[:10]:
        print(f"    {a}")
    if len(att) > 10:
        print(f"    ... and {len(att)-10} more")
    print()
    if att:
        print("  VERDICT: pyzk CAN read punches. The existing agent has a real")
        print("           chance of working with ZK_TRANSPORT=pyzk.")
    else:
        print("  VERDICT: AMBIGUOUS -- and this is the dangerous case.")
        print("    If someone HAS punched on this device already, then this is the")
        print("    SpeedFace-V5L failure mode (pyzk issue #126): connect and")
        print("    get_users work, get_attendance silently returns nothing. An")
        print("    agent built on it would look healthy and deliver zero punches.")
        print("    -> Go the TA push / ADMS route instead.")
        print("    If NOBODY has punched yet: punch once on the device, re-run.")
    return len(att)


def stage4_live(conn, seconds: int) -> None:
    hr(f"STAGE 4 - realtime capture  (live_capture, {seconds}s)")
    print("  Punch on the device NOW (face or card)...")
    import itertools
    import time
    deadline = time.time() + seconds
    seen = 0
    try:
        for att in conn.live_capture():
            if att is not None:
                seen += 1
                print(f"    LIVE PUNCH: {att}")
            if time.time() > deadline:
                break
    except Exception as exc:
        print(f"  FAILED: {type(exc).__name__}: {exc}")
    finally:
        try:
            conn.end_live_capture = True
        except Exception:
            pass
    print(f"  live punches seen: {seen}")
    if seen == 0:
        print("  (no punch seen -- either nobody punched, or realtime is unsupported)")


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--host", default="192.168.1.202")
    p.add_argument("--port", type=int, default=4370)
    p.add_argument("--password", type=int, default=0,
                   help="comm key (old SC700 used 1; a new device is usually 0)")
    p.add_argument("--timeout", type=int, default=10)
    p.add_argument("--force-udp", action="store_true")
    p.add_argument("--omit-ping", action="store_true", default=True)
    p.add_argument("--live-seconds", type=int, default=0,
                   help="also run stage 4 realtime capture for N seconds")
    args = p.parse_args(argv)

    print(f"Probing {args.host}:{args.port} (comm key {args.password}) -- READ ONLY")

    sdk_port_open = stage0_ports(args.host)
    if not sdk_port_open:
        hr("RESULT")
        print("  Port 4370 is CLOSED. The standalone SDK path (and therefore the")
        print("  existing pyzk agent) is not available on this device as configured.")
        print("  -> The integration must be TA push / ADMS: the device dials OUT to")
        print("     a server you configure under COMM. > Cloud Server Setting.")
        return 0

    conn = stage1_connect(args.host, args.port, args.password, args.timeout,
                          args.force_udp, args.omit_ping)
    if conn is None:
        return 1
    try:
        stage2_users(conn)
        stage3_attendance(conn)
        if args.live_seconds:
            stage4_live(conn, args.live_seconds)
    finally:
        try:
            conn.disconnect()
            print("\ndisconnected cleanly.")
        except Exception:
            traceback.print_exc()
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
