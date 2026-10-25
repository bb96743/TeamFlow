#Requires -Version 5.1
<#
.SYNOPSIS
    Local equivalent of .github/workflows/ci.yml (G-5).

.DESCRIPTION
    Runs the same checks as CI, step by step, on the local machine:
      1. backend compile        : mvn -B -pl teamflow-server -am -DskipTests compile
      2. backend test           : mvn -B -pl teamflow-server -am test
      3. frontend unit test     : npm run test:unit
      4. frontend build         : npm run build

    Prerequisites: JDK 21, Maven 3.9+, Node 24 + npm, MySQL on 127.0.0.1:3306 and
    Redis on 127.0.0.1:6379. The script checks the ports first and prints the exact
    start-up hints when something is missing.

    Local connection settings are read from dev-local/.env.local (JWT_SECRET, DB_PASSWORD)
    when they are not already present in the process environment. CI uses fixed test values.

    Exit code: 0 when every executed step passed, 1 otherwise.

.PARAMETER SkipBackend
    Skip backend compile + tests (front-end only).

.PARAMETER SkipFrontend
    Skip front-end unit tests + build (backend only).

.PARAMETER WithNpmInstall
    Run "npm ci" before front-end steps (needs network). Default: only verify that
    teamflow-vue/node_modules exists and instruct to run npm ci when missing.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts/ci-local.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts/ci-local.ps1 -SkipFrontend
#>
[CmdletBinding()]
param(
    [switch]$SkipBackend,
    [switch]$SkipFrontend,
    [switch]$WithNpmInstall
)

$ErrorActionPreference = 'Continue'

# ---------------------------------------------------------------------------
# Paths and helpers
# ---------------------------------------------------------------------------
$repoRoot = Split-Path -Parent $PSScriptRoot
$backendDir = Join-Path $repoRoot 'teamflow-system'
$frontendDir = Join-Path $repoRoot 'teamflow-vue'
$envFile = Join-Path $repoRoot 'dev-local\.env.local'
$logDir = Join-Path $env:TEMP 'teamflow-ci-local'
$results = New-Object System.Collections.ArrayList

function Write-Section([string]$Title) {
    Write-Host ''
    Write-Host ('=' * 78)
    Write-Host $Title
    Write-Host ('=' * 78)
}

function Add-Result([string]$Step, [string]$Status, [double]$Seconds, [string]$Detail) {
    [void]$results.Add([pscustomobject]@{
        Step    = $Step
        Status  = $Status
        Seconds = [math]::Round($Seconds, 1)
        Detail  = $Detail
    })
}

function Test-TcpPort([string]$HostName, [int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne(1500)) { return $false }
        $client.EndConnect($async)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Import-LocalEnv {
    if (-not (Test-Path $envFile)) {
        Write-Host "[info] $envFile not found; using process environment variables."
        return
    }
    Get-Content $envFile -Encoding Default | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
            $name = $matches[1]
            $value = $matches[2].Trim('"').Trim("'")
            if (-not [Environment]::GetEnvironmentVariable($name)) {
                [Environment]::SetEnvironmentVariable($name, $value, 'Process')
            }
        }
    }
    Write-Host "[info] Loaded local environment from dev-local\.env.local (existing values kept)."
}

