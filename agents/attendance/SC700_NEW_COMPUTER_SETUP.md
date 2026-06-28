# SC700 Scanner Test On A New Computer

Use this guide when testing the showroom ZKTeco SC700 from a computer that does
not have Python or this repo set up yet.

This test is scanner-only. It does not connect to the ERP backend, does not
write to the database, and does not clear logs from the scanner.

## Scanner Details

```text
Scanner model: ZKTeco SC700
Scanner IP: 192.168.1.201
Scanner port: 4370
Communication key: 0
```

## 1. Install Python

Download and install Python 3:

```text
https://www.python.org/downloads/
```

On Windows, enable this option during installation:

```text
Add Python to PATH
```

After installation, open Terminal or PowerShell and check:

```bash
python --version
```

If that does not work, try:

```bash
python3 --version
```

Expected result:

```text
Python 3.x.x
```

## 2. Get The Test Scripts

### Option A: Clone The Repo

Install Git if needed:

```text
https://git-scm.com/downloads
```

Then run:

```bash
git clone https://github.com/waritctd/GL-R-ERP.git
cd GL-R-ERP
```

### Option B: Download ZIP

Open:

```text
https://github.com/waritctd/GL-R-ERP
```

Then click:

```text
Code -> Download ZIP
```

Unzip the file, then open Terminal or PowerShell inside the unzipped folder.

## 3. Create A Python Virtual Environment

### Mac

```bash
python3 -m venv .venv-attendance
source .venv-attendance/bin/activate
```

### Windows PowerShell

```powershell
py -3 -m venv .venv-attendance
.\.venv-attendance\Scripts\Activate.ps1
```

If PowerShell blocks activation, run:

```powershell
Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
```

Then activate again:

```powershell
.\.venv-attendance\Scripts\Activate.ps1
```

You should see this at the beginning of the terminal prompt:

```text
(.venv-attendance)
```

## 4. Install Python Dependencies

From the repo folder:

```bash
pip install -r agents/attendance/requirements.txt
```

If you copied only the `agents/attendance` folder and are already inside that
folder, run:

```bash
pip install -r requirements.txt
```

## 5. Connect To The Scanner Network

Connect the computer to the same LAN/HUB/network that can reach the showroom
SC700 scanner.

The computer must be able to reach:

```text
192.168.1.201:4370
```

## 6. Test Network First

### Mac

```bash
ping 192.168.1.201
nc -vz 192.168.1.201 4370
```

### Windows PowerShell

```powershell
ping 192.168.1.201
Test-NetConnection 192.168.1.201 -Port 4370
```

Good result:

```text
Ping works
Port 4370 is open
```

On Windows, a good port result usually shows:

```text
TcpTestSucceeded : True
```

## 7. Run Simple Scanner Connection Test

### Mac

```bash
python3 agents/attendance/sc700_simple_test.py --check --with-counts --host 192.168.1.201 --password 0 --timeout 30
```

### Windows PowerShell

```powershell
python agents\attendance\sc700_simple_test.py --check --with-counts --host 192.168.1.201 --password 0 --timeout 30
```

If it works, the script prints device information and attendance/user counts.

## 8. Try UDP Mode If TCP Times Out

### Mac

```bash
python3 agents/attendance/sc700_simple_test.py --check --with-counts --host 192.168.1.201 --password 0 --force-udp --omit-ping --timeout 30
```

### Windows PowerShell

```powershell
python agents\attendance\sc700_simple_test.py --check --with-counts --host 192.168.1.201 --password 0 --force-udp --omit-ping --timeout 30
```

## 9. Pull Last 20 Attendance Logs

Run this only if the connection test succeeds.

### Mac

```bash
python3 agents/attendance/sc700_simple_test.py --pull --limit 20 --host 192.168.1.201 --password 0
```

### Windows PowerShell

```powershell
python agents\attendance\sc700_simple_test.py --pull --limit 20 --host 192.168.1.201 --password 0
```

## 10. Test A New Card Tap

Run polling mode:

### Mac

```bash
python3 agents/attendance/sc700_simple_test.py --poll --host 192.168.1.201 --password 0
```

### Windows PowerShell

```powershell
python agents\attendance\sc700_simple_test.py --poll --host 192.168.1.201 --password 0
```

Then tap a card on the scanner.

Expected output:

```text
New punch
User ID : ...
Time    : ...
Status  : ...
Punch   : ...
UID     : ...
```

Stop the script with:

```text
Ctrl + C
```

## 11. What A Timeout Means

If the script prints:

```text
Error: timed out
```

That means the computer can reach the network path, but the scanner did not
complete the ZKTeco SDK session.

Ask IT to check:

- Is another ZKTeco/attendance program already connected?
- Is the communication key really `0`?
- Is TCP/UDP `4370` allowed both directions?
- Can the scanner be restarted once and tested again?
- Can the same test be run from the final server instead of a laptop?

## 12. Fallback For Demo

If real-time SDK connection still times out, use this fallback:

```text
Export .dat from the scanner
Import .dat into ERP attendance module
Continue real-time integration after SDK connection is solved
```

