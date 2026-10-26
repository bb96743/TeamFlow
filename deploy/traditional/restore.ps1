#Requires -Version 5.1
<#
.SYNOPSIS
    Restore a TeamFlow database from a mysqldump file (Windows).

.DESCRIPTION
    Safe by default:
      · -Force is REQUIRED (without it the script refuses to do anything);
      · if the target database already exists and is not empty, you must also pass -Drop,
        otherwise the script refuses to overwrite it (prevents accidental data loss);
      · after restore it prints verification data (Flyway version, table count, key row counts).

    Usage example:
      powershell -ExecutionPolicy Bypass -File deploy/traditional/restore.ps1 `
        -File deploy/backup/teamflow-teamflow-20261027-011500.sql -DbName teamflow -Force -Drop

.PARAMETER File
    Backup .sql file produced by backup.ps1 (required).

.PARAMETER DbName
    Target database. Default: DB_NAME env or teamflow.

.PARAMETER Drop
    Drop and recreate the target database before restoring (destructive; requires -Force).

.PARAMETER Force
    Required confirmation flag.

.PARAMETER EnvFile
    Env file with DB_HOST/DB_PORT/DB_USERNAME/DB_PASSWORD.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$File,
    [string]$DbName = '',
    [switch]$Drop,
    [switch]$Force,
    [string]$EnvFile = ''
)

$ErrorActionPreference = 'Continue'

$deployDir = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $deployDir

function Resolve-Exe([string]$Name, [string]$Fallback) {
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -ne $command) { return $command.Source }
    if (Test-Path $Fallback) { return $Fallback }
    return $null
}

if (-not $Force) {
    Write-Host '[ABORT] restore overwrites data; pass -Force to confirm.'
    exit 1
}
if (-not (Test-Path $File)) {
    Write-Host ("[FAIL] backup file not found: " + $File)
    exit 1
}
if ((Get-Item $File).Length -lt 1024) {
    Write-Host '[FAIL] backup file is suspiciously small; refusing to restore.'
    exit 1
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
if ($null -eq $mysqlExe) {
    Write-Host '[FAIL] mysql client not found (add MySQL client tools to PATH).'
    exit 1
}

# existing data protection
$exists = & $mysqlExe -h $dbHost -P $dbPort -u $dbUser -N -B -e "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='$DbName';"
$dbExists = (($exists | Select-Object -First 1) -eq '1')
if ($dbExists) {
    $tableCount = & $mysqlExe -h $dbHost -P $dbPort -u $dbUser -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DbName';"
    $tables = [int](($tableCount | Select-Object -First 1))
    if ($tables -gt 0 -and -not $Drop) {
        Write-Host ("[ABORT] database '" + $DbName + "' already has " + $tables + ' tables.')
        Write-Host '        Pass -Drop to drop and recreate it (DESTRUCTIVE), or restore into an empty/new database.'
        exit 1
    }
    Write-Host ("[deploy] target database exists (" + $tables + ' tables)')
} else {
    Write-Host ("[deploy] target database does not exist; it will be created")
}

if ($Drop) {
    Write-Host ("[deploy] DROP DATABASE " + $DbName + ' (confirmed by -Force -Drop)')
    & $mysqlExe -h $dbHost -P $dbPort -u $dbUser -e "DROP DATABASE IF EXISTS ``$DbName``;"
}
Write-Host ("[deploy] CREATE DATABASE " + $DbName + ' IF NOT EXISTS')
& $mysqlExe -h $dbHost -P $dbPort -u $dbUser -e "CREATE DATABASE IF NOT EXISTS ``$DbName`` DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;"

Write-Host ("[deploy] restoring " + $File + ' -> ' + $DbName)
cmd /c "`"$mysqlExe`" -h $dbHost -P $dbPort -u $dbUser --default-character-set=utf8mb4 $DbName < `"$File`" 2>&1"
$exit = $LASTEXITCODE
if ($exit -ne 0) {
    Write-Host ("[FAIL] restore failed (exit " + $exit + ')')
    exit 1
}

Write-Host '[ok] restore finished; verification:'
& $mysqlExe -h $dbHost -P $dbPort -u $dbUser -N -B -e "SELECT 'tables', COUNT(*) FROM information_schema.tables WHERE table_schema='$DbName';"
$flywayExists = & $mysqlExe -h $dbHost -P $dbPort -u $dbUser -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DbName' AND table_name='flyway_schema_history';"
if (($flywayExists | Select-Object -First 1) -eq '1') {
    & $mysqlExe -h $dbHost -P $dbPort -u $dbUser -N -B -e "SELECT 'flyway_max_version', MAX(CAST(version AS UNSIGNED)) FROM $DbName.flyway_schema_history WHERE version IS NOT NULL;"
    & $mysqlExe -h $dbHost -P $dbPort -u $dbUser -N -B -e "SELECT 'flyway_rows', COUNT(*) FROM $DbName.flyway_schema_history;"
}
foreach ($table in @('sys_user', 'sys_menu', 'sys_role_menu')) {
    $check = & $mysqlExe -h $dbHost -P $dbPort -u $dbUser -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DbName' AND table_name='$table';"
    if (($check | Select-Object -First 1) -eq '1') {
        & $mysqlExe -h $dbHost -P $dbPort -u $dbUser -N -B -e "SELECT '$table', COUNT(*) FROM $DbName.$table;"
    }
}
exit 0
