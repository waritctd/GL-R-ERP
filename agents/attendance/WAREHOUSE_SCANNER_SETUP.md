# Warehouse SC700 Scanner Setup

Ops runbook for bringing the second physical scanner online: a ZKTeco SC700 /
ZMM220 board -- same family as the showroom scanner, same Pull SDK
(`plcommpro.dll`) -- installed in the warehouse and connected to a dedicated
Windows mini PC. That mini PC reaches the on-prem backend over a site-to-site
VPN.

This is a **registration + deployment** task, not a new driver: the agent
script (`agents/attendance/showroom_agent.py`) is already env-driven and
multi-site capable. The warehouse scanner runs the *same* script as the
showroom one, pointed at different env vars.

For SDK install detail (Python venv, `plcommpro.dll`, PowerShell execution
policy, etc.) see `agents/attendance/SC700_NEW_COMPUTER_SETUP.md` -- this
runbook does not repeat that, it only calls out where the warehouse steps
differ.

## 0. Before you start

```text
Device code : WAREHOUSE_SC700
Site code   : WAREHOUSE   (already seeded in hr.attendance_site, V7)
Model       : ZKTeco SC700
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

Follow `agents/attendance/SC700_NEW_COMPUTER_SETUP.md` steps 1-4 (install
32-bit Python, get the repo, create the venv, `pip install -r
agents/attendance/requirements.txt`) on the warehouse mini PC. The Pull SDK
(`plcommpro.dll`, from the ZKAccess3.5 install) must also be present and
`ZK_SDK_DIR` must point at the folder that contains it -- same requirement as
the showroom machine, 32-bit Python only.

Do **not** reuse the showroom machine's `.venv-attendance` or state files;
this is a separate machine with its own venv, its own agent process, and its
own state/queue files.

## 3. Set the environment variables

On the warehouse mini PC (PowerShell), set:

```powershell
$env:ATTENDANCE_SITE_CODE      = "WAREHOUSE"
$env:ATTENDANCE_DEVICE_CODE    = "WAREHOUSE_SC700"
$env:ZK_HOST                   = "<warehouse scanner LAN IP -- fill in on-site>"
$env:ZK_PORT                   = "4370"
$env:ATTENDANCE_API_URL        = "http://<on-prem backend LAN IP over VPN>:8080/api/attendance/punch"
$env:ATTENDANCE_AGENT_TOKEN    = "<token from step 1>"
$env:ATTENDANCE_CATCHUP_MAX_DAYS = "3"
$env:ATTENDANCE_AGENT_DATA_DIR = "C:\glr-attendance-agent-warehouse"
```

Notes:
- `ZK_HOST` has no safe default for this device -- the code/README default
  (`192.168.1.201`) is the **showroom** scanner's IP, not the warehouse
  one. It must always be set explicitly here.
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

Both must show `TcpTestSucceeded : True` before continuing. If the scanner
check fails, treat it like the showroom troubleshooting steps in
`SC700_NEW_COMPUTER_SETUP.md` section 11 (comm key, other SDK session already
open, device reachable at all). If the backend check fails, the VPN routing
itself is the problem -- this is the most likely failure point for this
whole rollout (new site-to-site tunnel, new subnet, possibly new firewall
rules on both ends). Confirm with IT/networking before assuming the agent
code is at fault.

Once both checks pass, confirm the Pull SDK session itself works:

```powershell
python agents\attendance\showroom_agent.py --check
```

## 5. Enroll employees on the device

Warehouse staff must be enrolled on the scanner with **PIN = employee_code**
(matching the showroom convention) so punches resolve to the right employee
with no extra mapping step -- the backend matches the device identifier
against `hr.employee.employee_code` first.

If any warehouse staff instead use card taps (raw card serial, not PIN), sync
the device's card-to-employee mapping the same way the showroom does:

```powershell
cd C:\glr\agents\attendance
.\pause-for-zkaccess.ps1
$env:GLR_IMPORT_EMAIL = "hr-user@glr.co.th"; $env:GLR_IMPORT_PASSWORD = "..."
py -3-32 sync_card_mapping.py --api-base-url http://<on-prem backend LAN IP over VPN>:8080 --dry-run   # review
py -3-32 sync_card_mapping.py --api-base-url http://<on-prem backend LAN IP over VPN>:8080             # apply
.\resume-agent.ps1
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

Once installed, day-to-day pause/resume for on-device maintenance (enrolling
new staff, pushing config in ZKAccess) uses the same two scripts as showroom,
just pointed at the warehouse service name:

```powershell
.\pause-for-zkaccess.ps1
# ... do the ZKAccess maintenance, close it from its own menu ...
.\resume-agent.ps1 -ServiceName "GLRAttendanceAgentWarehouse"
```

`resume-agent.ps1` refuses to start while ZKAccess/ZKTimeNet is still
running (it would hold the device and the agent would fail to connect), and
on successful start it tails the service log so you can watch catch-up
backfill whatever punches landed while the agent was paused.

## 8. Final sanity check

Have someone punch in at the warehouse scanner, then confirm:

1. The agent log shows a delivered punch (`LIVE_CAPTURE`).
2. `GET /api/attendance/daily?...` for that employee/date shows the punch.
3. The punch's `site_code` is `WAREHOUSE`, not `SHOWROOM` -- this is the
   whole point of the exercise, so don't skip this check.
