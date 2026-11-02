#requires -Version 5.1
[CmdletBinding()]
param([ValidateSet('Docker', 'Local')][string]$Mode = 'Docker')

$ErrorActionPreference = 'Stop'
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    & (Join-Path $PSScriptRoot 'init-env.ps1')
    & (Join-Path $PSScriptRoot 'check-env.ps1') -Mode $Mode
    if ($Mode -eq 'Docker') {
        docker compose up --build -d
    } else {
        $wrapper = if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) { '.\mvnw.cmd' } else { './mvnw' }
        & $wrapper spring-boot:run
    }
    if ($LASTEXITCODE -ne 0) { throw 'Запуск завершился ошибкой. Проверь вывод Docker или Maven выше.' }
} finally { Pop-Location }
