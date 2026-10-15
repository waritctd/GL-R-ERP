# Showroom SenseFace 3A → ERP via ZKBio CVAccess

The showroom's SenseFace 3A sends every punch to ZKBio CVAccess (AC push), and
nothing else can talk to it. `cvaccess_agent.py` reads those punches out of
CVAccess's own PostgreSQL database and posts them to the ERP, both live (every
few seconds) and as a one-off backfill of past days.

It only ever **reads**. The session is read-only, and it never touches the
device or CVAccess's settings. Stopping it is always safe: a restart resumes
from where it stopped, and the ERP ignores punches it already has.

Do everything below **on the CVAccess server, 192.168.1.100**, in PowerShell.
CVAccess's database normally accepts connections from that machine only.

The old agent (`GLRAttendanceAgent`) and the `C:\glr` checkout live on the T360
office server. If CVAccess runs on a different machine, clone the repo there
(`git clone https://github.com/waritctd/GL-R-ERP.git C:\glr`) and read the old
service's settings on the T360 with `nssm get GLRAttendanceAgent AppEnvironmentExtra`.

## 0. Before you start

- Update the repo: `cd C:\glr; git checkout main; git pull`.
- Check the CVAccess version (web console → the "About" entry). ZKTeco's
  [2026-09-01 security bulletin](https://www.zkteco.com/en/Security_Bulletinsibs/26)
  says **4.4.1 and earlier** have unauthorized-access and path-traversal flaws,
  fixed in **4.4.2**. Back up the
  database first if you upgrade. After an upgrade, run step 5 again.
- If the web console still accepts `admin` / `admin`, change it.

## 1. Find CVAccess's database

Port the bundled PostgreSQL listens on:

```powershell
Get-NetTCPConnection -State Listen | Where-Object OwningProcess -in (Get-Process postgres).Id | Select-Object LocalAddress, LocalPort
```

Database name, user and password, from CVAccess's configuration files (the
manual's default install path is shown; adjust it if CVAccess lives elsewhere):

```powershell
Get-ChildItem "C:\Program Files\ZKBio CVAccess" -Recurse -Include *.properties,*.yml,*.yaml -ErrorAction SilentlyContinue | Select-String -Pattern "jdbc:postgresql","username","password"
```

Look for `jdbc:postgresql://127.0.0.1:<port>/<database>` and the user and
password next to it. If the password is encrypted (for example `ENC(...)`),
stop and ask TigerSoft for it rather than guessing.

**Never commit these values.** The repository is public.

## 2. Give the agent its own read-only login (recommended)

This login can read the punch table but no other CVAccess table, and cannot
change any CVAccess data, so a leaked environment variable is of little use. Connect with the bundled `psql`, as
the user from step 1:

```powershell
& "C:\Program Files\ZKBio CVAccess\Main Resource\postgresql\bin\psql.exe" -h 127.0.0.1 -p <port> -U <user> -d <database>
```

Then, choosing your own password (letters and digits only, so step 8 can pass
it on without quoting trouble):

```sql
CREATE ROLE glr_reader LOGIN PASSWORD '<choose one>';
GRANT CONNECT ON DATABASE "<database>" TO glr_reader;
GRANT USAGE ON SCHEMA public TO glr_reader;
GRANT SELECT ON acc_transaction TO glr_reader;
```

If a CVAccess upgrade recreates the table, the reader loses its grant and step 5
reports `missing:` with `(table not found)`. Run the last `GRANT` again.

## 3. Install

Python 3.9 or newer, 32- or 64-bit (the database driver is pure Python):

```powershell
py -3 -m venv C:\glr\.venv-cvaccess
C:\glr\.venv-cvaccess\Scripts\pip install -r C:\glr\agents\attendance\requirements.txt
```

## 4. Configure

Do steps 4 to 7 in **one PowerShell window**: `$env:` values disappear when it
closes. Without `ATTENDANCE_AGENT_DATA_DIR`, the agent saves its position in
the current folder, and the service in step 8 would not find it.

```powershell
$env:CVACCESS_DB_HOST = "127.0.0.1"
$env:CVACCESS_DB_PORT = "<port from step 1>"
$env:CVACCESS_DB_NAME = "<database from step 1>"
$env:CVACCESS_DB_USER = "glr_reader"
$env:CVACCESS_DB_PASSWORD = "<its password>"
$env:ATTENDANCE_API_URL = "<same value the old GLRAttendanceAgent service used>"
$env:ATTENDANCE_AGENT_TOKEN = "<same value the old GLRAttendanceAgent service used>"
$env:ATTENDANCE_SITE_CODE = "SHOWROOM"
$env:ATTENDANCE_DEVICE_CODE = "SHOWROOM_SC700"
$env:ATTENDANCE_AGENT_DATA_DIR = "C:\glr-cvaccess-agent"
```

`ATTENDANCE_DEVICE_CODE` stays `SHOWROOM_SC700` because the ERP's device row has
that name. Renaming it later changes no punch data. If the token is lost, an HR
user can issue a new one with
`POST /api/attendance/devices/SHOWROOM_SC700/agent-token`.

## 5. Check the database before trusting it

```powershell
C:\glr\.venv-cvaccess\Scripts\python C:\glr\agents\attendance\cvaccess_agent.py --check
```

It must exit without a `connection failed:`, `missing:` or `query failed:` line. It also prints each
column's type and how many rows have no insert time (those can never be sent;
expect 0). Then read two parts of its report:

