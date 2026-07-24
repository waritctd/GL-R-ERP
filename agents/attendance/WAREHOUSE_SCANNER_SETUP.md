# Warehouse SC700 Scanner Setup

Ops runbook for bringing the second physical scanner online: a ZKTeco unit
badged SC700 on a **ZMM220_TFT** board (firmware Ver 6.60 / 2017), installed in
the warehouse and connected to a dedicated Windows mini PC. That mini PC reaches
the on-prem backend over a site-to-site VPN.

> **Transport: pyzk, NOT the Pull SDK.** This board's firmware accepts pyzk's
> standalone ZK protocol but does **not** connect over the ZKAccess3.5 Pull SDK
> (`plcommpro.dll`) -- the Pull SDK fails with an opaque `PullLastError=-2`. The
> showroom SC700 is the opposite (Pull-SDK-only). The agent supports both via the
> `ZK_TRANSPORT` env var; the warehouse **must** set `ZK_TRANSPORT=pyzk`. This was
> confirmed on the physical device 2026-07-24 (serial BY34191560105). Because it
> uses pyzk, the warehouse machine needs **no** `plcommpro.dll`, no `ZK_SDK_DIR`,
> and no 32-bit Python requirement, and there is no ZKAccess3.5 in the loop.

This is a **registration + deployment** task, not a new driver: the agent
script (`agents/attendance/showroom_agent.py`) is already env-driven, multi-site,
and multi-transport. The warehouse scanner runs the *same* script as the showroom
one, pointed at different env vars (including `ZK_TRANSPORT=pyzk`).

For general Python/venv setup on a fresh machine see
`agents/attendance/SC700_NEW_COMPUTER_SETUP.md` -- but ignore its Pull-SDK /
`plcommpro.dll` / 32-bit-Python steps, which are showroom-only and do not apply
to this pyzk device.

## 0. Before you start

```text
Device code : WAREHOUSE_SC700
Site code   : WAREHOUSE   (already seeded in hr.attendance_site, V7)
Model       : ZKTeco SC700 (ZMM220_TFT board, fw 6.60)
Transport   : pyzk        (ZK_TRANSPORT=pyzk -- Pull SDK does NOT work on this unit)
IP address  : 192.168.201.202   (confirmed on-site; static, DHCP off)
Comm key    : 1                 (device Comm Key; ZK_COMM_PASSWORD=1)
Port        : 4370
```

The device row itself is registered by migration
`backend/src/main/resources/db/migration/V89__attendance_warehouse_device.sql`.
It ships with `ip_address = NULL` because the warehouse scanner's LAN IP is
not yet known -- that's fine, the *agent* (not the DB row) is what actually
dials the scanner, via its own `ZK_HOST` env var. Confirm the migration has
run (it deploys the same way every other Flyway migration does -- merge to
`main`, Render auto-deploys) before continuing:

```powershell
# from any machine that can reach the backend, as HR/CEO:
# GET /api/attendance/devices should list WAREHOUSE_SC700
```

## 1. Issue the per-device agent token

The backend stores only a hash of each device's agent token, so it is shown
in plaintext exactly once, at rotation time. Do this from an HR session
(browser devtools, curl, or Postman -- anything that carries the session
cookie):

```text
POST /api/attendance/devices/WAREHOUSE_SC700/agent-token
```

Response:

```json
{
  "device_code": "WAREHOUSE_SC700",
  "agent_token": "<plaintext token -- copy this now>",
  "rotated_at": "..."
}
```

Copy `agent_token` somewhere safe immediately -- it cannot be retrieved again,
only rotated (which invalidates the old one). This endpoint is HR-role-gated
(`sessions.requireAnyRole(user, "hr")` in `AttendanceController`); no new
permission logic was added for the warehouse device, it reuses the existing
per-device token endpoint from issue #22 (`V20__attendance_device_agent_token.sql`).

## 2. Prep the Windows mini PC

Get the repo, create a venv, and `pip install -r
agents/attendance/requirements.txt` (which now includes `pyzk`) on the warehouse
mini PC. **Any Python bitness works** -- pyzk is pure Python, so this device does
**not** need 32-bit Python, `plcommpro.dll`, or `ZK_SDK_DIR` (those are
showroom/Pull-SDK-only). If PowerShell blocks `Activate.ps1`
(`running scripts is disabled`), either call the venv's Python directly
(`.\.venv-attendance\Scripts\python.exe ...`) or
`Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`.

Do **not** reuse the showroom machine's `.venv-attendance` or state files;
this is a separate machine with its own venv, its own agent process, and its
own state/queue files.

## 3. Set the environment variables

On the warehouse mini PC (PowerShell), set:

```powershell
$env:ZK_TRANSPORT              = "pyzk"    # REQUIRED -- Pull SDK cannot talk to this unit
$env:ATTENDANCE_SITE_CODE      = "WAREHOUSE"
$env:ATTENDANCE_DEVICE_CODE    = "WAREHOUSE_SC700"
$env:ZK_HOST                   = "192.168.201.202"
$env:ZK_PORT                   = "4370"
$env:ZK_COMM_PASSWORD          = "1"       # device Comm Key
$env:ATTENDANCE_API_URL        = "http://<on-prem backend LAN IP over VPN>:8080/api/attendance/punch"
$env:ATTENDANCE_AGENT_TOKEN    = "<token from step 1>"
$env:ATTENDANCE_CATCHUP_MAX_DAYS = "3"
$env:ATTENDANCE_AGENT_DATA_DIR = "C:\glr-attendance-agent-warehouse"
```

