#requires -Version 5.1
<# .SYNOPSIS
Восстанавливает копию в новую случайную БД, проверяет схему и данные, удаляет только свою проверочную БД.
#>
[CmdletBinding()]
param([Parameter(Mandatory)][string]$Backup, [string]$Container, [switch]$PassThru)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'PostgresTools.psm1') -Force
$Backup = (Resolve-Path -LiteralPath $Backup).Path
$manifest = Get-Content -LiteralPath "$Backup.json" -Raw | ConvertFrom-Json
if ($manifest.sha256 -ne (Get-FileHash -LiteralPath $Backup -Algorithm SHA256).Hash) { throw 'SHA-256 копии не совпадает с манифестом.' }
$id = [Guid]::NewGuid().ToString('N')
$database = 'teamflow_restore_' + $id
$temporary = '/tmp/teamflow-restore-' + $id + '.dump'
$created = $false
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    $Container = Resolve-PostgresContainer $Container
    try {
        $null = Invoke-PostgresDocker @('exec', $Container, 'sh', '-c', 'exec createdb -U "$POSTGRES_USER" --template=template0 "$1"', '--', $database)
        $created = $true
        $null = Invoke-PostgresDocker @('cp', $Backup, "${Container}:$temporary")
        $null = Invoke-PostgresDocker @('exec', $Container, 'sh', '-c', 'exec pg_restore -U "$POSTGRES_USER" -d "$1" --exit-on-error --single-transaction --no-owner --no-acl "$2"', '--', $database, $temporary)
        $sql = @'
SELECT CASE WHEN EXISTS (SELECT 1 FROM flyway_schema_history WHERE NOT success)
 OR EXISTS (SELECT 1 FROM pg_constraint WHERE connamespace = 'public'::regnamespace AND NOT convalidated)
 OR EXISTS (SELECT 1 FROM tasks t LEFT JOIN projects p ON p.id=t.project_id WHERE p.id IS NULL)
 OR EXISTS (SELECT 1 FROM projects p LEFT JOIN workspaces w ON w.id=p.workspace_id WHERE w.id IS NULL)
 OR EXISTS (SELECT 1 FROM workspace_members m LEFT JOIN users u ON u.id=m.user_id WHERE u.id IS NULL)
 OR EXISTS (SELECT 1 FROM tasks t WHERE t.assignee_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM project_members m WHERE m.project_id=t.project_id AND m.user_id=t.assignee_id))
 THEN 'INVALID' ELSE 'OK' END;
SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1;
SELECT 'workspaces=' || count(*) FROM workspaces;
SELECT 'projects=' || count(*) FROM projects;
SELECT 'tasks=' || count(*) FROM tasks;
SELECT 'users=' || count(*) FROM users;
SELECT 'jobs=' || count(*) FROM background_jobs;
SELECT 'comments=' || count(*) FROM task_comments;
SELECT 'history=' || count(*) FROM task_status_history;
SELECT 'projectMembers=' || count(*) FROM project_members;
'@
        $result = @(Invoke-PostgresDocker @('exec', $Container, 'sh', '-c', 'exec psql -X -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1 -A -t -c "$2"', '--', $database, $sql))
        $latest = (Get-ChildItem (Join-Path $PSScriptRoot '../src/main/resources/db/migration') -Filter 'V*__*.sql' |
            ForEach-Object { [int]($_.Name -replace '^V(\d+)__.*$', '$1') } | Measure-Object -Maximum).Maximum
        if ($result.Count -lt 10 -or $result[0] -ne 'OK' -or $result[1] -ne "$latest") { throw 'Восстановленная схема или связи не прошли проверку. Нужна копия текущей версии миграций.' }
        Write-Host 'Восстановление успешно; схема Flyway, ограничения и связи проверены.'
        $result | Select-Object -Skip 2 | ForEach-Object { Write-Host $_ }
        if ($PassThru) {
            $counts = @{}
            $result | Select-Object -Skip 2 | ForEach-Object { $pair = $_ -split '=', 2; $counts[$pair[0]] = [long]$pair[1] }
            [pscustomobject]@{ MigrationVersion = [int]$latest; Counts = $counts }
        }
    } finally {
        $cleanupFailed = $false
        & docker exec $Container rm -f -- $temporary | Out-Null
        if ($LASTEXITCODE -ne 0) { $cleanupFailed = $true }
        if ($created) {
            & docker exec $Container sh -c 'exec dropdb -U "$POSTGRES_USER" "$1"' -- $database | Out-Null
            if ($LASTEXITCODE -ne 0) { $cleanupFailed = $true }
        }
        if ($cleanupFailed) { throw "Очистка проверки не завершена: контейнер $Container, временная БД $database, файл $temporary." }
    }
} finally { Pop-Location }
