#Requires -Version 5.1
<#
.SYNOPSIS
    L-1: Clean M1 test residue from the TeamFlow development database.

.DESCRIPTION
    The dev database accumulated thousands of rows created by integration tests and parallel
    acceptance runs (IT-*, E2E-*, acceptance leftovers, test teams/tasks with nanoTime suffixes).
    These rows dilute M05 aggregation results and make demos misleading.

    This tool is AUDITABLE and REVERSIBLE:
      1. DRY-RUN BY DEFAULT: prints per-table delete counts and kept rows; changes nothing.
         Use -Execute to actually delete.
      2. ONLY explicit naming patterns are matched (never "truncate whole table"):
         - ASCII prefixes : IT-, E2E-, RB-, RB2..., M03-, M1-, V15-
         - Chinese prefixes (matched via HEX() so this script stays ASCII-safe):
           HEX E9AA8CE694B62D=acceptance prefix, E58692E7839F2D=smoke prefix,
           corrupted '??-M1-' / '????' leftovers (3F3F2D4D312D / 3F3F3F3F)
         - nanoTime suffix: names ending with 16+ digits (test data signatures)
         - todo_item: task_assign/task_approval rows cascaded from deleted tasks,
                      meeting_invite rows (M06 not started => test-only), plus the above patterns
         - flow_definition rows with test-only def_code prefix "it_"
      3. CASCADE follows real ownership (period -> objective -> KR -> task -> todo/flow; team -> member).
      4. sys_*, gen_*, flyway_schema_history and perf_* are NEVER touched.
      5. Before deleting, an automatic mysqldump backup is written to dev-local/backup/
         and its path + size are printed. If the backup fails, NOTHING is deleted.
      6. All deletes run in a single transaction; a failure rolls the whole cleanup back.

.PARAMETER Execute
    Actually perform backup + delete. Without it the script only reports (dry-run).

.PARAMETER Database
    Target schema name (default: teamflow).

.PARAMETER BackupDir
    Backup directory (default: <repo>/dev-local/backup).

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts/db-clean.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts/db-clean.ps1 -Execute
#>
[CmdletBinding()]
param(
    [switch]$Execute,
    [string]$Database = 'teamflow',
    [string]$BackupDir = ''
)

$ErrorActionPreference = 'Continue'

$repoRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repoRoot 'dev-local\.env.local'
if ([string]::IsNullOrWhiteSpace($BackupDir)) {
    $BackupDir = Join-Path $repoRoot 'dev-local\backup'
}

function Write-Section([string]$Title) {
    Write-Host ''
    Write-Host ('=' * 78)
    Write-Host $Title
    Write-Host ('=' * 78)
}

function Resolve-Exe([string]$Name, [string]$Fallback) {
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -ne $command) { return $command.Source }
    if (Test-Path $Fallback) { return $Fallback }
    return $null
}

if ($Database -notmatch '^[A-Za-z0-9_]+$') {
    Write-Host "[FAIL] invalid database name: $Database"
    exit 1
}

$mysqlExe = Resolve-Exe 'mysql' 'D:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$mysqldumpExe = Resolve-Exe 'mysqldump' 'D:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe'
if ($null -eq $mysqlExe -or $null -eq $mysqldumpExe) {
    Write-Host '[FAIL] mysql / mysqldump not found (add them to PATH or install MySQL 8.0 client tools).'
    exit 1
}

# Local credentials only (never written to the repo by this script).
if (Test-Path $envFile) {
    Get-Content $envFile -Encoding Default | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
            $name = $matches[1]
            $value = $matches[2].Trim('"').Trim("'")
            if (-not [Environment]::GetEnvironmentVariable($name)) {
                [Environment]::SetEnvironmentVariable($name, $value, 'Process')
            }
        }
    }
}
$dbHost = if ($env:DB_HOST) { $env:DB_HOST } else { '127.0.0.1' }
$dbUser = if ($env:DB_USERNAME) { $env:DB_USERNAME } else { 'root' }
$dbPassword = $env:DB_PASSWORD
if ([string]::IsNullOrEmpty($dbPassword)) {
    Write-Host '[FAIL] DB_PASSWORD is not set (expected from process env or dev-local/.env.local).'
    exit 1
}
# Pass the password through MYSQL_PWD so it never appears on the command line.
$env:MYSQL_PWD = $dbPassword