- **Event list.** Find the event a normal staff scan produces (usually
  `0 Normal Verify Open`). Only the events in `CVACCESS_PUNCH_EVENTS` (default
  `0`) count as punches. If face scans show up under a different number, add it,
  for example `$env:CVACCESS_PUNCH_EVENTS = "0,15"`.
- **Newest rows.** Scan your face, run `--check` again, and confirm the newest
  row shows your PIN and **the clock time you scanned**. Then check step 6's
  dry run shows that same time with `+07:00`. If either is 7 hours off, stop:
  the timezone assumption is wrong and every punch would land on the wrong
  hour. (The agent treats an `event_time` of type `timestamp without time zone`
  as Bangkok wall-clock time.)

## 6. Dry run on today

```powershell
C:\glr\.venv-cvaccess\Scripts\python C:\glr\agents\attendance\cvaccess_agent.py --once --since <today, YYYY-MM-DD> --dry-run
```

Every punch it would send appears as a `DRY RUN punch payload=...` line, and
nothing is posted. Your scan from step 5 must be among them.

## 7. Backfill the past

The SenseFace has been in use since 2026-09-07. Preview, then send:

```powershell
C:\glr\.venv-cvaccess\Scripts\python C:\glr\agents\attendance\cvaccess_agent.py --once --since 2026-09-07 --dry-run
C:\glr\.venv-cvaccess\Scripts\python C:\glr\agents\attendance\cvaccess_agent.py --once --since 2026-09-07
```

Every punch of the real run must log `Delivered punch`. A `Queued punch ...
reason=HTTP 401` means the token, site code or device code from step 4 is
wrong. Stop, fix it, delete `C:\glr-cvaccess-agent\showroom_agent_queue.jsonl`,
and run step 7 again. The re-run sends every punch since 2026-09-07, so nothing
in the deleted queue is lost. (Queued punches keep the wrong codes, so they
would fail forever and hold up every later retry.)

Re-running it is safe because the ERP ignores punches it already has. Once the
step 8 service exists, **stop it first** (`nssm stop GLRAttendanceAgentCVAccess`)
and start it again afterwards: two copies share one retry queue file.

What the backfill changes in the ERP:

- Each backfilled day's attendance is recalculated. Days that HR marked present
  by hand keep HR's entry. Lateness is reporting-only (Labour Protection Act
  §76, enforced in code).
- **Overtime already approved is not recalculated.** The ERP computes paid OT
  minutes once, at the *first* approval (the division manager's, or the CEO's
  when approving directly), from the punches it had then, and freezes them.
  Showroom OT for work dates since 2026-09-07 whose first approval came before
  this backfill found no punches and is frozen at 0 minutes. That includes
  requests a manager approved earlier that the CEO approves later. The ERP has
  no recalculate action. The employee's division manager (or the CEO, for an
  employee with no manager stage) has to cancel each one, and the employee or
  that manager resubmits it after the backfill, while its payroll month is
  still open. HR cannot cancel another division's OT.

## 8. Run it live as a service

Install it the same way the old `GLRAttendanceAgent` service was installed, under
a new name, with the step 4 variables set on the service. If that was `nssm`
(check with `nssm get GLRAttendanceAgent Application`):

```powershell
nssm install GLRAttendanceAgentCVAccess "C:\glr\.venv-cvaccess\Scripts\python.exe" "C:\glr\agents\attendance\cvaccess_agent.py"
nssm set GLRAttendanceAgentCVAccess AppEnvironmentExtra 'CVACCESS_DB_HOST=127.0.0.1' 'CVACCESS_DB_PORT=<port>' 'CVACCESS_DB_NAME=<database>' 'CVACCESS_DB_USER=glr_reader' 'CVACCESS_DB_PASSWORD=<password>' 'ATTENDANCE_API_URL=<url>' 'ATTENDANCE_AGENT_TOKEN=<token>' 'ATTENDANCE_SITE_CODE=SHOWROOM' 'ATTENDANCE_DEVICE_CODE=SHOWROOM_SC700' 'ATTENDANCE_AGENT_DATA_DIR=C:\glr-cvaccess-agent' 'CVACCESS_PUNCH_EVENTS=<value from step 5, default 0>'
nssm set GLRAttendanceAgentCVAccess AppStdout C:\glr-cvaccess-agent\agent.log
nssm set GLRAttendanceAgentCVAccess AppStderr C:\glr-cvaccess-agent\agent.log
nssm start GLRAttendanceAgentCVAccess
```

With no `--once`, it polls every 3 seconds (`CVACCESS_POLL_SECONDS`). Its first
start picks up where step 7 stopped. Never give the service `--since`: the
agent refuses it outside `--once`, because the service would re-send all
history on every restart. Its log is `C:\glr-cvaccess-agent\agent.log`.

The old showroom `GLRAttendanceAgent` service targets the SC700, which no longer
exists. If it still runs, it only logs connection errors. Stop and disable it
**only if** it is not also serving another device.

## If something goes wrong

- **The ERP is down.** Punches wait in `C:\glr-cvaccess-agent\showroom_agent_queue.jsonl`
  and go out, in order, when it is back.
- **CVAccess or its database restarts.** The service reconnects every 30
  seconds and continues from its saved position.
- **Punches never arrive but `--check` is clean.** Compare the event numbers
  in `--check` with `CVACCESS_PUNCH_EVENTS` (step 5).
- **A punch is missing for one day.** Stop the service, re-sweep that day with
  `--once --since <that day>`, then start the service again. The ERP ignores
  everything it already has.
