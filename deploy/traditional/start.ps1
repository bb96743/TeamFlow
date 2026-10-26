#Requires -Version 5.1
<#
.SYNOPSIS
    TeamFlow traditional deployment launcher (Windows / PowerShell 5.1+).

.DESCRIPTION
    Starts the backend executable JAR with environment variables loaded from an env file
    (default: <repo>/dev-local/.env.local) or the current process environment.

    Background mode (default): starts java as a detached process, writes its PID to
    deploy/logs/backend.pid, redirects logs to deploy/logs/backend-<timestamp>.log,
    then polls GET /api/health until it returns UP (or times out -> exit 1 with log tail).

    Foreground mode: runs java in the current console (Ctrl+C to stop).

    The JAR is expected to be built first:
        cd teamflow-system
        mvn -B -DskipTests clean package

.PARAMETER Jar
    Path to the executable JAR. Default: teamflow-system/teamflow-server/target/teamflow-server-0.1.0-SNAPSHOT.jar

.PARAMETER EnvFile
    KEY=VALUE file to load (existing process env wins). Default: dev-local/.env.local if it exists.

.PARAMETER DbName
    Override DB_NAME (useful for pointing at a verification database such as teamflow_deploy_check).

.PARAMETER Port
    Override server port. Default: SERVER_PORT or 8080.

.PARAMETER Background
    Start detached (default when -Foreground is not given).

.PARAMETER Foreground
    Run attached to this console.

.PARAMETER HealthTimeoutSeconds
    Health-check timeout in seconds (default 120).

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File deploy/traditional/start.ps1 -Background

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File deploy/traditional/start.ps1 -DbName teamflow_deploy_check -Port 8080
#>
[CmdletBinding()]
param(
    [string]$Jar = '',
    [string]$EnvFile = '',
    [string]$DbName = '',
    [int]$Port = 0,
    [switch]$Background,
    [switch]$Foreground,
    [int]$HealthTimeoutSeconds = 120
)

$ErrorActionPreference = 'Continue'

$scriptDir = $PSScriptRoot
$deployDir = Split-Path -Parent $scriptDir
$repoRoot = Split-Path -Parent $deployDir
$logDir = Join-Path $deployDir 'logs'
$pidFile = Join-Path $logDir 'backend.pid'

function Write-Step([string]$Message) { Write-Host ('[deploy] ' + $Message) }

# ---------- resolve JAR ----------
if ([string]::IsNullOrWhiteSpace($Jar)) {
    $Jar = Join-Path $repoRoot 'teamflow-system\teamflow-server\target\teamflow-server-0.1.0-SNAPSHOT.jar'
}
if (-not (Test-Path $Jar)) {
    Write-Host ("[FAIL] JAR not found: " + $Jar)
    Write-Host '       Build it first:  cd teamflow-system ; mvn -B -DskipTests clean package'
    exit 1
}

# ---------- load environment ----------
if ([string]::IsNullOrWhiteSpace($EnvFile)) {
    $candidate = Join-Path $repoRoot 'dev-local\.env.local'
    if (Test-Path $candidate) { $EnvFile = $candidate }
}
if (-not [string]::IsNullOrWhiteSpace($EnvFile)) {
    if (-not (Test-Path $EnvFile)) {
        Write-Host ("[FAIL] env file not found: " + $EnvFile)
        exit 1
    }
    Get-Content $EnvFile -Encoding Default | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
            $name = $matches[1]
            $value = $matches[2].Trim('"').Trim("'")
            if (-not [Environment]::GetEnvironmentVariable($name)) {
                [Environment]::SetEnvironmentVariable($name, $value, 'Process')
            }
        }
    }
    Write-Step ("env loaded from " + $EnvFile + " (existing process values kept)")
}

if (-not [string]::IsNullOrWhiteSpace($DbName)) { $env:DB_NAME = $DbName }
if ($Port -gt 0) { $env:SERVER_PORT = [string]$Port }
if ([string]::IsNullOrWhiteSpace($env:SERVER_PORT)) { $env:SERVER_PORT = '8080' }

