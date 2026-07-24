# Agent Handoff

## Task
The warehouse ZKTeco scanner (a ZMM220_TFT board / firmware Ver 6.60 Apr 2017,
badged "SC700") could not be brought online: `showroom_agent.py --check` failed
every time with `Pull SDK Connect failed (PullLastError=-2)`, even though TCP
reachability, comm key (`1`), and env were all confirmed correct. Field testing
proved the device works over **pyzk's standalone ZK protocol** but **not** over
the ZKAccess3.5 Pull SDK (`plcommpro.dll`) that the agent uses. The showroom
SC700 is the opposite — Pull-SDK-only. Task: make the attendance agent (and its
companion backfill/card-sync tools) work with **both** transports, one per site.

## Branch
`feat/attendance-pyzk-transport` (worktree: `.claude/worktrees/pyzk-transport`)

## Base Commit
`7756243` (origin/main — "Merge pull request #308 …mark-present-wfh")

## Current Commit
Not committed yet (awaiting Ploy's go-ahead per the Sonnet-implements /
Opus-reviews loop). Working tree only.

## Agent / Model Used
Claude Opus 4.8 (implementation, this branch).

## Field evidence that drove the design (warehouse device, 2026-07-24)
- `Test-NetConnection 192.168.201.202 -Port 4370` → `TcpTestSucceeded : True`
- `plcommpro.dll` path (`showroom_agent.py --check`): `PullLastError=-2` with
  comm key `0` **and** `1`, TCP and default — always fails. Not a config issue.
- pyzk (`sc700_simple_test.py --password 1 --check`): **connects**, over both TCP
  and UDP. `Serial BY34191560105, Firmware Ver 6.60 Apr 27 2017, Platform
  ZMM220_TFT`. `--pull` returned real punches (user ids `10014` etc. = PINs =
  employee_codes, Bangkok timestamps, status 4 / punch 0).
- Conclusion: this firmware speaks the legacy standalone protocol pyzk
  implements, but not the Pull SDK handshake `plcommpro.dll` uses. Comm key = `1`.

## Scope

### In Scope
- Add a **transport abstraction** to `showroom_agent.py`: `pullsdk` (existing,
  default) and `pyzk` (new), selected by `ZK_TRANSPORT`. Both normalize into the
  existing `Punch` dataclass, so all downstream logic is shared and unchanged.
- Wire the same switch through the two companion tools (`export_transactions_dat`,
  `sync_card_mapping`) so warehouse backfill + card-serial mapping also work.
- Make `pyzk` a real (lazily-imported) dependency in `requirements.txt`.

### Out of Scope
- No backend / API / DB / auth changes. The punch payload, endpoints, dedup, and
  the HR-gated agent-token flow are all untouched.
- No business-logic change. The punch filter (verified-open only for pull; pyzk
  attendance is already only genuine punches), badge selection, dedup watermark,
  queue/state, and `.dat` format are byte-for-byte preserved.
- The **showroom** default (`ZK_TRANSPORT=pullsdk`) behaviour is unchanged.

## Files Changed
- `agents/attendance/showroom_agent.py`:
  - Module docstring rewritten for dual transport; `import Iterator`.
  - `AgentConfig`: new `transport` / `force_udp` / `omit_ping` fields, read from
    `ZK_TRANSPORT` (default `pullsdk`), `ZK_FORCE_UDP`, `ZK_OMIT_PING`.
  - New `PullSdkTransport` (wraps the unchanged raw `PullSDK`) and `PyzkTransport`
    (lazy `from zk import ZK`), plus `build_transport()` / `open_transport()`.
    Both expose `connect / disconnect / read_attendance(zone) -> list[Punch] /
    stream_live(zone) -> Iterator[Punch|None] / read_user_mappings()`.
  - Parsing: new `txn_row_to_punch` (keeps **all** event types), `parse_transaction_row`
    now a thin verified-open filter on top of it (legacy behaviour preserved);
    new `attendance_to_punch` (pyzk `Attendance` → `Punch`, eventtype = 0) and
    `pullsdk_user_mappings` (moved out of `sync_card_mapping`).
  - `run_catchup` / `run_live` / `sdk_check` now use the transport; `run_live`
    consumes `stream_live` and flushes the queue on the `None` idle tick.
    `open_sdk` removed (replaced by `open_transport`).
- `agents/attendance/export_transactions_dat.py`: `--transport/--force-udp/--omit-ping`;
  reads punches via `build_transport(...).read_attendance()` and keeps every event
  type (broad backfill) filtering only by age. `.dat` output format unchanged.
