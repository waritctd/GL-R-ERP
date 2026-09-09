#!/usr/bin/env python3
"""Load the showroom roster (PIN + name + card number) onto the new SenseFace 3A.

    *** THIS SCRIPT WRITES TO THE DEVICE. Dry-run is the default. ***
    Nothing is written unless you pass --commit.

Goal: get CARDS working tonight. This does NOT need the ERP -- the device stores
punches locally (150,000 records), so once the users and card numbers are on it,
staff can tap tomorrow morning and we sync the punches later.

Run the probe FIRST (probe_senseface.py). If it cannot connect, this cannot
either -- enrol on the device's own screen instead (see MANUAL_ENROLMENT.md).

RECOMMENDED SEQUENCE -- do not load all 18 and hope:

  1. Dry run, see what would happen:
       python3 load_users.py --roster showroom_roster.json

  2. Load ONE person, then have them tap their real card at the device:
       python3 load_users.py --roster showroom_roster.json --only 10098 --commit

  3. Only if that tap is ACCEPTED, load the rest:
       python3 load_users.py --roster showroom_roster.json --commit

Step 2 exists because of a real risk: the number stored in the old export is
whatever the OLD reader reported. The new reader may present the same physical
card differently (Wiegand 26 vs 34, byte order, facility-code handling). If the
stored number and the read number disagree, the card is simply not recognised --
and you would only find that out at 8am with 18 people queueing. One card, one
tap, then commit the rest.

If the tap FAILS, enrol by TAPPING the physical cards on the device instead of
typing numbers -- that sidesteps the format question entirely, because the device
then stores exactly what its own reader produces.
"""

from __future__ import annotations

import argparse
import json
import sys

# The device name field is a fixed-width buffer (24 bytes on the standalone
# protocol). Thai is 3 bytes per character in UTF-8, so only ~8 Thai characters
# fit. We truncate on a character boundary rather than let the device store a
# torn multi-byte sequence and render mojibake.
NAME_BYTES = 24


def fit_name(name: str, limit: int = NAME_BYTES) -> str:
    out = ""
    for ch in name:
        if len((out + ch).encode("utf-8")) > limit:
            break
        out += ch
    return out


def load_roster(path: str, include_unnamed: bool) -> list[dict]:
    try:
        people = json.load(open(path, encoding="utf-8"))
    except FileNotFoundError:
        raise SystemExit(
            f"Roster not found: {path}\n"
            "It is deliberately NOT in git -- it holds real names and card serial\n"
            "numbers, and this repo is public. Copy showroom_roster.json onto this\n"
            "machine and pass --roster <path>.")
    keep = []
    for p in people:
        # 11102025 "ploy" is a test enrolment from the old device, not a person.
        if p["pin"] == "11102025":
            continue
        # The 14 nameless PINs are the WAREHOUSE roster -- they punch on the
        # ZMM220, not here. Loading them onto the showroom device is noise.
        if not p["name"] and not include_unnamed:
            continue
        keep.append(p)
    return keep


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--roster", default="showroom_roster.json")
    ap.add_argument("--host", default="192.168.1.202")
    ap.add_argument("--port", type=int, default=4370)
    ap.add_argument("--password", type=int, default=0, help="comm key")
    ap.add_argument("--timeout", type=int, default=10)
    ap.add_argument("--only", help="load just this one PIN (use for the test card)")
    ap.add_argument("--include-unnamed", action="store_true",
                    help="also load the 14 nameless PINs (the warehouse roster)")
    ap.add_argument("--no-names", action="store_true",
                    help="write PIN only, no name (use if Thai names error out)")
    ap.add_argument("--full-name", action="store_true",
                    help="try to write 'first last' (usually truncates; default is "
                         "first name only, which is what staff recognise anyway)")
    ap.add_argument("--commit", action="store_true",
                    help="ACTUALLY WRITE to the device (default is dry-run)")
    args = ap.parse_args(argv)

    people = load_roster(args.roster, args.include_unnamed)
    if args.only:
        people = [p for p in people if p["pin"] == args.only]
        if not people:
            print(f"PIN {args.only} not in roster.", file=sys.stderr)
            return 2

    print(f"{'WRITE' if args.commit else 'DRY RUN'}: {len(people)} user(s) -> "
          f"{args.host}:{args.port}\n")
    plan = []
    for i, p in enumerate(people, start=1):
        # 24 bytes is ~8 Thai characters, so "first last" almost always tears.
        # First name alone is what staff actually recognise on the screen.
        wanted = p["name"] if args.full_name else (p["first_name"] or p["name"])
        name = "" if args.no_names else fit_name(wanted)
        card = int(p["card_no"]) if p["card_no"].isdigit() else 0
        trunc = " (TRUNCATED)" if name and name != wanted else ""
        print(f"  uid={i:<4} pin={p['pin']:<10} card={card or '-':<12} "
              f"name={name!r}{trunc}")
        plan.append((i, p["pin"], name, card))

    if not args.commit:
        print("\nDry run only -- nothing written. Add --commit to write.")
        print("Load ONE person first (--only <PIN>) and test the tap before the rest.")
        return 0

    try:
        from zk import ZK
    except ImportError:
        print("\npyzk not installed. Run: pip install pyzk", file=sys.stderr)
        return 1

    zk = ZK(args.host, port=args.port, timeout=args.timeout,
            password=args.password, ommit_ping=True)
    conn = zk.connect()
    print("\nconnected.")
    disabled = False
    try:
        # ZK devices want writes done while the UI is locked out; best-effort,
        # since not every firmware implements it.
        try:
            conn.disable_device()
            disabled = True
        except Exception as exc:
            print(f"  (disable_device unsupported: {type(exc).__name__} -- continuing)")

        ok = failed = 0
        for uid, pin, name, card in plan:
            try:
                conn.set_user(uid=uid, name=name, privilege=0, password="",
                              group_id="", user_id=pin, card=card)
                print(f"  OK   pin={pin} card={card or '-'}")
                ok += 1
            except Exception as exc:
                print(f"  FAIL pin={pin}: {type(exc).__name__}: {exc}")
                failed += 1
        print(f"\nwritten ok={ok} failed={failed}")

        # Read back -- never trust the write, confirm the device agrees.
        try:
            users = conn.get_users() or []
            got = {str(getattr(u, "user_id", "")): getattr(u, "card", None)
                   for u in users}
            print(f"read-back: {len(users)} user(s) now on device")
            for _, pin, _, card in plan:
                on = got.get(pin)
                state = "present" if pin in got else "MISSING"
                note = ""
                if pin in got and card and str(on) != str(card):
                    note = f"  <-- card mismatch: wrote {card}, device reports {on}"
                print(f"  {pin}: {state}{note}")
        except Exception as exc:
            print(f"read-back failed: {type(exc).__name__}: {exc}")
    finally:
        if disabled:
            try:
                conn.enable_device()
            except Exception:
                print("  WARNING: could not re-enable the device -- if the screen is "
                      "unresponsive, reboot it from its power supply.")
        conn.disconnect()
        print("disconnected.")

    print("\nNOW: have one of these people tap their real card at the device.")
    print("If it is rejected, the stored number does not match what this reader")
    print("produces -- re-enrol by TAPPING the cards on the device screen instead.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
