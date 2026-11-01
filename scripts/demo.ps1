#requires -Version 5.1
<#
.SYNOPSIS
Создаёт демонстрационную команду через REST API и проверяет её сценарии.
.DESCRIPTION
Нужен запущенный TeamFlow на loopback-адресе. Каждый запуск создаёт новые данные.
Пароли и JWT сохраняются только в игнорируемом target/demo-<id>.json.
Параметр -NoSessionFile отключает сохранение сессии, например в CI.
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [ValidateRange(1, 300)][int]$WaitSeconds = 120,
    [switch]$NoSessionFile
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$baseUri = $null
if (-not [Uri]::TryCreate($BaseUrl, [UriKind]::Absolute, [ref]$baseUri) -or
    $baseUri.Scheme -notin @('http', 'https') -or -not $baseUri.IsLoopback -or
    $baseUri.AbsolutePath -ne '/' -or $baseUri.Query -or $baseUri.Fragment -or $baseUri.UserInfo) {
    throw 'BaseUrl должен быть локальным адресом вида http://localhost:8080 без пути и параметров.'
}
$BaseUrl = $BaseUrl.TrimEnd('/')
Add-Type -AssemblyName System.Net.Http
$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect = $false
$handler.UseProxy = $false
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(10)
$script:requestCount = 0

function Assert-Demo([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "Проверка демо не пройдена: $Message" }
}

function Invoke-DemoApi {
    param(
        [string]$Method = 'GET',
        [Parameter(Mandatory)][string]$Path,
        [string]$Token,
        [object]$Body,
        [int]$ExpectedStatus = 200
    )
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), "$BaseUrl$Path")
    $response = $null
    try {
        if ($Token) {
            $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $Token)
        }
        if ($null -ne $Body) {
            $request.Content = [System.Net.Http.StringContent]::new(
                (ConvertTo-Json -InputObject $Body -Depth 10 -Compress), [Text.Encoding]::UTF8, 'application/json')
        }
        try { $response = $client.SendAsync($request).GetAwaiter().GetResult() }
        catch { throw "Не удалось выполнить $Method $Path. Проверь запуск TeamFlow и BaseUrl." }
        $status = [int]$response.StatusCode
        if ($status -ne $ExpectedStatus) {
            # Не включаем тело ответа: auth-ответ может содержать JWT.
            throw "$Method $Path : ожидался HTTP $ExpectedStatus, получен $status."
        }
        $script:requestCount++
        $content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if ($content) {
            $payload = ConvertFrom-Json -InputObject $content
            if ($ExpectedStatus -ge 400) {
                Assert-Demo ($response.Content.Headers.ContentType.MediaType -eq 'application/problem+json') 'формат ошибки'
                Assert-Demo ($payload.status -eq $ExpectedStatus) 'код ошибки в ProblemDetail'
            }
            return $payload
        }
    } finally {
        if ($null -ne $response) { $response.Dispose() }
        $request.Dispose()
    }
}

function New-DemoPassword {
    $bytes = New-Object byte[] 24
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    return [Convert]::ToBase64String($bytes)
}

