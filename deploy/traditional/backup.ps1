#Requires -Version 5.1
<#
.SYNOPSIS
    Backup a TeamFlow database with mysqldump (Windows).

.DESCRIPTION
    Creates deploy/backup/teamflow-<db>-<timestamp>.sql using:
        mysqldump --single-transaction --routines --triggers --default-character-set=utf8mb4
    Validates the dump size (>1KB) and prunes old backups (default keep 10).

    Credentials are read from the process environment first, then from an env file
    (default: <repo>/dev-local/.env.local). DB_PASSWORD is passed through MYSQL_PWD,
    so it never appears on the command line.

.PARAMETER DbName
    Database to back up. Default: DB_NAME env or teamflow.

.PARAMETER BackupDir
    Output directory. Default: deploy/backup.

.PARAMETER Keep
    Number of most recent backups to keep (default 10; 0 = keep all).

.PARAMETER EnvFile
    Env file with DB_HOST/DB_PORT/DB_USERNAME/DB_PASSWORD.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File deploy/traditional/backup.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File deploy/traditional/backup.ps1 -DbName teamflow_deploy_check
#>
[CmdletBinding()]
param(
    [string]$DbName = '',
    [string]$BackupDir = '',
    [int]$Keep = 10,
    [string]$EnvFile = ''
)

$ErrorActionPreference = 'Continue'

$deployDir = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $deployDir
if ([string]::IsNullOrWhiteSpace($BackupDir)) { $BackupDir = Join-Path $deployDir 'backup' }

function Resolve-Exe([string]$Name, [string]$Fallback) {
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -ne $command) { return $command.Source }
    if (Test-Path $Fallback) { return $Fallback }
    return $null
}

if ([string]::IsNullOrWhiteSpace($EnvFile)) {
    $candidate = Join-Path $repoRoot 'dev-local\.env.local'
    if (Test-Path $candidate) { $EnvFile = $candidate }
}
if (-not [string]::IsNullOrWhiteSpace($EnvFile) -and (Test-Path $EnvFile)) {
    Get-Content $EnvFile -Encoding Default | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
            $name = $matches[1]
            $value = $matches[2].Trim('"').Trim("'")
            if (-not [Environment]::GetEnvironmentVariable($name)) {
                [Environment]::SetEnvironmentVariable($name, $value, 'Process')
            }
        }
    }
}

if ([string]::IsNullOrWhiteSpace($DbName)) {
    if (-not [string]::IsNullOrWhiteSpace($env:DB_NAME)) { $DbName = $env:DB_NAME } else { $DbName = 'teamflow' }
}
$dbHost = if ($env:DB_HOST) { $env:DB_HOST } else { '127.0.0.1' }
$dbPort = if ($env:DB_PORT) { $env:DB_PORT } else { '3306' }
$dbUser = if ($env:DB_USERNAME) { $env:DB_USERNAME } else { 'root' }
if ([string]::IsNullOrEmpty($env:DB_PASSWORD)) {
    Write-Host '[FAIL] DB_PASSWORD is not set (process env or env file).'
    exit 1
}
if ($DbName -notmatch '^[A-Za-z0-9_]+$') {
    Write-Host ("[FAIL] invalid database name: " + $DbName)
    exit 1
}
$env:MYSQL_PWD = $env:DB_PASSWORD

$mysqlExe = Resolve-Exe 'mysql' 'D:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$dumpExe = Resolve-Exe 'mysqldump' 'D:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe'
if ($null -eq $mysqlExe -or $null -eq $dumpExe) {
    Write-Host '[FAIL] mysql / mysqldump not found (add MySQL client tools to PATH).'
    exit 1
}

# sanity: database must exist
$exists = & $mysqlExe -h $dbHost -P $dbPort -u $dbUser -N -B -e "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='$DbName';"
if (($exists | Select-Object -First 1) -ne '1') {
    Write-Host ("[FAIL] database '" + $DbName + "' does not exist.")
    exit 1
}

New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$file = Join-Path $BackupDir ("teamflow-" + $DbName + "-" + $stamp + ".sql")

Write-Host ("[deploy] dumping " + $dbHost + ':' + $dbPort + '/' + $DbName + ' -> ' + $file)
cmd /c "`"$dumpExe`" -h $dbHost -P $dbPort -u $dbUser --default-character-set=utf8mb4 --single-transaction --routines --triggers --no-tablespaces $DbName > `"$file`" 2>&1"
$exit = $LASTEXITCODE
if ($exit -ne 0 -or -not (Test-Path $file)) {
    Write-Host ("[FAIL] mysqldump failed (exit " + $exit + ')')
    exit 1
}
$size = (Get-Item $file).Length
if ($size -lt 1024) {
    Write-Host ("[FAIL] dump is suspiciously small (" + $size + ' bytes); check credentials/permissions.')
    exit 1
}
Write-Host ("[ok] backup created: " + $file)
Write-Host ("[ok] size: " + $size + " bytes (" + [math]::Round($size / 1MB, 2) + ' MB)')
Write-Host ("[hint] restore with: powershell -File deploy/traditional/restore.ps1 -File `"$file`" -DbName " + $DbName + ' -Force -Drop')

# prune old backups
if ($Keep -gt 0) {
    $old = Get-ChildItem -Path $BackupDir -Filter ("teamflow-" + $DbName + "-*.sql") | Sort-Object LastWriteTime -Descending | Select-Object -Skip $Keep
    foreach ($item in $old) {
        Remove-Item $item.FullName -Force -ErrorAction SilentlyContinue
        Write-Host ("[info] pruned old backup: " + $item.Name)
    }
}

exit 0
