<#
.SYNOPSIS
    Frees the ZKTeco SC700 so ZKAccess / ZKTimeNet can be used for maintenance.

.DESCRIPTION
    The SC700 allows only one Pull-SDK session at a time, so the attendance
    agent and ZKAccess cannot talk to it simultaneously. This script stops the
    agent service and waits for the device to release its session, leaving the
    device free for ZKAccess.

    Attendance data is stored ON the device (its transaction table), not in the
    agent. When you later run resume-agent.ps1, the agent's catch-up reads that
    table and backfills every punch logged while it was paused; the ERP dedups,
    so nothing is lost or duplicated.

.EXAMPLE
    .\pause-for-zkaccess.ps1
#>
[CmdletBinding()]
param(
    [string]$ServiceName   = "GLRAttendanceAgent",
    [int]   $DevicePort    = 4370,
    [int]   $TimeoutSeconds = 40
)

$ErrorActionPreference = "Stop"

$svc = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
if (-not $svc) {
    Write-Error "Service '$ServiceName' not found. Is the agent installed as a service?"
    return
}

if ($svc.Status -ne 'Stopped') {
    Write-Host "Stopping $ServiceName ..."
    Stop-Service -Name $ServiceName -Force
    (Get-Service $ServiceName).WaitForStatus('Stopped', '00:00:30')
}
Write-Host "Service stopped." -ForegroundColor Green

# Wait for the device to drop the agent's TCP session before ZKAccess connects.
Write-Host "Waiting for the SC700 to release its session on port $DevicePort ..."
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
do {
    Start-Sleep -Seconds 2
    $held = Get-NetTCPConnection -RemotePort $DevicePort -ErrorAction SilentlyContinue
} while ($held -and (Get-Date) -lt $deadline)

if ($held) {
    Write-Warning "A connection to the device on port $DevicePort is still present:"
    $held | Select-Object State, OwningProcess | Format-Table | Out-String | Write-Host
    Write-Warning "Give it another moment before opening ZKAccess."
} else {
    Write-Host "Device is free." -ForegroundColor Green
}

Write-Host ""
Write-Host "You can open ZKAccess now. Before you finish:" -ForegroundColor Cyan
Write-Host "  * Do NOT tick 'Clear data in device' -- it wipes the attendance log."
Write-Host "  * Close ZKAccess from its OWN menu, not Task Manager. A force-kill"
Write-Host "    leaves a stuck session that needs a device reboot to clear."
Write-Host "  * Then run:  .\resume-agent.ps1"
