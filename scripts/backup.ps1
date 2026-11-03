#requires -Version 5.1
<# .SYNOPSIS
Создаёт консистентную копию PostgreSQL в custom-формате и SHA-256 манифест.
#>
[CmdletBinding()]
param([string]$Container, [string]$Destination)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'PostgresTools.psm1') -Force
Push-Location (Split-Path -Parent $PSScriptRoot)
$temporary = '/tmp/teamflow-backup-' + [Guid]::NewGuid().ToString('N') + '.dump'
try {
    $Container = Resolve-PostgresContainer $Container
    if (-not $Destination) { $Destination = Join-Path 'backups' ("teamflow-{0}-{1}.dump" -f [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'), [Guid]::NewGuid().ToString('N').Substring(0, 8)) }
    $Destination = [IO.Path]::GetFullPath($Destination)
    if ((Test-Path -LiteralPath $Destination) -or (Test-Path -LiteralPath "$Destination.json")) { throw 'Файл копии или манифеста уже существует.' }
    $null = [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Destination))
    try {
        $null = Invoke-PostgresDocker @('exec', $Container, 'sh', '-c', 'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom --no-owner --no-acl --file="$1"', '--', $temporary)
        $null = Invoke-PostgresDocker @('cp', "${Container}:$temporary", $Destination)
        $version = Invoke-PostgresDocker @('exec', $Container, 'pg_dump', '--version')
        [ordered]@{ createdAtUtc = [DateTime]::UtcNow.ToString('o'); sha256 = (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash; pgDump = "$version" } |
            ConvertTo-Json | Set-Content -LiteralPath "$Destination.json" -Encoding UTF8
    } finally { & docker exec $Container rm -f -- $temporary | Out-Null }
    Write-Host "Копия: $Destination"
    Write-Host 'Проверка восстановления: .\scripts\verify-backup.ps1 -Backup <путь к .dump>'
} finally { Pop-Location }