try {
    Write-Host '[1/7] Ожидание API, проверка health и OpenAPI...'
    $deadline = [DateTime]::UtcNow.AddSeconds($WaitSeconds)
    $ready = $false
    do {
        try {
            $health = Invoke-DemoApi -Path '/actuator/health'
            $ready = $health.status -eq 'UP'
        } catch { $ready = $false }
        if ($ready) { break }
        if ([DateTime]::UtcNow -ge $deadline) { throw "TeamFlow не готов на $BaseUrl. Проверь логи приложения." }
        Start-Sleep -Seconds 1
    } while ($true)
    $spec = Invoke-DemoApi -Path '/v3/api-docs'
    Assert-Demo ($spec.info.title -eq 'TeamFlow API') 'название OpenAPI'
    Assert-Demo ($spec.components.securitySchemes.bearerAuth.scheme -eq 'bearer') 'Bearer в OpenAPI'
    $null = Invoke-DemoApi -Path '/api/v1/workspaces' -ExpectedStatus 401

    Write-Host '[2/7] Регистрация администратора, участника и постороннего пользователя...'
    $runId = [Guid]::NewGuid().ToString('N')
    $accounts = @{}
    foreach ($role in @('admin', 'member', 'outsider')) {
        $password = New-DemoPassword
        $email = "$role.$runId@example.test"
        $auth = Invoke-DemoApi -Method POST -Path '/api/v1/auth/register' -ExpectedStatus 201 -Body @{
            email = $email; displayName = "Demo $role"; password = $password
        }
        $accounts[$role] = [ordered]@{ userId = $auth.user.id; email = $email; password = $password; accessToken = $auth.accessToken }
    }
    $admin = $accounts.admin
    $member = $accounts.member
    $login = Invoke-DemoApi -Method POST -Path '/api/v1/auth/login' -Body @{email = $admin.email; password = $admin.password}
    $admin.accessToken = $login.accessToken
    $me = Invoke-DemoApi -Path '/api/v1/auth/me' -Token $admin.accessToken
    Assert-Demo ($me.id -eq $admin.userId) 'вход администратора'

    Write-Host '[3/7] Команда, два проекта и права участников...'
    $workspace = Invoke-DemoApi -Method POST -Path '/api/v1/workspaces' -Token $admin.accessToken -ExpectedStatus 201 -Body @{
        name = "TeamFlow Demo $($runId.Substring(0, 8))"; description = 'Демонстрация Java backend: роли, задачи и аудит'
    }
    $workspacePath = "/api/v1/workspaces/$($workspace.id)"
    $null = Invoke-DemoApi -Method POST -Path "$workspacePath/members" -Token $admin.accessToken -ExpectedStatus 201 -Body @{
        email = $member.email; role = 'MEMBER'
    }
    $project = Invoke-DemoApi -Method POST -Path "$workspacePath/projects" -Token $admin.accessToken -ExpectedStatus 201 -Body @{
        name = 'Релиз TeamFlow'; description = 'Рабочий проект команды'
    }
    $privateProject = Invoke-DemoApi -Method POST -Path "$workspacePath/projects" -Token $admin.accessToken -ExpectedStatus 201 -Body @{
        name = 'Планирование'; description = 'Проект, доступный только администратору'
    }
    $projectPath = "$workspacePath/projects/$($project.id)"
    $tasksPath = "$projectPath/tasks"
    $null = Invoke-DemoApi -Method POST -Path "$projectPath/members" -Token $admin.accessToken -ExpectedStatus 201 -Body @{userId = $member.userId}
    $visible = Invoke-DemoApi -Path "$workspacePath/projects?size=1" -Token $member.accessToken
    Assert-Demo ($visible.totalElements -eq 1 -and $visible.content[0].id -eq $project.id) 'фильтрация проектов до пагинации'
    $null = Invoke-DemoApi -Path "$workspacePath/projects/$($privateProject.id)" -Token $member.accessToken -ExpectedStatus 404
    $null = Invoke-DemoApi -Path $workspacePath -Token $accounts.outsider.accessToken -ExpectedStatus 404
    $null = Invoke-DemoApi -Method POST -Path "$workspacePath/projects" -Token $member.accessToken -ExpectedStatus 403 -Body @{name = 'Запрещённый проект'}
    $null = Invoke-DemoApi -Method DELETE -Path "$workspacePath/members/$($admin.userId)" -Token $admin.accessToken -ExpectedStatus 409

    Write-Host '[4/7] Задачи, назначение исполнителя, фильтр и пагинация...'
    $taskIds = @()
    foreach ($title in @('Подготовить API релиза', 'Добавить примеры запросов', 'Проверить документацию')) {
        $task = Invoke-DemoApi -Method POST -Path $tasksPath -Token $admin.accessToken -ExpectedStatus 201 -Body @{
            title = $title; description = 'Задача из демонстрационного сценария'; status = 'TODO'; priority = 'HIGH'
            dueDate = [DateTime]::UtcNow.AddDays(7).ToString('yyyy-MM-dd')
        }
        $taskIds += $task.id
    }
    $taskPath = "$tasksPath/$($taskIds[0])"
    $assigned = Invoke-DemoApi -Method PUT -Path "$taskPath/assignee" -Token $admin.accessToken -Body @{userId = $member.userId}
    Assert-Demo ($assigned.assigneeId -eq $member.userId) 'исполнитель задачи'
    $filtered = Invoke-DemoApi -Path "$tasksPath`?assigneeId=$($member.userId)&status=TODO&priority=HIGH" -Token $member.accessToken
    Assert-Demo ($filtered.totalElements -eq 1 -and $filtered.content[0].id -eq $taskIds[0]) 'совместный фильтр задач'
    $firstPage = Invoke-DemoApi -Path "$tasksPath`?size=1&page=0" -Token $member.accessToken
    $secondPage = Invoke-DemoApi -Path "$tasksPath`?size=1&page=1" -Token $member.accessToken
    Assert-Demo ($firstPage.totalElements -eq 3 -and $firstPage.totalPages -eq 3 -and
        $firstPage.content[0].id -ne $secondPage.content[0].id) 'разные страницы задач'
    $null = Invoke-DemoApi -Method POST -Path $tasksPath -Token $member.accessToken -ExpectedStatus 400 -Body @{
        title = ''; status = 'TODO'; priority = 'HIGH'
    }

    Write-Host '[5/7] Комментарии, права автора и история TODO -> IN_PROGRESS -> DONE...'
    $comment = Invoke-DemoApi -Method POST -Path "$taskPath/comments" -Token $member.accessToken -ExpectedStatus 201 -Body @{body = 'Беру задачу в работу.'}
    $comment = Invoke-DemoApi -Method PUT -Path "$taskPath/comments/$($comment.id)" -Token $member.accessToken -Body @{body = 'API готов, проверяю сценарии.'}
    Assert-Demo ($comment.body -eq 'API готов, проверяю сценарии.' -and $comment.authorId -eq $member.userId) 'текст и автор комментария'
    $null = Invoke-DemoApi -Method PUT -Path "$taskPath/comments/$($comment.id)" -Token $admin.accessToken -ExpectedStatus 403 -Body @{body = 'Попытка изменить чужой текст'}
    $taskBody = @{title = 'Подготовить API релиза'; description = 'Проверено в демо'; status = 'IN_PROGRESS'; priority = 'HIGH'; dueDate = $assigned.dueDate}
    $null = Invoke-DemoApi -Method PUT -Path $taskPath -Token $member.accessToken -Body $taskBody
    $taskBody.status = 'DONE'
    $null = Invoke-DemoApi -Method PUT -Path $taskPath -Token $member.accessToken -Body $taskBody
    $updated = Invoke-DemoApi -Method PUT -Path $taskPath -Token $member.accessToken -Body $taskBody
    Assert-Demo ($updated.assigneeId -eq $member.userId) 'PUT задачи сохраняет исполнителя'
    $history = Invoke-DemoApi -Path "$taskPath/history" -Token $admin.accessToken
    Assert-Demo ($history.totalElements -eq 3) 'повторный DONE не создаёт событие'
    Assert-Demo ($history.content[0].eventType -eq 'CREATED' -and $history.content[0].toStatus -eq 'TODO' -and
        $history.content[1].fromStatus -eq 'TODO' -and $history.content[1].toStatus -eq 'IN_PROGRESS' -and
        $history.content[2].fromStatus -eq 'IN_PROGRESS' -and $history.content[2].toStatus -eq 'DONE') 'последовательность статусов'
    Assert-Demo ($history.content[0].actorId -eq $admin.userId -and
        $history.content[1].actorId -eq $member.userId -and $history.content[2].actorId -eq $member.userId) 'авторы событий'

    Write-Host '[6/7] Удаление участника снимает назначение и отзывает доступ...'
    $null = Invoke-DemoApi -Method DELETE -Path "$projectPath/members/$($member.userId)" -Token $admin.accessToken -ExpectedStatus 204
    $unassigned = Invoke-DemoApi -Path $taskPath -Token $admin.accessToken
    Assert-Demo ($null -eq $unassigned.assigneeId) 'снятие назначения'
    $null = Invoke-DemoApi -Path $taskPath -Token $member.accessToken -ExpectedStatus 404
    $comments = Invoke-DemoApi -Path "$taskPath/comments" -Token $admin.accessToken
    Assert-Demo ($comments.totalElements -eq 1 -and $comments.content[0].id -eq $comment.id) 'сохранение обсуждения'
    $retainedHistory = Invoke-DemoApi -Path "$taskPath/history" -Token $admin.accessToken
    Assert-Demo ($retainedHistory.totalElements -eq 3) 'сохранение истории'
    # Возвращаем участника, чтобы готовое демо можно было исследовать под обеими ролями.
    $null = Invoke-DemoApi -Method POST -Path "$projectPath/members" -Token $admin.accessToken -ExpectedStatus 201 -Body @{userId = $member.userId}
    $null = Invoke-DemoApi -Method PUT -Path "$tasksPath/$($taskIds[1])/assignee" -Token $admin.accessToken -Body @{userId = $member.userId}

    Write-Host '[7/7] Демо готово.'
    if (-not $NoSessionFile) {
        $outputDirectory = Join-Path (Split-Path -Parent $PSScriptRoot) 'target'
        $null = New-Item -ItemType Directory -Path $outputDirectory -Force
        $sessionPath = Join-Path $outputDirectory "demo-$runId.json"
        $session = [ordered]@{
            baseUrl = $BaseUrl; createdAt = [DateTime]::UtcNow.ToString('o'); workspaceId = $workspace.id
            projectId = $project.id; privateProjectId = $privateProject.id; taskIds = $taskIds
            workspacePath = $workspacePath; projectPath = $projectPath; tasksPath = $tasksPath
            admin = $admin; member = $member
        }
        [IO.File]::WriteAllText($sessionPath, (ConvertTo-Json -InputObject $session -Depth 10), [Text.UTF8Encoding]::new($false))
        Write-Host "Локальная сессия для Swagger и повторного входа: $sessionPath"
        Write-Host 'Файл содержит пароли и JWT. Он исключён из Git; не включай его в скриншоты и публикации.'
    }
    Write-Host "Успешно: $script:requestCount HTTP-проверок. Workspace: $($workspace.id)"
    Write-Host "Swagger: $BaseUrl/swagger-ui/index.html"
} finally {
    $client.Dispose()
}
