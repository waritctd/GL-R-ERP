# Showroom SC700 Field Test Plan

Use this guide on 28 June to prove the ZKTeco SC700 scanner first, without the
ERP backend. After the scanner-only test passes, move to the backend agent test.

## What This Proves

Phase 1 proves:

- The laptop/server can reach the scanner at `192.168.1.201:4370`.
- Python and `pyzk` can connect to the SC700.
- Existing attendance logs can be read from the device.
- A live card tap can be captured in real time.

Phase 2 proves:

- The same punch can be sent into the ERP backend/database.
- HR/CEO users can view attendance logs.
- Employees can view their own attendance logs.

## Important Safety Notes

- Do not expose `192.168.1.201` or TCP `4370` to the public internet.
- Do not run ZKAccess, another SDK tool, and the Python live test at the same
  time. Some ZKTeco devices are unreliable with multiple active sessions.
- The scanner-only script never clears logs and never posts to the backend.
- For the first test, use the laptop on the showroom HUB/LAN. After that, run
  the same commands on the server.

## Phase 1: Scanner-Only Test

### 1. Connect To The Showroom Network

Connect the laptop or server to the same showroom HUB/LAN path that can reach:

```text
Scanner IP: 192.168.1.201
Scanner port: 4370
```

### 2. Check Network

Mac:

```bash
ping 192.168.1.201
nc -vz 192.168.1.201 4370
```

Windows PowerShell:

```powershell
ping 192.168.1.201
Test-NetConnection 192.168.1.201 -Port 4370
```

Success means the machine can reach the SC700 network path.

### 3. Install Python Dependencies

Mac:

```bash
cd "/Users/ploy_warit/Desktop/GL&R ERP"
python3 -m venv .venv-attendance
source .venv-attendance/bin/activate
pip install -r agents/attendance/requirements.txt
```

Windows PowerShell:

```powershell
cd "C:\path\to\GL&R ERP"
py -3 -m venv .venv-attendance
.\.venv-attendance\Scripts\Activate.ps1
pip install -r agents\attendance\requirements.txt
```

### 4. Check SC700 Connection

Mac:

```bash
python3 agents/attendance/sc700_local_test.py --check --with-counts
```

Windows PowerShell:

```powershell
python agents\attendance\sc700_local_test.py --check --with-counts
```

Expected result:

- TCP check passed.
- pyzk check passed.
- Device information prints as JSON.

### 5. Pull Recent Logs Without Backend

Mac:

```bash
python3 agents/attendance/sc700_local_test.py --pull --limit 20
```

Windows PowerShell:

```powershell
python agents\attendance\sc700_local_test.py --pull --limit 20
```

Optional: save records to a local JSONL file:

```bash
python3 agents/attendance/sc700_local_test.py --pull --limit 50 --format jsonl --out /tmp/sc700_pull_test.jsonl
```

Windows:

```powershell
python agents\attendance\sc700_local_test.py --pull --limit 50 --format jsonl --out C:\Temp\sc700_pull_test.jsonl
```

### 6. Test Real-Time Card Tap

Run live mode, then tap a real card on the SC700.

Mac:

```bash
python3 agents/attendance/sc700_local_test.py --live --format jsonl --out /tmp/sc700_live_test.jsonl
```

Windows PowerShell:

```powershell
python agents\attendance\sc700_local_test.py --live --format jsonl --out C:\Temp\sc700_live_test.jsonl
```

Expected result:

- A new line appears when the card is tapped.
- The record contains `badge_code`, `punch_time`, `punch_state`, and `uid`.

If live mode does not print immediately, stop it with Ctrl+C, tap the card once,
then run:

```bash
python3 agents/attendance/sc700_local_test.py --pull --limit 5
```

If the new tap appears in pull mode, the scanner is recording correctly and the
remaining issue is only the live SDK session behavior.

## Phase 2: Backend Agent Test

Do this only after Phase 1 passes.

### 1. Confirm Backend Is Latest

```bash
curl https://gl-r-erp.onrender.com/api/auth/me
```

Expected:

```json
{"message":"Not authenticated","status":401}
```

Then test the attendance endpoint without a token:

```bash
curl -X POST https://gl-r-erp.onrender.com/api/attendance/punch \
  -H "Content-Type: application/json" \
  -d '{"site_code":"SHOWROOM","device_code":"SHOWROOM_SC700","badge_code":"TEST","punch_time":"2026-06-28T09:00:00+07:00"}'
```

Expected after the latest backend deploy:

```json
{"message":"Attendance agent token is not configured","status":503}
```

or:

```json
{"message":"Invalid attendance agent token","status":401}
```

If the response is `Invalid CSRF token`, Render has not deployed the latest
backend commit yet.

### 2. Set Backend Secret

In Render, set:

```text
APP_ATTENDANCE_AGENT_TOKEN=replace-with-secret-token
```

Then manually deploy the latest commit.

### 3. Run Production Agent In Dry Run

Mac:

```bash
export ZK_HOST=192.168.1.201
export ZK_PORT=4370
export ATTENDANCE_SITE_CODE=SHOWROOM
export ATTENDANCE_DEVICE_CODE=SHOWROOM_SC700
export ATTENDANCE_API_URL=https://gl-r-erp.onrender.com/api/attendance/punch
export ATTENDANCE_AGENT_TOKEN=replace-with-secret-token

python3 agents/attendance/showroom_agent.py --check
python3 agents/attendance/showroom_agent.py --once-catchup --dry-run
```

Windows PowerShell:

```powershell
$env:ZK_HOST = "192.168.1.201"
$env:ZK_PORT = "4370"
$env:ATTENDANCE_SITE_CODE = "SHOWROOM"
$env:ATTENDANCE_DEVICE_CODE = "SHOWROOM_SC700"
$env:ATTENDANCE_API_URL = "https://gl-r-erp.onrender.com/api/attendance/punch"
$env:ATTENDANCE_AGENT_TOKEN = "replace-with-secret-token"

python agents\attendance\showroom_agent.py --check
python agents\attendance\showroom_agent.py --once-catchup --dry-run
```

### 4. Run Backend Live Test

Mac:

```bash
python3 agents/attendance/showroom_agent.py --live
```

Windows PowerShell:

```powershell
python agents\attendance\showroom_agent.py --live
```

Tap one card. Expected backend result in logs:

```text
Delivered punch badge=... time=...
```

If the card was already sent before, the backend may return duplicate but still
accept the request.

## Success Checklist

- Network can reach `192.168.1.201:4370`.
- `sc700_local_test.py --check --with-counts` passes.
- `sc700_local_test.py --pull --limit 20` prints scanner records.
- `sc700_local_test.py --live` prints a new card tap.
- Render backend no longer returns `Invalid CSRF token` for `/api/attendance/punch`.
- `showroom_agent.py --live` delivers a punch to backend.
- Attendance appears in the ERP attendance page.

