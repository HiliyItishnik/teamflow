#requires -Version 5.1
Set-StrictMode -Version Latest

function Resolve-PostgresContainer([string]$Container) {
    if (-not $Container) {
        $Container = docker compose ps -q postgres
        if ($LASTEXITCODE -ne 0 -or -not $Container) { throw 'Не найден запущенный postgres текущего Docker Compose.' }
    }
    if ($Container -notmatch '^[A-Za-z0-9][A-Za-z0-9_.-]*$') { throw 'Некорректный идентификатор контейнера PostgreSQL.' }
    return $Container
}

function Invoke-PostgresDocker([string[]]$DockerArguments) {
    # Windows PowerShell 5.1 / older PowerShell remove embedded quotes when invoking native tools.
    $passing = Get-Variable PSNativeCommandArgumentPassing -ValueOnly -ErrorAction SilentlyContinue
    if ($PSVersionTable.PSVersion -lt [version]'7.3' -or $passing -eq 'Legacy') {
        $DockerArguments = @($DockerArguments | ForEach-Object { $_.Replace('"', '\"') })
    }
    $result = & docker @DockerArguments
    if ($LASTEXITCODE -ne 0) { throw "Ошибка Docker/PostgreSQL (код $LASTEXITCODE)." }
    return $result
}

Export-ModuleMember -Function Resolve-PostgresContainer, Invoke-PostgresDocker