function Invoke-SqlText([string]$Sql, [string]$Label) {
    $file = Join-Path $env:TEMP ("teamflow-clean-" + $Label + "-" + [guid]::NewGuid().ToString('N') + ".sql")
    [System.IO.File]::WriteAllText($file, $Sql, (New-Object System.Text.UTF8Encoding($false)))
    try {
        $output = cmd /c "`"$mysqlExe`" -h $dbHost -u $dbUser --default-character-set=utf8mb4 --database=$Database --batch --skip-column-names < `"$file`" 2>&1"
        return @{ Output = @($output); ExitCode = $LASTEXITCODE }
    } finally {
        Remove-Item $file -Force -ErrorAction SilentlyContinue
    }
}

# ---------------------------------------------------------------------------
# Naming rules (explicit patterns only)
# ---------------------------------------------------------------------------
function Get-NameRule([string]$Column) {
    return @"
(($Column) LIKE 'IT-%' OR ($Column) LIKE 'E2E-%' OR ($Column) LIKE 'RB-%' OR ($Column) LIKE 'RB2%'
 OR ($Column) LIKE 'M03-%' OR ($Column) LIKE 'M1-%' OR ($Column) LIKE 'V15-%'
 OR ($Column) REGEXP '[0-9]{16}$'
 OR HEX($Column) LIKE 'E9AA8CE694B62D%'
 OR HEX($Column) LIKE 'E58692E7839F2D%'
 OR HEX($Column) LIKE '3F3F2D4D312D%'
 OR HEX($Column) LIKE '3F3F3F3F%')
"@
}

$periodRule = Get-NameRule 'period_name'
$objectiveRule = Get-NameRule 'objective_title'
$krRule = Get-NameRule 'kr_title'
$taskRule = Get-NameRule 'task_title'
$teamRule = Get-NameRule 'team_name'
$todoRule = Get-NameRule 'title'

$collectSql = @"
/* ---- collect candidate IDs (helper tables; dropped again at the end of the run) ---- */
/* NOTE: plain tables (not TEMPORARY) because MySQL cannot reopen a TEMPORARY table
   multiple times inside one statement (e.g. UNION / two IN sub-queries). */
DROP TABLE IF EXISTS tmp_clean_period, tmp_clean_objective, tmp_clean_kr, tmp_clean_task,
                     tmp_clean_team, tmp_clean_todo, tmp_clean_flow_def, tmp_clean_flow_inst;