# ---------- validate required settings ----------
if ([string]::IsNullOrEmpty($env:JWT_SECRET)) {
    Write-Host '[FAIL] JWT_SECRET is required. Generate one with: openssl rand -hex 32'
    exit 1
}
if ($env:JWT_SECRET.Length -lt 32) {
    Write-Host ("[FAIL] JWT_SECRET must be at least 32 bytes (current: " + $env:JWT_SECRET.Length + ')')
    exit 1
}
if ([string]::IsNullOrEmpty($env:DB_PASSWORD)) {
    Write-Step '[WARN] DB_PASSWORD is not set; application-dev.yml falls back to "root" (not for production)'
}
if ([string]::IsNullOrEmpty($env:DB_HOST)) { $env:DB_HOST = 'localhost' }
if ([string]::IsNullOrEmpty($env:DB_PORT)) { $env:DB_PORT = '3306' }
if ([string]::IsNullOrEmpty($env:DB_NAME)) { $env:DB_NAME = 'teamflow' }
if ([string]::IsNullOrEmpty($env:REDIS_HOST)) { $env:REDIS_HOST = 'localhost' }
if ([string]::IsNullOrEmpty($env:REDIS_PORT)) { $env:REDIS_PORT = '6379' }

# ---------- filesystem ----------
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$storagePath = $env:TEAMFLOW_FILE_STORAGE_PATH
if ([string]::IsNullOrWhiteSpace($storagePath)) { $storagePath = Join-Path $repoRoot 'data\uploads' }
if (-not [System.IO.Path]::IsPathRooted($storagePath)) { $storagePath = Join-Path $repoRoot $storagePath }
New-Item -ItemType Directory -Force -Path $storagePath | Out-Null
$env:TEAMFLOW_FILE_STORAGE_PATH = $storagePath

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$logFile = Join-Path $logDir ("backend-" + $stamp + ".log")
$errFile = Join-Path $logDir ("backend-" + $stamp + ".err.log")

Write-Step ("jar     : " + $Jar)
Write-Step ("db      : " + $env:DB_HOST + ':' + $env:DB_PORT + '/' + $env:DB_NAME)
Write-Step ("redis   : " + $env:REDIS_HOST + ':' + $env:REDIS_PORT)
Write-Step ("port    : " + $env:SERVER_PORT)
Write-Step ("storage : " + $storagePath)
Write-Step ("log     : " + $logFile)

if ($Foreground -and -not $Background) {
    Write-Step 'starting in FOREGROUND (Ctrl+C to stop)'
    & java -jar $Jar
    exit $LASTEXITCODE
}

# ---------- background start ----------
$process = Start-Process -FilePath 'java' -ArgumentList @('-jar', $Jar) -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput $logFile -RedirectStandardError $errFile -WorkingDirectory $repoRoot
Set-Content -Path $pidFile -Value $process.Id -Encoding ASCII
Write-Step ("started pid=" + $process.Id + " (pid file: " + $pidFile + ')')

$healthUrl = 'http://127.0.0.1:' + $env:SERVER_PORT + '/api/health'
$ready = $false
$elapsed = 0
for ($i = 0; $i -lt $HealthTimeoutSeconds; $i++) {
    Start-Sleep -Seconds 1
    $elapsed = $i + 1
    if ($process.HasExited) { break }
    try {
        $health = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 2
        if ($null -ne $health -and $health.code -eq 200) { $ready = $true; break }
    } catch { }
}

if ($ready) {
    Write-Step ("health check UP after " + $elapsed + "s: " + $healthUrl)
    Write-Step ("pid=" + $process.Id + "  log=" + $logFile)
    exit 0
}

Write-Host ("[FAIL] backend did not become healthy within " + $HealthTimeoutSeconds + 's (url: ' + $healthUrl + ')')
Write-Host '--- stdout tail ---'
if (Test-Path $logFile) { Get-Content $logFile -Tail 30 }
Write-Host '--- stderr tail ---'
if (Test-Path $errFile) { Get-Content $errFile -Tail 30 }
exit 1