- `agents/attendance/sync_card_mapping.py`: `--transport/--force-udp/--omit-ping`;
  reads `{employee_code, card_no}` via `transport.read_user_mappings()`; local
  `column_index`/`build_mappings` removed (logic now in `pullsdk_user_mappings`).
- `agents/attendance/requirements.txt`: `pyzk>=0.9` added (lazy import → harmless
  on the pull-only showroom machine); comments updated.

## Commands Run
```bash
python3 -m py_compile showroom_agent.py export_transactions_dat.py sync_card_mapping.py   # OK
python3 test_transport.py    # 17/17 PASS  (normalization + parsing + mappings)
python3 test_dispatch.py     # 5/5 PASS    (transport selection, lazy-import error path)
```

## Tests / Build Results
- **Syntax**: all three scripts compile.
- **Unit (local, `requests` stubbed, tzdata present)**: pyzk `Attendance`→`Punch`
  (badge/pin/tz/status/punch/eventtype=0, empty/zero user_id dropped); pull txn
  parsing keeps all events while `parse_transaction_row` still drops non-open;
  `pullsdk_user_mappings` drops card=0; `build_transport` dispatch incl. the
  `pyzk`-without-lib RuntimeError and the pull-path Windows-only raise. All pass.
- **Not run**: npm/mvn suites — these are standalone Python agent scripts, not
  covered by frontend/backend CI. There is no automated test harness for them in
  the repo.
- **Not run locally**: a live device connect (needs the on-LAN warehouse scanner)
  — see the required on-device verification below.

## Authz evidence
**No authorization change.** Transport is purely how the agent talks to the
device; the backend endpoints, the HR-gated per-device agent-token issuance, and
punch ingestion are untouched. Nothing role/scope-shaped in this diff.

## Known risks
1. **`stream_live` for pyzk is verified only by code review**, not against a live
   device (can't reach the warehouse LAN from the dev box). The `--check` and
   catch-up paths use the same `get_attendance()` call already proven in the
   field; `live_capture()` is the one primitive not yet exercised end-to-end here.
   → The on-device test below must include a real live tap.
2. **`ZK_TRANSPORT=pyzk` must be added to the warehouse env block.** That env
   block lives in `WAREHOUSE_SCANNER_SETUP.md` on the **separate, unmerged**
   `feat/attendance-warehouse-scanner` branch (not this one). When both land,
   step 3 of that runbook needs `$env:ZK_TRANSPORT = "pyzk"` (+ comm key `1`).
   Cross-branch follow-up — flagged so it isn't missed.
3. **Showroom regression surface**: `run_live`/`run_catchup` were refactored to a
   shared transport loop. Behaviour is intended to be identical for `pullsdk`, but
   the showroom device itself was not re-tested here. Re-run `--check` and a live
   tap on the showroom SC700 before relying on it, or merge behind the unchanged
   default and verify on next showroom touch.
4. pyzk `live_capture` idle-tick cadence = the connect timeout (~4s), which is
   when the queue flushes while idle. Fine (queue only holds failed posts, and
   catch-up-on-reconnect + flush-before-each-post also drain it).

## On-device verification (Ploy / warehouse mini PC — REQUIRED before merge)
```powershell
$env:ZK_TRANSPORT = "pyzk"
$env:ZK_HOST = "192.168.201.202"; $env:ZK_COMM_PASSWORD = "1"
$env:ATTENDANCE_SITE_CODE = "WAREHOUSE"; $env:ATTENDANCE_DEVICE_CODE = "WAREHOUSE_SC700"
python agents\attendance\showroom_agent.py --check                 # expect: connection passed
python agents\attendance\showroom_agent.py --once-catchup --dry-run # expect: real punches, no POST
python agents\attendance\showroom_agent.py --live --dry-run         # tap a card -> LIVE_CAPTURE line
```
Then a non-dry `--live` with the agent token set, confirming a tap lands in
`GET /api/attendance/daily?...` with `site_code=WAREHOUSE`.

## Exact next prompt for the next agent
> Review branch `feat/attendance-pyzk-transport` (worktree
> `.claude/worktrees/pyzk-transport`): verify the pyzk transport in
> `showroom_agent.py` normalizes `Attendance` records identically to the Pull SDK
> path and that the `pullsdk` default is behaviourally unchanged (diff
> `run_catchup`/`run_live`/`parse_transaction_row` against origin/main). Confirm
> `export_transactions_dat` still keeps all event types and `sync_card_mapping`
> still emits `{employee_code, card_no}`. Then coordinate the cross-branch
> follow-up: add `ZK_TRANSPORT=pyzk` to the warehouse env block in
> `WAREHOUSE_SCANNER_SETUP.md` on `feat/attendance-warehouse-scanner`.
