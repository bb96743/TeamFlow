#Requires -Version 5.1
<#
.SYNOPSIS
    Stop the backend started by deploy/traditional/start.ps1 (Windows).

.DESCRIPTION
    Reads deploy/logs/backend.pid and stops that process. If the PID file is missing or
    stale it reports and exits 0 (nothing to stop). Use -Force to kill without waiting.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File deploy/traditional/stop.ps1
#>
[CmdletBinding()]
param([switch]$Force)

$deployDir = Split-Path -Parent $PSScriptRoot
$pidFile = Join-Path $deployDir 'logs\backend.pid'

if (-not (Test-Path $pidFile)) {
    Write-Host '[info] no pid file; backend not started by start.ps1 (or already stopped).'
    exit 0
}

$raw = (Get-Content $pidFile -Raw).Trim()
if ($raw -notmatch '^\d+$') {
    Write-Host ('[WARN] invalid pid file content: ' + $raw + ' ; removing it')
    Remove-Item $pidFile -Force
    exit 0
}

$targetId = [int]$raw
$process = Get-Process -Id $targetId -ErrorAction SilentlyContinue
if ($null -eq $process) {
    Write-Host ('[info] process ' + $targetId + ' is not running; removing stale pid file')
    Remove-Item $pidFile -Force
    exit 0
}

try {
    if ($Force) {
        Stop-Process -Id $targetId -Force
    } else {
        Stop-Process -Id $targetId
    }
    Start-Sleep -Seconds 2
    if (-not $Force) {
        $still = Get-Process -Id $targetId -ErrorAction SilentlyContinue
        if ($null -ne $still) {
            Write-Host '[info] process still running; forcing stop'
            Stop-Process -Id $targetId -Force
        }
    }
} catch {
    Write-Host ('[FAIL] stop failed: ' + $_.Exception.Message)
    exit 1
} finally {
    Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
}

Write-Host ('[ok] backend stopped pid=' + $targetId)
exit 0
