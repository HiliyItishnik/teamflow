#requires -Version 7.2
<# .SYNOPSIS
Собирает изолированное TLS-демо, проверяет health, права метрик и восстановление копии; удаляет только свои контейнеры и тома.
#>
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Push-Location (Split-Path -Parent $PSScriptRoot)
$id = [Guid]::NewGuid().ToString('N').Substring(0, 12)
$project = 'teamflow-deploy-check-' + $id
$variables = @('TEAMFLOW_DOMAIN', 'ACME_EMAIL', 'POSTGRES_PASSWORD', 'JWT_SECRET', 'OPERATIONS_KEY', 'HTTP_BIND', 'HTTP_PORT', 'HTTPS_PORT', 'HTTPS_CONTAINER_PORT', 'MANAGEMENT_PORT')
$previous = @{}
foreach ($name in $variables) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
function New-CheckSecret { return [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)) }
function Get-FreePort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { return $listener.LocalEndpoint.Port } finally { $listener.Stop() }
}
function Assert-Status($Response, [int]$Expected) {
    try { if ([int]$Response.StatusCode -ne $Expected) { throw "Ожидался HTTP $Expected, получен $([int]$Response.StatusCode)." } }
    finally { $Response.Dispose() }
}
function Invoke-CheckJson([string]$Path, [hashtable]$Body, [string]$Method = 'POST', [string]$Etag) {
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method), "$url$Path")
    $request.Content = [Net.Http.StringContent]::new(($Body | ConvertTo-Json), [Text.Encoding]::UTF8, 'application/json')
    if ($Etag) { $request.Headers.Add('If-Match', $Etag) }
    $response = $client.SendAsync($request).GetAwaiter().GetResult()
    try {
        if (-not $response.IsSuccessStatusCode) { throw "Сценарий восстановления: HTTP $([int]$response.StatusCode)." }
        return $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
    } finally { $response.Dispose(); $request.Dispose() }
}
$client = $null
$operator = $null
$ca = $null
$container = $null
$certPath = Join-Path 'target' "deployment-$id-ca.crt"
try {
    $env:HTTP_BIND = '127.0.0.1'
    $env:HTTP_PORT = [string](Get-FreePort)
    do { $env:HTTPS_PORT = [string](Get-FreePort) } while ($env:HTTPS_PORT -eq $env:HTTP_PORT)
    do { $env:MANAGEMENT_PORT = [string](Get-FreePort) } while ($env:MANAGEMENT_PORT -in @($env:HTTP_PORT, $env:HTTPS_PORT))
    $env:HTTPS_CONTAINER_PORT = $env:HTTPS_PORT
    $env:TEAMFLOW_DOMAIN = "localhost:$($env:HTTPS_PORT)"
    $env:ACME_EMAIL = 'local-check@example.test'
    $env:POSTGRES_PASSWORD = New-CheckSecret
    $env:JWT_SECRET = New-CheckSecret
    $env:OPERATIONS_KEY = New-CheckSecret
    docker compose --env-file deploy/.env.example -f deploy/compose.yml -p $project up --build -d *> "target/deployment-$id-build.log"
    if ($LASTEXITCODE -ne 0) { throw "Сборка не удалась: target/deployment-$id-build.log" }
    $container = docker compose --env-file deploy/.env.example -f deploy/compose.yml -p $project ps -q postgres
    $proxy = docker compose --env-file deploy/.env.example -f deploy/compose.yml -p $project ps -q caddy
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        & docker cp "${proxy}:/data/caddy/pki/authorities/local/root.crt" $certPath 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { break }
        Start-Sleep -Seconds 1
    }
    if ($LASTEXITCODE -ne 0) { throw 'Не удалось получить корневой сертификат локального Caddy.' }
    # Trust only this check's CA inside this HttpClient. Do not change the Windows/system trust store.
    $ca = [Security.Cryptography.X509Certificates.X509Certificate2]::new((Resolve-Path $certPath).Path)
    if (-not ('TeamFlow.DeploymentTls' -as [type])) {
        Add-Type -TypeDefinition @'
using System.Net.Http;
using System.Net.Security;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
namespace TeamFlow {
    public static class DeploymentTls {
        public static HttpClientHandler Create(X509Certificate2 root) {
            var handler = new HttpClientHandler { UseProxy = false, AllowAutoRedirect = false };
            handler.ServerCertificateCustomValidationCallback = (request, certificate, chain, errors) => {
                if (certificate == null || (errors & SslPolicyErrors.RemoteCertificateNameMismatch) != 0) return false;
                using (var validation = new X509Chain()) {
                    validation.ChainPolicy.TrustMode = X509ChainTrustMode.CustomRootTrust;
                    validation.ChainPolicy.CustomTrustStore.Add(root);
                    validation.ChainPolicy.RevocationMode = X509RevocationMode.NoCheck;
                    validation.ChainPolicy.ApplicationPolicy.Add(new Oid("1.3.6.1.5.5.7.3.1"));
                    if (chain != null) foreach (var element in chain.ChainElements) validation.ChainPolicy.ExtraStore.Add(element.Certificate);
                    return validation.Build(certificate);
                }
            };
            return handler;
        }
    }
}
'@
    }
    $handler = [TeamFlow.DeploymentTls]::Create($ca)
    $client = [Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(10)
    $operatorHandler = [Net.Http.HttpClientHandler]::new()
    $operatorHandler.UseProxy = $false
    $operatorHandler.AllowAutoRedirect = $false
    $operator = [Net.Http.HttpClient]::new($operatorHandler)
    $operator.Timeout = [TimeSpan]::FromSeconds(10)
    $url = "https://localhost:$($env:HTTPS_PORT)"
    $ready = $false
    for ($attempt = 0; $attempt -lt 120; $attempt++) {
        try {
            $response = $client.GetAsync("$url/actuator/health").GetAwaiter().GetResult()
            $ready = [int]$response.StatusCode -eq 200
            $response.Dispose()
        } catch { $ready = $false }
        if ($ready) { break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'TLS-демо не стало доступно за 120 секунд.' }
    $redirect = $operator.GetAsync("http://localhost:$($env:HTTP_PORT)/").GetAwaiter().GetResult()
    try {
        if ([int]$redirect.StatusCode -ne 308 -or $redirect.Headers.Location.Scheme -ne 'https' -or $redirect.Headers.Location.Port -ne [int]$env:HTTPS_PORT) {
            throw 'Не работает перенаправление HTTP в HTTPS.'
        }
    } finally { $redirect.Dispose() }
    Assert-Status ($client.GetAsync("$url/").GetAwaiter().GetResult()) 200
    Assert-Status ($client.GetAsync("$url/api/v1/workspaces").GetAwaiter().GetResult()) 401
    Assert-Status ($client.GetAsync("$url/actuator/prometheus").GetAwaiter().GetResult()) 404
    $metricsUrl = "http://127.0.0.1:$($env:MANAGEMENT_PORT)/actuator/prometheus"
    Assert-Status ($operator.GetAsync($metricsUrl).GetAwaiter().GetResult()) 401
    $operator.DefaultRequestHeaders.Add('X-Operations-Key', $env:OPERATIONS_KEY)
    Assert-Status ($operator.GetAsync($metricsUrl).GetAwaiter().GetResult()) 200
    $authBody = @{email="restore-$id@example.test"; displayName='Restore check'; password=(New-CheckSecret)} | ConvertTo-Json
    $response = $client.PostAsync("$url/api/v1/auth/register", [Net.Http.StringContent]::new($authBody, [Text.Encoding]::UTF8, 'application/json')).GetAwaiter().GetResult()
    try {
        if ([int]$response.StatusCode -ne 201) { throw 'Регистрация для проверки восстановления не удалась.' }
        $auth = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
        $client.DefaultRequestHeaders.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $auth.accessToken)
    } finally { $response.Dispose() }
    $workspace = Invoke-CheckJson '/api/v1/workspaces' @{ name='Проверка восстановления' }
    $workspacePath = "/api/v1/workspaces/$($workspace.id)"
    $peerEmail = "peer-$id@example.test"
    $peer = Invoke-CheckJson '/api/v1/auth/register' @{email=$peerEmail; displayName='Коллега'; password=(New-CheckSecret)}
    $null = Invoke-CheckJson "$workspacePath/members" @{email=$peerEmail; role='MEMBER'}
    $board = Invoke-CheckJson "$workspacePath/projects" @{name='Восстановленный проект'}
    $projectPath = "$workspacePath/projects/$($board.id)"
    $null = Invoke-CheckJson "$projectPath/members" @{userId=$peer.user.id}
    $task = Invoke-CheckJson "$projectPath/tasks" @{title='Задача из копии'; status='TODO'; priority='HIGH'}
    $null = Invoke-CheckJson "$projectPath/tasks/$($task.id)/assignee" @{userId=$peer.user.id} -Method PUT -Etag ('"{0}:{1}"' -f $task.id,$task.version)
    $null = Invoke-CheckJson "$projectPath/tasks/$($task.id)/comments" @{body='Комментарий из копии'}
    $backup = Join-Path 'target' "deployment-$id.dump"
    & (Join-Path $PSScriptRoot 'backup.ps1') -Container $container -Destination $backup
    $restored = & (Join-Path $PSScriptRoot 'verify-backup.ps1') -Container $container -Backup $backup -PassThru
    foreach ($entry in @{workspaces=1; projects=1; tasks=1; users=2; jobs=2; comments=1; history=1; projectMembers=2}.GetEnumerator()) {
        if ($restored.Counts[$entry.Key] -ne $entry.Value) { throw "Число восстановленных записей не совпало: $($entry.Key)." }
    }
    Write-Host 'TLS, интерфейс, защита API/метрик и восстановление PostgreSQL проверены.'
} finally {
    if ($client) { $client.Dispose() }
    if ($operator) { $operator.Dispose() }
    if ($ca) { $ca.Dispose() }
    # The project name is generated locally, never supplied by a caller.
    docker compose --env-file deploy/.env.example -f deploy/compose.yml -p $project down --volumes *> "target/deployment-$id-cleanup.log"
    $cleanupExit = $LASTEXITCODE
    foreach ($name in $variables) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    Pop-Location
    if ($cleanupExit -ne 0) { throw "Не завершена очистка $project; см. target/deployment-$id-cleanup.log." }
}
