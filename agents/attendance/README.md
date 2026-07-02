# GL&R Attendance Agent

Showroom-first Python scripts for the ZKTeco SC700.

The agent connects to the SC700 over TCP port `4370`, reads attendance logs with `pyzk`, and posts normalized punch JSON to the Spring Boot backend. It is intended to run on the Dell T360 server as a Windows service after the backend attendance endpoint is implemented.

For the scanner-only field test that does not touch the backend, use:

```powershell
python agents\attendance\sc700_simple_test.py --check --with-counts
python agents\attendance\sc700_simple_test.py --pull --limit 20
python agents\attendance\sc700_simple_test.py --poll
```

The fuller diagnostic script is:

```powershell
python agents\attendance\sc700_local_test.py --check --with-counts
python agents\attendance\sc700_local_test.py --pull --limit 20
python agents\attendance\sc700_local_test.py --live
```

See `agents/attendance/SC700_FIELD_TEST.md` for the full 28 June test plan.

For a computer that does not have Python or this repo yet, follow
`agents/attendance/SC700_NEW_COMPUTER_SETUP.md`.

## Network Check From The Server

Run this on the T360 server, not from a laptop:

```powershell
ping 192.168.1.201
Test-NetConnection 192.168.1.201 -Port 4370
```

If both pass, the server can reach the SC700. If ping passes but port `4370` fails, check Windows Firewall, VLAN/subnet ACLs, the SC700 communication settings, or whether another app already has the device session open.

## Install

```powershell
cd "C:\path\to\GL&R ERP"
py -3 -m venv .venv-attendance
.\.venv-attendance\Scripts\Activate.ps1
pip install -r agents\attendance\requirements.txt
```

## Configure

Defaults are showroom-safe:

```powershell
$env:ZK_HOST = "192.168.1.201"
$env:ZK_PORT = "4370"
$env:ZK_PASSWORD = "0"
$env:ATTENDANCE_SITE_CODE = "SHOWROOM"
$env:ATTENDANCE_DEVICE_CODE = "SHOWROOM_SC700"
$env:ATTENDANCE_API_URL = "http://127.0.0.1:8080/api/attendance/punch"
$env:ATTENDANCE_AGENT_DATA_DIR = "C:\glr-attendance-agent"
```

Set an agent token when posting to the backend:

```powershell
$env:ATTENDANCE_AGENT_TOKEN = "replace-with-server-token"
```

## Run Checks

Check raw network plus `pyzk` connectivity:

```powershell
python agents\attendance\showroom_agent.py --check
```

Pull existing device logs once without posting to the backend:

```powershell
python agents\attendance\showroom_agent.py --once-catchup --dry-run
```

Run live capture:

```powershell
python agents\attendance\showroom_agent.py --live
```

If no mode is provided, the agent defaults to live capture.

## Import A Past `.dat` Export

After the backend is running and Flyway has created the attendance tables, an HR/MD/CEO user can import the old SC700 export without using the frontend:

```powershell
$env:GLR_API_BASE_URL = "http://127.0.0.1:8080"
$env:GLR_IMPORT_EMAIL = "hr-user@glr.co.th"
$env:GLR_IMPORT_PASSWORD = "employee-code-password"
python agents\attendance\import_dat.py "C:\Users\ploy_warit\Downloads\1_attlog.dat"
```

On this Mac workspace, the same import path is:

```bash
GLR_API_BASE_URL=http://127.0.0.1:8080 \
GLR_IMPORT_EMAIL=hr-user@glr.co.th \
GLR_IMPORT_PASSWORD=employee-code-password \
python3 agents/attendance/import_dat.py /Users/ploy_warit/Downloads/1_attlog.dat
```

The importer logs in through `/api/auth/login`, sends the `.dat` content to `POST /api/attendance/imports/dat`, and prints the import counts returned by the backend. Re-running the same file returns `duplicate_file` rather than importing it again.

## Using ZKAccess while the agent runs (maintenance)

The SC700 tolerates only one Pull-SDK session, so the agent service and ZKAccess
cannot use the device at the same time. To do maintenance in ZKAccess (enrolling
users, pushing config, etc.), pause the agent first and resume it after:

```powershell
.\pause-for-zkaccess.ps1     # stops the service, frees the device
# ...open ZKAccess, do the work, then close it from its own menu...
.\resume-agent.ps1           # restarts the service; catch-up backfills the gap
```

This loses no data: punches accumulate on the device while the agent is paused,
and on resume the agent's catch-up reads the device transaction table and
delivers anything it missed (the backend dedups). Two rules while in ZKAccess:
do **not** tick "Clear data in device", and always close ZKAccess from its own
menu (a force-kill leaves a stuck session that needs a device reboot to clear).

## Notes

- Keep the SC700, T360 server, and backend clock synced to Bangkok time.
- Avoid running ZKAccess, a laptop test script, and this agent against the SC700 at the same time. Some ZKTeco devices behave like they only tolerate one reliable active SDK/live session.
- The agent keeps state in `showroom_agent_state.json`.
- Failed backend posts are queued to `showroom_agent_queue.jsonl` and retried before sending new punches.
- Use `sc700_local_test.py` first when you only want to test the scanner without backend/database writes.