CREATE TABLE tmp_clean_period (id BIGINT PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO tmp_clean_period SELECT id FROM okr_period WHERE $periodRule;

CREATE TABLE tmp_clean_objective (id BIGINT PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO tmp_clean_objective SELECT id FROM okr_objective
 WHERE period_id IN (SELECT id FROM tmp_clean_period) OR $objectiveRule;

CREATE TABLE tmp_clean_kr (id BIGINT PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO tmp_clean_kr SELECT id FROM okr_key_result
 WHERE objective_id IN (SELECT id FROM tmp_clean_objective) OR $krRule;

CREATE TABLE tmp_clean_task (id BIGINT PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO tmp_clean_task SELECT id FROM task_task
 WHERE kr_id IN (SELECT id FROM tmp_clean_kr) OR $taskRule;

CREATE TABLE tmp_clean_team (id BIGINT PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO tmp_clean_team SELECT id FROM team_team WHERE $teamRule;

CREATE TABLE tmp_clean_todo (id BIGINT PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO tmp_clean_todo SELECT id FROM todo_item
 WHERE (biz_type IN ('task_assign','task_approval') AND biz_id IN (SELECT id FROM tmp_clean_task))
    OR biz_type = 'meeting_invite'
    OR $todoRule
    OR HEX(title) LIKE 'E4BBBBE58AA1E68C87E6B4BE%'
    OR HEX(title) LIKE 'E4BBBBE58AA1E5AEA1E689B9%';

CREATE TABLE tmp_clean_flow_def (id BIGINT PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO tmp_clean_flow_def SELECT id FROM flow_definition WHERE def_code LIKE 'it\_%';

CREATE TABLE tmp_clean_flow_inst (id BIGINT PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO tmp_clean_flow_inst SELECT id FROM flow_instance WHERE biz_id IN (SELECT id FROM tmp_clean_task);
"@

$dropSql = @"
DROP TABLE IF EXISTS tmp_clean_period, tmp_clean_objective, tmp_clean_kr, tmp_clean_task,
                     tmp_clean_team, tmp_clean_todo, tmp_clean_flow_def, tmp_clean_flow_inst;
"@

$statsSql = $collectSql + @"

/* ---- statistics (to be deleted / total) ---- */
SELECT 'okr_period', (SELECT COUNT(*) FROM tmp_clean_period), (SELECT COUNT(*) FROM okr_period)
UNION ALL SELECT 'okr_objective', (SELECT COUNT(*) FROM tmp_clean_objective), (SELECT COUNT(*) FROM okr_objective)
UNION ALL SELECT 'okr_key_result', (SELECT COUNT(*) FROM tmp_clean_kr), (SELECT COUNT(*) FROM okr_key_result)
UNION ALL SELECT 'task_task', (SELECT COUNT(*) FROM tmp_clean_task), (SELECT COUNT(*) FROM task_task)
UNION ALL SELECT 'todo_item', (SELECT COUNT(*) FROM tmp_clean_todo), (SELECT COUNT(*) FROM todo_item)
UNION ALL SELECT 'team_team', (SELECT COUNT(*) FROM tmp_clean_team), (SELECT COUNT(*) FROM team_team)
UNION ALL SELECT 'team_member', (SELECT COUNT(*) FROM team_member WHERE team_id IN (SELECT id FROM tmp_clean_team)), (SELECT COUNT(*) FROM team_member)
UNION ALL SELECT 'okr_align', (SELECT COUNT(*) FROM okr_align WHERE child_objective_id IN (SELECT id FROM tmp_clean_objective) OR parent_objective_id IN (SELECT id FROM tmp_clean_objective)), (SELECT COUNT(*) FROM okr_align)
UNION ALL SELECT 'okr_progress_log', (SELECT COUNT(*) FROM okr_progress_log WHERE kr_id IN (SELECT id FROM tmp_clean_kr) OR objective_id IN (SELECT id FROM tmp_clean_objective)), (SELECT COUNT(*) FROM okr_progress_log)
UNION ALL SELECT 'okr_kr_task_link', (SELECT COUNT(*) FROM okr_kr_task_link WHERE kr_id IN (SELECT id FROM tmp_clean_kr) OR task_id IN (SELECT id FROM tmp_clean_task)), (SELECT COUNT(*) FROM okr_kr_task_link)
UNION ALL SELECT 'okr_task_event_log', (SELECT COUNT(*) FROM okr_task_event_log WHERE kr_id IN (SELECT id FROM tmp_clean_kr) OR task_id IN (SELECT id FROM tmp_clean_task)), (SELECT COUNT(*) FROM okr_task_event_log)
UNION ALL SELECT 'task_assignee', (SELECT COUNT(*) FROM task_assignee WHERE task_id IN (SELECT id FROM tmp_clean_task)), (SELECT COUNT(*) FROM task_assignee)
UNION ALL SELECT 'task_relation', (SELECT COUNT(*) FROM task_relation WHERE task_id IN (SELECT id FROM tmp_clean_task) OR related_task_id IN (SELECT id FROM tmp_clean_task)), (SELECT COUNT(*) FROM task_relation)
UNION ALL SELECT 'todo_handle_log', (SELECT COUNT(*) FROM todo_handle_log WHERE todo_id IN (SELECT id FROM tmp_clean_todo) OR biz_id IN (SELECT id FROM tmp_clean_task)), (SELECT COUNT(*) FROM todo_handle_log)
UNION ALL SELECT 'flow_definition', (SELECT COUNT(*) FROM tmp_clean_flow_def), (SELECT COUNT(*) FROM flow_definition)
UNION ALL SELECT 'flow_instance', (SELECT COUNT(*) FROM tmp_clean_flow_inst), (SELECT COUNT(*) FROM flow_instance)
UNION ALL SELECT 'flow_node_instance', (SELECT COUNT(*) FROM flow_node_instance WHERE instance_id IN (SELECT id FROM tmp_clean_flow_inst)), (SELECT COUNT(*) FROM flow_node_instance)
UNION ALL SELECT 'flow_task', (SELECT COUNT(*) FROM flow_task WHERE instance_id IN (SELECT id FROM tmp_clean_flow_inst) OR biz_id IN (SELECT id FROM tmp_clean_task)), (SELECT COUNT(*) FROM flow_task);
"@

$deleteSql = $collectSql + @"

/* ---- delete in one transaction (child -> parent); ROW_COUNT() reports affected rows ---- */
START TRANSACTION;

DELETE FROM okr_progress_log WHERE kr_id IN (SELECT id FROM tmp_clean_kr) OR objective_id IN (SELECT id FROM tmp_clean_objective);
SELECT 'deleted:okr_progress_log', ROW_COUNT();
DELETE FROM okr_kr_task_link WHERE kr_id IN (SELECT id FROM tmp_clean_kr) OR task_id IN (SELECT id FROM tmp_clean_task);
SELECT 'deleted:okr_kr_task_link', ROW_COUNT();
DELETE FROM okr_task_event_log WHERE kr_id IN (SELECT id FROM tmp_clean_kr) OR task_id IN (SELECT id FROM tmp_clean_task);
SELECT 'deleted:okr_task_event_log', ROW_COUNT();
DELETE FROM okr_align WHERE child_objective_id IN (SELECT id FROM tmp_clean_objective) OR parent_objective_id IN (SELECT id FROM tmp_clean_objective);
SELECT 'deleted:okr_align', ROW_COUNT();

DELETE FROM todo_handle_log WHERE todo_id IN (SELECT id FROM tmp_clean_todo) OR biz_id IN (SELECT id FROM tmp_clean_task);
SELECT 'deleted:todo_handle_log', ROW_COUNT();
DELETE FROM todo_item WHERE id IN (SELECT id FROM tmp_clean_todo);
SELECT 'deleted:todo_item', ROW_COUNT();

DELETE FROM task_assignee WHERE task_id IN (SELECT id FROM tmp_clean_task);
SELECT 'deleted:task_assignee', ROW_COUNT();
DELETE FROM task_relation WHERE task_id IN (SELECT id FROM tmp_clean_task) OR related_task_id IN (SELECT id FROM tmp_clean_task);
SELECT 'deleted:task_relation', ROW_COUNT();

DELETE FROM flow_node_instance WHERE instance_id IN (SELECT id FROM tmp_clean_flow_inst);
SELECT 'deleted:flow_node_instance', ROW_COUNT();
DELETE FROM flow_task WHERE instance_id IN (SELECT id FROM tmp_clean_flow_inst) OR biz_id IN (SELECT id FROM tmp_clean_task);
SELECT 'deleted:flow_task', ROW_COUNT();
DELETE FROM flow_instance WHERE id IN (SELECT id FROM tmp_clean_flow_inst);
SELECT 'deleted:flow_instance', ROW_COUNT();
DELETE FROM flow_definition WHERE id IN (SELECT id FROM tmp_clean_flow_def);
SELECT 'deleted:flow_definition', ROW_COUNT();

DELETE FROM task_task WHERE id IN (SELECT id FROM tmp_clean_task);
SELECT 'deleted:task_task', ROW_COUNT();
DELETE FROM okr_key_result WHERE id IN (SELECT id FROM tmp_clean_kr);
SELECT 'deleted:okr_key_result', ROW_COUNT();
DELETE FROM okr_objective WHERE id IN (SELECT id FROM tmp_clean_objective);
SELECT 'deleted:okr_objective', ROW_COUNT();
DELETE FROM okr_period WHERE id IN (SELECT id FROM tmp_clean_period);
SELECT 'deleted:okr_period', ROW_COUNT();

DELETE FROM team_member WHERE team_id IN (SELECT id FROM tmp_clean_team);
SELECT 'deleted:team_member', ROW_COUNT();
DELETE FROM team_team WHERE id IN (SELECT id FROM tmp_clean_team);
SELECT 'deleted:team_team', ROW_COUNT();

COMMIT;

/* ---- post-check ---- */
SELECT 'check:flyway_schema_history', COUNT(*) FROM flyway_schema_history
UNION ALL SELECT 'check:sys_user', COUNT(*) FROM sys_user
UNION ALL SELECT 'check:sys_role', COUNT(*) FROM sys_role
UNION ALL SELECT 'check:sys_menu', COUNT(*) FROM sys_menu
UNION ALL SELECT 'check:sys_role_menu', COUNT(*) FROM sys_role_menu
UNION ALL SELECT 'check:sys_dict_data', COUNT(*) FROM sys_dict_data
UNION ALL SELECT 'check:sys_config', COUNT(*) FROM sys_config
UNION ALL SELECT 'check:perf_template', COUNT(*) FROM perf_template
UNION ALL SELECT 'check:perf_template_item', COUNT(*) FROM perf_template_item
UNION ALL SELECT 'check:perf_cycle', COUNT(*) FROM perf_cycle
UNION ALL SELECT 'remain:okr_period', COUNT(*) FROM okr_period
UNION ALL SELECT 'remain:okr_objective', COUNT(*) FROM okr_objective
UNION ALL SELECT 'remain:okr_key_result', COUNT(*) FROM okr_key_result
UNION ALL SELECT 'remain:task_task', COUNT(*) FROM task_task
UNION ALL SELECT 'remain:todo_item', COUNT(*) FROM todo_item
UNION ALL SELECT 'remain:team_team', COUNT(*) FROM team_team
UNION ALL SELECT 'remain:team_member', COUNT(*) FROM team_member
UNION ALL SELECT 'remain:flow_definition', COUNT(*) FROM flow_definition;
"@

# ---------------------------------------------------------------------------
# Baseline (protected tables) - used to prove sys_*/flyway are untouched
# ---------------------------------------------------------------------------
$baselineSql = @"
SELECT 'flyway_schema_history', COUNT(*) FROM flyway_schema_history
UNION ALL SELECT 'sys_user', COUNT(*) FROM sys_user
UNION ALL SELECT 'sys_role', COUNT(*) FROM sys_role
UNION ALL SELECT 'sys_menu', COUNT(*) FROM sys_menu
UNION ALL SELECT 'sys_role_menu', COUNT(*) FROM sys_role_menu
UNION ALL SELECT 'sys_dict_data', COUNT(*) FROM sys_dict_data
UNION ALL SELECT 'sys_config', COUNT(*) FROM sys_config
UNION ALL SELECT 'perf_template', COUNT(*) FROM perf_template
UNION ALL SELECT 'perf_template_item', COUNT(*) FROM perf_template_item
UNION ALL SELECT 'perf_cycle', COUNT(*) FROM perf_cycle;
"@

Write-Section 'L-1 cleanup: TeamFlow development database test residue'
Write-Host ("database   : {0}" -f $Database)
Write-Host ("mysql      : {0}" -f $mysqlExe)
Write-Host ("mysqldump  : {0}" -f $mysqldumpExe)
Write-Host ("mode       : {0}" -f $(if ($Execute) { 'EXECUTE (backup + delete)' } else { 'DRY-RUN (no changes)' }))

$baselineResult = Invoke-SqlText $baselineSql 'baseline'
if ($baselineResult.ExitCode -ne 0) {
    Write-Host '[FAIL] cannot read baseline counts:'
    $baselineResult.Output | ForEach-Object { Write-Host ('  ' + $_) }
    exit 1
}
$baseline = @{}
foreach ($line in $baselineResult.Output) {
    $parts = ([string]$line).Split("`t")
    if ($parts.Count -ge 2) { $baseline[$parts[0]] = [long]$parts[1] }
}

Write-Section 'DRY-RUN statistics (explicit naming patterns only)'
$statsResult = Invoke-SqlText $statsSql 'stats'
if ($statsResult.ExitCode -ne 0) {
    Write-Host '[FAIL] statistics failed:'
    $statsResult.Output | ForEach-Object { Write-Host ('  ' + $_) }
    exit 1
}
$rows = @()
foreach ($line in $statsResult.Output) {
    $parts = ([string]$line).Split("`t")
    if ($parts.Count -ge 3) {
        $rows += [pscustomobject]@{
            Table      = $parts[0]
            WouldDelete = [long]$parts[1]
            Total      = [long]$parts[2]
            Kept       = [long]$parts[2] - [long]$parts[1]
        }
    }
}
$rows | Format-Table -AutoSize | Out-String | Write-Host

$totalDelete = ($rows | Measure-Object -Property WouldDelete -Sum).Sum
Write-Host ("TOTAL rows that would be deleted: {0}" -f $totalDelete)
Write-Host ''
Write-Host 'Protected tables (never touched by this script): sys_*, gen_*, flyway_schema_history, perf_*'
Write-Host 'Patterns: IT- / E2E- / RB- / RB2 / M03- / M1- / V15- prefixes, 16-digit test suffix,'
Write-Host '          Chinese prefixes (acceptance / smoke / corrupted M1 names) matched via HEX(),'
Write-Host '          todo task_assign|task_approval|meeting_invite, flow_definition def_code it_*;'
Write-Host '          cascade: period->objective->KR->task->todo/flow; team->member'

if (-not $Execute) {
    Write-Section 'DRY-RUN completed - nothing was changed'
    Invoke-SqlText $dropSql 'drop-dryrun' | Out-Null
    Write-Host 'Re-run with -Execute to create a backup and delete the rows listed above.'
    exit 0
}

# ---------------------------------------------------------------------------
# EXECUTE: backup first, then delete
# ---------------------------------------------------------------------------
Write-Section 'Backup (mandatory before any delete)'
if (-not (Test-Path $BackupDir)) {
    New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
}
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backupFile = Join-Path $BackupDir ("teamflow-before-clean-" + $stamp + ".sql")
Write-Host ("dump target: {0}" -f $backupFile)

cmd /c "`"$mysqldumpExe`" -h $dbHost -u $dbUser --default-character-set=utf8mb4 --single-transaction --no-tablespaces --routines --triggers $Database > `"$backupFile`" 2>&1"
$dumpExit = $LASTEXITCODE
if ($dumpExit -ne 0 -or -not (Test-Path $backupFile)) {
    Write-Host ("[ABORT] mysqldump failed (exit {0}); nothing was deleted." -f $dumpExit)
    exit 1
}
$backupSize = (Get-Item $backupFile).Length
Write-Host ("backup ok  : {0} bytes ({1:N1} MB)" -f $backupSize, ($backupSize / 1MB))
if ($backupSize -lt 1024) {
    Write-Host '[ABORT] backup file is suspiciously small; nothing was deleted.'
    exit 1
}

Write-Section 'Executing cleanup (single transaction)'
$deleteResult = Invoke-SqlText $deleteSql 'delete'
$deleteResult.Output | ForEach-Object { Write-Host ('  ' + $_) }
if ($deleteResult.ExitCode -ne 0) {
    Write-Host ("[FAIL] cleanup SQL failed (exit {0}); the transaction was not committed (rolled back)." -f $deleteResult.ExitCode)
    exit 1
}

Write-Section 'Verification'
$after = @{}
foreach ($line in $deleteResult.Output) {
    $parts = ([string]$line).Split("`t")
    if ($parts.Count -ge 2 -and $parts[0] -like 'check:*') { $after[$parts[0].Substring(6)] = [long]$parts[1] }
    if ($parts.Count -ge 2 -and $parts[0] -like 'remain:*') { $after['remain:' + $parts[0].Substring(7)] = [long]$parts[1] }
}

$protectedChanged = $false
foreach ($key in @('flyway_schema_history','sys_user','sys_role','sys_menu','sys_role_menu','sys_dict_data','sys_config','perf_template','perf_template_item','perf_cycle')) {
    $beforeValue = if ($baseline.ContainsKey($key)) { $baseline[$key] } else { 'n/a' }
    $afterValue = if ($after.ContainsKey($key)) { $after[$key] } else { 'n/a' }
    $same = ($beforeValue -eq $afterValue)
    if (-not $same) { $protectedChanged = $true }
    Write-Host ("{0,-24} before={1,-6} after={2,-6} {3}" -f $key, $beforeValue, $afterValue, $(if ($same) { 'UNCHANGED' } else { 'CHANGED' }))
}

Write-Host ''
$remainingKeys = @('okr_period','okr_objective','okr_key_result','task_task','todo_item','team_team','team_member','flow_definition')
foreach ($key in $remainingKeys) {
    $value = if ($after.ContainsKey('remain:' + $key)) { $after['remain:' + $key] } else { 'n/a' }
    Write-Host ("remaining {0,-18} = {1}" -f $key, $value)
}

Write-Host ''
Write-Host ("backup file : {0}" -f $backupFile)
Write-Host ("backup size : {0} bytes" -f $backupSize)

if ($protectedChanged) {
    Invoke-SqlText $dropSql 'drop-fail' | Out-Null
    Write-Host '[FAIL] protected tables changed unexpectedly. Inspect the output above.'
    exit 1
}
Invoke-SqlText $dropSql 'drop-execute' | Out-Null
Write-Host '[OK] cleanup finished: test residue removed, sys_*/gen_*/flyway/perf preserved, backup created.'
exit 0
