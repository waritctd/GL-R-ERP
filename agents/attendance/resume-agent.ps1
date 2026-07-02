<#
.SYNOPSIS
    Resumes the GL&R attendance agent after ZKAccess maintenance.

.DESCRIPTION
    Verifies ZKAccess / ZKTimeNet is closed (so it isn't still holding the
    device, which would make the agent fail to connect with error -14), starts
    the agent service, and shows the tail of the log so you can watch catch-up
    backfill the punches logged while the agent was paused.

.EXAMPLE
    .\resume-agent.ps1
#>
[CmdletBinding()]
param(
    [string]$ServiceName = "GLRAttendanceAgent",
    [string]$LogPath     = "C:\glr-attendance-agent\service.err.log"
)

$ErrorActionPreference = "Stop"

# Refuse to start while ZKAccess/ZKTimeNet is running -- it holds the device and
# the agent would just fail to connect.
$zk = Get-Process -ErrorAction SilentlyContinue |
    Where-Object { try { $_.Path -match 'ZKAccess|ZKTeco|ZKTimeNet' } catch { $false } }
if ($zk) {
    Write-Warning "ZKAccess/ZKTimeNet is still running:"
    $zk | Select-Object Name, Id, Path | Format-Table | Out-String | Write-Host
    Write-Warning "Close it from its own menu first, then re-run this script."
    return
}

$svc = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
if (-not $svc) {
    Write-Error "Service '$ServiceName' not found."
    return
}

if ($svc.Status -ne 'Running') {
    Write-Host "Starting $ServiceName ..."
    Start-Service -Name $ServiceName
    (Get-Service $ServiceName).WaitForStatus('Running', '00:00:30')
}
Write-Host "Service running. Reading the log for catch-up ..." -ForegroundColor Green
Write-Host ""

if (Test-Path $LogPath) {
    Start-Sleep -Seconds 3
    Get-Content -Path $LogPath -Tail 25
    Write-Host ""
    Write-Host "(Live-tail with:  Get-Content '$LogPath' -Tail 30 -Wait )" -ForegroundColor DarkGray
} else {
    Write-Warning "Log not found at $LogPath yet -- check again shortly."
}