Notes:
- `ZK_TRANSPORT=pyzk` is mandatory. Without it the agent defaults to the Pull SDK
  and fails with `PullLastError=-2` on this board.
- `ZK_HOST` must be set explicitly. The code default (`192.168.1.202`) is the
  **showroom** scanner's IP; the warehouse is on a different subnet
  (`192.168.201.202`).
- `ZK_COMM_PASSWORD=1` matches the device's Comm Key. (Blank/`0` fails with
  `Unauthenticated`.)
- `ATTENDANCE_API_URL` points at the backend's LAN address as reached
  *through the VPN tunnel*, not a public URL -- confirm the exact address
  with whoever set up the site-to-site VPN.
- `ATTENDANCE_AGENT_DATA_DIR` should be a warehouse-specific path so its
  state/queue files never collide with the showroom agent's.
- `ATTENDANCE_CATCHUP_MAX_DAYS=3` bounds the very first catch-up pull so it
  doesn't try to replay the device's entire history (same rationale as
  showroom -- see `catchup_cutoff()` in `showroom_agent.py`).

## 4. Check VPN / firewall reachability

Two separate hops to verify -- scanner reachability on the warehouse LAN, and
backend reachability over the VPN:

```powershell
# Mini PC -> scanner (same LAN as the mini PC, no VPN involved)
Test-NetConnection <warehouse scanner IP> -Port 4370

# Mini PC -> backend (over the site-to-site VPN)
Test-NetConnection <on-prem backend LAN IP> -Port 8080
```

Both must show `TcpTestSucceeded : True` before continuing. Note TCP reachability
alone does not prove the ZK session works -- if `--check` (below) still fails
after TCP passes, it is almost always the **Comm Key** (must be `1`, not blank/`0`
-- pyzk reports `Unauthenticated`) or another client holding the device's single
session. If the backend check fails, the VPN routing itself is the problem -- the
most likely failure point for this rollout (new tunnel, new subnet, possibly new
firewall rules on both ends). Confirm with IT/networking before assuming the agent
code is at fault.

Once both checks pass, confirm the pyzk device session itself works (expect
`pyzk connection passed attendance_records=<n>`):

```powershell
python agents\attendance\showroom_agent.py --check
```

## 5. Enroll employees on the device

Warehouse staff must be enrolled on the scanner with **PIN = employee_code**
(matching the showroom convention) so punches resolve to the right employee
with no extra mapping step -- the backend matches the device identifier
against `hr.employee.employee_code` first.

If any warehouse staff instead use card taps (raw card serial, not PIN), sync
the device's card-to-employee mapping with the `pyzk` transport (stop the
warehouse agent service first so there is only one device session; there is no
ZKAccess to close on this machine):

```powershell
cd C:\glr\GL-R-ERP
Stop-Service GLRAttendanceAgentWarehouse   # free the single device session
$env:GLR_IMPORT_EMAIL = "hr-user@glr.co.th"; $env:GLR_IMPORT_PASSWORD = "..."
python agents\attendance\sync_card_mapping.py --transport pyzk --host 192.168.201.202 --password 1 --api-base-url http://<on-prem backend LAN IP over VPN>:8080 --dry-run   # review
python agents\attendance\sync_card_mapping.py --transport pyzk --host 192.168.201.202 --password 1 --api-base-url http://<on-prem backend LAN IP over VPN>:8080             # apply
Start-Service GLRAttendanceAgentWarehouse
```

## 6. Check for unmapped punches

After the agent has been running a little while (or after a manual
`--once-catchup --dry-run` test), check for badges that scanned but did not
match any employee -- this is HR/CEO-only:

```text
GET /api/attendance/unmapped?from=<date>&to=<date>
```

Anything listed there is either a PIN typo on enrollment or an un-synced card
serial -- go back to step 5.

## 7. Run the agent as a Windows service

Install the agent as a service on the warehouse mini PC the same way it was
done on the showroom T360 (this repo does not ship a service-install script
-- match whatever mechanism (e.g. `nssm`) was used for the showroom
`GLRAttendanceAgent` service, but give it a **different service name** since
it's a separate machine, e.g. `GLRAttendanceAgentWarehouse`).

For on-device maintenance (enrolling new staff, changing device config), the
device still tolerates only one session at a time -- but on this machine that is
just the agent service, there is no ZKAccess to close. So simply stop the service
for the duration and start it again after:

```powershell
Stop-Service GLRAttendanceAgentWarehouse
# ... enroll staff / adjust the device from its own on-screen menu ...
Start-Service GLRAttendanceAgentWarehouse
```

On restart the agent's catch-up reads the device's stored attendance and
backfills any punches that landed while it was stopped (the backend dedups, so
nothing is lost or doubled). The `pause-for-zkaccess.ps1` / `resume-agent.ps1`
scripts are **showroom-only** -- they exist to coordinate with ZKAccess3.5, which
this pyzk machine does not run.

## 8. Final sanity check

Have someone punch in at the warehouse scanner, then confirm:

1. The agent log shows a delivered punch (`LIVE_CAPTURE`).
2. `GET /api/attendance/daily?...` for that employee/date shows the punch.
3. The punch's `site_code` is `WAREHOUSE`, not `SHOWROOM` -- this is the
   whole point of the exercise, so don't skip this check.
