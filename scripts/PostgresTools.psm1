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
    $result = & docker @DockerArguments
    if ($LASTEXITCODE -ne 0) { throw "Ошибка Docker/PostgreSQL (код $LASTEXITCODE)." }
    return $result
}

Export-ModuleMember -Function Resolve-PostgresContainer, Invoke-PostgresDocker