function Invoke-CommandStep {
    param(
        [string]$StepName,
        [string]$WorkDir,
        [string]$FileName,
        [string[]]$Arguments,
        [string]$SuccessDetail,
        [scriptblock]$Analyze
    )

    Write-Section ("STEP: " + $StepName)
    Write-Host ("[cmd] {0} {1}   (working dir: {2})" -f $FileName, ($Arguments -join ' '), $WorkDir)

    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    Push-Location $WorkDir
    try {
        $output = & $FileName @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    $stopwatch.Stop()

    $logFile = Join-Path $logDir (($StepName -replace '[^A-Za-z0-9_.-]', '_') + '.log')
    $output | ForEach-Object { [string]$_ } | Set-Content -Path $logFile -Encoding UTF8

    Write-Host ("[log] full output: {0}" -f $logFile)
    $output | Select-Object -Last 25 | ForEach-Object { Write-Host ('  ' + [string]$_) }

    $detail = $SuccessDetail
    $ok = ($exitCode -eq 0)
    if ($null -ne $Analyze) {
        $analysis = & $Analyze $output
        if ($analysis) { $detail = $analysis }
    }
    if (-not $ok) {
        $detail = ("exit code {0}; see {1}" -f $exitCode, $logFile)
    }

    $status = if ($ok) { 'PASS' } else { 'FAIL' }
    Add-Result $StepName $status $stopwatch.Elapsed.TotalSeconds $detail
    Write-Host ("[result] {0} {1} in {2:N1}s - {3}" -f $status, $StepName, $stopwatch.Elapsed.TotalSeconds, $detail)
    return $ok
}

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------
Write-Section 'TeamFlow local CI (equivalent to .github/workflows/ci.yml)'
Write-Host ("repo root : {0}" -f $repoRoot)
Write-Host ("timestamp : {0}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
Write-Host ("PS version: {0}" -f $PSVersionTable.PSVersion)

New-Item -ItemType Directory -Force -Path $logDir | Out-Null
Import-LocalEnv

$toolsOk = $true
if (-not $SkipBackend) {
    foreach ($tool in @('java', 'mvn')) {
        $command = Get-Command $tool -ErrorAction SilentlyContinue
        if ($null -eq $command) {
            Write-Host ("[FAIL] missing tool: {0}" -f $tool)
            $toolsOk = $false
        } else {
            Write-Host ("[ok] {0} -> {1}" -f $tool, $command.Source)
        }
    }
    if (-not [Environment]::GetEnvironmentVariable('JWT_SECRET')) {
        Write-Host '[FAIL] JWT_SECRET is not set (needed by backend tests; see dev-local/.env.local).'
        $toolsOk = $false
    }
    if (-not [Environment]::GetEnvironmentVariable('DB_PASSWORD')) {
        Write-Host '[FAIL] DB_PASSWORD is not set (needed by backend tests; see dev-local/.env.local).'
        $toolsOk = $false
    }
}
if (-not $SkipFrontend) {
    foreach ($tool in @('node', 'npm')) {
        $command = Get-Command $tool -ErrorAction SilentlyContinue
        if ($null -eq $command) {
            Write-Host ("[FAIL] missing tool: {0}" -f $tool)
            $toolsOk = $false
        } else {
            Write-Host ("[ok] {0} -> {1}" -f $tool, $command.Source)
        }
    }
}

$servicesOk = $true
if (-not $SkipBackend) {
    $mysqlUp = Test-TcpPort '127.0.0.1' 3306
    $redisUp = Test-TcpPort '127.0.0.1' 6379
    Write-Host ("[{0}] MySQL 127.0.0.1:3306" -f $(if ($mysqlUp) { 'ok' } else { 'FAIL' }))
    Write-Host ("[{0}] Redis 127.0.0.1:6379" -f $(if ($redisUp) { 'ok' } else { 'FAIL' }))
    if (-not $mysqlUp) {
        Write-Host '[hint] Start MySQL first:  net start MySQL80   (or dev-local\start-all.bat)'
        $servicesOk = $false
    }
    if (-not $redisUp) {
        Write-Host '[hint] Start Redis first:  Start-Process -WorkingDirectory "D:\Program Files\Redis" "redis-server.exe"   (or dev-local\start-all.bat)'
        $servicesOk = $false
    }
}

if (-not $toolsOk) {
    Write-Host ''
    Write-Host '[abort] Missing required tools or environment variables; nothing was executed.'
    Add-Result 'preflight:tools' 'FAIL' 0 'missing tools or env vars'
    $results | Format-Table -AutoSize | Out-String | Write-Host
    exit 1
}
if (-not $servicesOk) {
    Write-Host ''
    Write-Host '[abort] MySQL/Redis are not reachable; backend steps skipped. Start them and re-run.'
    Add-Result 'preflight:services' 'FAIL' 0 'MySQL/Redis not reachable'
    $results | Format-Table -AutoSize | Out-String | Write-Host
    exit 1
}

# ---------------------------------------------------------------------------
# Backend
# ---------------------------------------------------------------------------
if (-not $SkipBackend) {
    $compileOk = Invoke-CommandStep `
        -StepName 'backend:compile' `
        -WorkDir $backendDir `
        -FileName 'mvn' `
        -Arguments @('-B', '-pl', 'teamflow-server', '-am', '-DskipTests', 'compile') `
        -SuccessDetail 'compiled'

    $testAnalyzer = {
        param($output)
        $total = 0
        $failures = 0
        $errors = 0
        foreach ($line in $output) {
            $text = [string]$line
            if ($text -match '^\[INFO\] Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)\s*$') {
                $total += [int]$matches[1]
                $failures += [int]$matches[2]
                $errors += [int]$matches[3]
            }
        }
        if ($total -gt 0) {
            return ("{0} tests, {1} failures, {2} errors" -f $total, $failures, $errors)
        }
        return $null
    }

    $testOk = Invoke-CommandStep `
        -StepName 'backend:test' `
        -WorkDir $backendDir `
        -FileName 'mvn' `
        -Arguments @('-B', '-pl', 'teamflow-server', '-am', 'test') `
        -SuccessDetail 'tests passed' `
        -Analyze $testAnalyzer
}

# ---------------------------------------------------------------------------
# Frontend
# ---------------------------------------------------------------------------
if (-not $SkipFrontend) {
    $nodeModules = Join-Path $frontendDir 'node_modules'
    if ($WithNpmInstall -or -not (Test-Path $nodeModules)) {
        [void](Invoke-CommandStep `
            -StepName 'frontend:install' `
            -WorkDir $frontendDir `
            -FileName 'npm' `
            -Arguments @('ci') `
            -SuccessDetail 'npm ci finished')
    } else {
        Write-Host '[info] teamflow-vue\node_modules exists; skipping npm ci (use -WithNpmInstall to force it).'
    }

    $unitAnalyzer = {
        param($output)
        $pass = -1
        $fail = -1
        foreach ($line in $output) {
            $text = [string]$line
            if ($text -match 'pass\s+(\d+)\s*$') { $pass = [int]$matches[1] }
            if ($text -match 'fail\s+(\d+)\s*$') { $fail = [int]$matches[1] }
        }
        if ($pass -ge 0) { return ("{0} passed, {1} failed" -f $pass, $fail) }
        return $null
    }

    [void](Invoke-CommandStep `
        -StepName 'frontend:test:unit' `
        -WorkDir $frontendDir `
        -FileName 'npm' `
        -Arguments @('run', 'test:unit') `
        -SuccessDetail 'unit tests passed' `
        -Analyze $unitAnalyzer)

    [void](Invoke-CommandStep `
        -StepName 'frontend:build' `
        -WorkDir $frontendDir `
        -FileName 'npm' `
        -Arguments @('run', 'build') `
        -SuccessDetail 'frontend build ok')
}

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
Write-Section 'SUMMARY'
$results | Format-Table -AutoSize | Out-String | Write-Host

$failCount = @($results | Where-Object { $_.Status -ne 'PASS' }).Count
$totalSeconds = ($results | Measure-Object -Property Seconds -Sum).Sum
Write-Host ("Executed {0} step(s), {1} failed, total {2:N1}s" -f $results.Count, $failCount, $totalSeconds)
Write-Host ("Logs: {0}" -f $logDir)

if ($failCount -gt 0) {
    Write-Host '[FAILED] one or more CI steps failed (see the table above).'
    exit 1
}

Write-Host '[OK] all executed CI steps passed.'
exit 0
