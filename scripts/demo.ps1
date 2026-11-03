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
$script:etags = @{}

function Assert-Demo([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "Проверка демо не пройдена: $Message" }
}

function Invoke-DemoApi {
    param(
        [string]$Method = 'GET',
        [Parameter(Mandatory)][string]$Path,
        [string]$Token,
        [object]$Body,
        [int]$ExpectedStatus = 200,
        [string]$IdempotencyKey,
        [string]$IfMatch,
        [switch]$SkipPrecondition
    )
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), "$BaseUrl$Path")
    $response = $null
    try {
        if ($Token) {
            $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $Token)
        }
        if ($IdempotencyKey) { $request.Headers.Add('Idempotency-Key', $IdempotencyKey) }
        if (-not $SkipPrecondition -and $Method -in @('PUT', 'DELETE') -and
            $Path -match '/(?:workspaces|projects|tasks|comments)/([0-9a-f-]{36})(?:/(?:assignee|archive))?$') {
            $resourceId = $Matches[1]
            $expectedTag = if ($IfMatch) { $IfMatch } else { $script:etags[$resourceId] }
            if ($expectedTag) { $request.Headers.Add('If-Match', $expectedTag) }
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
            if ($payload.PSObject.Properties['id'] -and $payload.PSObject.Properties['version'] -and $null -ne $response.Headers.ETag) {
                $script:etags[$payload.id] = $response.Headers.ETag.ToString()
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
        $accounts[$role] = [ordered]@{ userId = $auth.user.id; email = $email; password = $password; accessToken = $auth.accessToken; refreshToken = $auth.refreshToken }
    }
    $admin = $accounts.admin
    $member = $accounts.member
    $login = Invoke-DemoApi -Method POST -Path '/api/v1/auth/login' -Body @{email = $admin.email; password = $admin.password}
    $admin.accessToken = $login.accessToken
    $renewed = Invoke-DemoApi -Method POST -Path '/api/v1/auth/refresh' -Body @{token = $login.refreshToken}
    Assert-Demo ($renewed.sessionId -eq $login.sessionId -and $renewed.refreshToken -ne $login.refreshToken) 'ротация refresh token'
    $admin.accessToken = $renewed.accessToken
    $admin.refreshToken = $renewed.refreshToken
    $sessions = @(Invoke-DemoApi -Path '/api/v1/auth/sessions' -Token $admin.accessToken)
    Assert-Demo ($sessions.Count -eq 2) 'две активные сессии администратора'
    $me = Invoke-DemoApi -Path '/api/v1/auth/me' -Token $admin.accessToken
    Assert-Demo ($me.id -eq $admin.userId) 'вход администратора'

    Write-Host '[3/7] Команда, два проекта и права участников...'
    $workspace = Invoke-DemoApi -Method POST -Path '/api/v1/workspaces' -Token $admin.accessToken -ExpectedStatus 201 -Body @{
        name = "TeamFlow Demo $($runId.Substring(0, 8))"; description = 'Демонстрация Java backend: роли, задачи и аудит'
    }
    $workspacePath = "/api/v1/workspaces/$($workspace.id)"
    $invitation = Invoke-DemoApi -Method POST -Path "$workspacePath/invitations" -Token $admin.accessToken -ExpectedStatus 201 -Body @{
        email = $member.email; role = 'MEMBER'
    }
    $null = Invoke-DemoApi -Method POST -Path '/api/v1/invitations/accept' -Token $accounts.outsider.accessToken -Body @{token = $invitation.token} -ExpectedStatus 404
    $joined = Invoke-DemoApi -Method POST -Path '/api/v1/invitations/accept' -Token $member.accessToken -Body @{token = $invitation.token}
    Assert-Demo ($joined.userId -eq $member.userId -and $joined.role -eq 'MEMBER') 'подтверждение приглашения'
    $null = Invoke-DemoApi -Method POST -Path '/api/v1/invitations/accept' -Token $member.accessToken -Body @{token = $invitation.token} -ExpectedStatus 404
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
    $searched = Invoke-DemoApi -Path "$tasksPath`?q=API&size=1" -Token $member.accessToken
    Assert-Demo ($searched.totalElements -eq 1) 'поиск задач до пагинации'
    $lateTasks = Invoke-DemoApi -Path "$tasksPath`?overdue=true" -Token $member.accessToken
    Assert-Demo ($lateTasks.totalElements -eq 0) 'будущие сроки не считаются просроченными'
    $savedFilter = Invoke-DemoApi -Method POST -Path "$projectPath/filters" -Token $member.accessToken -ExpectedStatus 201 -Body @{name = 'Мои срочные'; priority = 'HIGH'; assigneeId = $member.userId}
    $personalFilters = @(Invoke-DemoApi -Path "$projectPath/filters" -Token $member.accessToken)
    Assert-Demo ($personalFilters.Count -eq 1 -and $personalFilters[0].id -eq $savedFilter.id) 'сохранение личного фильтра'
    $adminFilters = @(Invoke-DemoApi -Path "$projectPath/filters" -Token $admin.accessToken)
    Assert-Demo ($adminFilters.Count -eq 0) 'личные фильтры недоступны другому пользователю'
    $secondPage = Invoke-DemoApi -Path "$tasksPath`?size=1&page=1" -Token $member.accessToken
    Assert-Demo ($firstPage.totalElements -eq 3 -and $firstPage.totalPages -eq 3 -and
        $firstPage.content[0].id -ne $secondPage.content[0].id) 'разные страницы задач'
    $null = Invoke-DemoApi -Method POST -Path $tasksPath -Token $member.accessToken -ExpectedStatus 400 -Body @{
        title = ''; status = 'TODO'; priority = 'HIGH'
    }

    Write-Host '[5/7] Комментарии, права автора и история TODO -> IN_PROGRESS -> DONE...'
    $commentKey = [Guid]::NewGuid().ToString()
    $comment = Invoke-DemoApi -Method POST -Path "$taskPath/comments" -Token $member.accessToken -ExpectedStatus 201 -IdempotencyKey $commentKey -Body @{body = 'Беру задачу в работу.'}
    $repeatedComment = Invoke-DemoApi -Method POST -Path "$taskPath/comments" -Token $member.accessToken -ExpectedStatus 201 -IdempotencyKey $commentKey -Body @{body = 'Беру задачу в работу.'}
    Assert-Demo ($repeatedComment.id -eq $comment.id) 'повтор комментария не создаёт дубликат'
    $null = Invoke-DemoApi -Method POST -Path "$taskPath/comments" -Token $member.accessToken -ExpectedStatus 409 -IdempotencyKey $commentKey -Body @{body = 'Другое содержимое с тем же ключом'}
    $comment = Invoke-DemoApi -Method PUT -Path "$taskPath/comments/$($comment.id)" -Token $member.accessToken -Body @{body = 'API готов, проверяю сценарии.'}
    Assert-Demo ($comment.body -eq 'API готов, проверяю сценарии.' -and $comment.authorId -eq $member.userId) 'текст и автор комментария'
    $null = Invoke-DemoApi -Method PUT -Path "$taskPath/comments/$($comment.id)" -Token $admin.accessToken -ExpectedStatus 403 -Body @{body = 'Попытка изменить чужой текст'}
    $taskBody = @{title = 'Подготовить API релиза'; description = 'Проверено в демо'; status = 'IN_PROGRESS'; priority = 'HIGH'; dueDate = $assigned.dueDate}
    $oldTaskEtag = $script:etags[$taskIds[0]]
    $null = Invoke-DemoApi -Method PUT -Path $taskPath -Token $member.accessToken -Body $taskBody -SkipPrecondition -ExpectedStatus 428
    $null = Invoke-DemoApi -Method PUT -Path $taskPath -Token $member.accessToken -Body $taskBody
    $taskBody.status = 'DONE'
    $null = Invoke-DemoApi -Method PUT -Path $taskPath -Token $member.accessToken -Body $taskBody
    $updated = Invoke-DemoApi -Method PUT -Path $taskPath -Token $member.accessToken -Body $taskBody
    Assert-Demo ($updated.assigneeId -eq $member.userId) 'PUT задачи сохраняет исполнителя'
    $null = Invoke-DemoApi -Method PUT -Path $taskPath -Token $member.accessToken -Body $taskBody -IfMatch $oldTaskEtag -ExpectedStatus 412
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

    $archived = Invoke-DemoApi -Method PUT -Path "$projectPath/archive" -Token $admin.accessToken -Body @{archived = $true}
    Assert-Demo $archived.archived 'проект архивирован'
    $null = Invoke-DemoApi -Method PUT -Path $taskPath -Token $member.accessToken -Body $taskBody -ExpectedStatus 409
    $restored = Invoke-DemoApi -Method PUT -Path "$projectPath/archive" -Token $admin.accessToken -Body @{archived = $false}
    Assert-Demo (-not $restored.archived) 'проект восстановлен'

    $notificationDeadline = [DateTime]::UtcNow.AddSeconds(20)
    do {
        $notifications = Invoke-DemoApi -Path '/api/v1/notifications?unread=true' -Token $member.accessToken
        if (@($notifications.content | Where-Object { $_.taskId -eq $taskIds[1] }).Count -gt 0) { break }
        if ([DateTime]::UtcNow -ge $notificationDeadline) { throw 'Фоновая очередь не доставила уведомление за 20 секунд.' }
        Start-Sleep -Milliseconds 500
    } while ($true)
    Assert-Demo ($notifications.content[0].kind -eq 'ASSIGNED') 'уведомление о назначении'
    $null = Invoke-DemoApi -Method PUT -Path '/api/v1/notifications/read-all' -Token $member.accessToken -ExpectedStatus 204
    $unreadCount = Invoke-DemoApi -Path '/api/v1/notifications/unread-count' -Token $member.accessToken
    Assert-Demo ($unreadCount.count -eq 0) 'прочтение уведомлений'

    $outsiderRefresh = Invoke-DemoApi -Method POST -Path '/api/v1/auth/refresh' -Body @{token = $accounts.outsider.refreshToken}
    $null = Invoke-DemoApi -Method POST -Path '/api/v1/auth/refresh' -Body @{token = $accounts.outsider.refreshToken} -ExpectedStatus 401
    $null = Invoke-DemoApi -Path '/api/v1/auth/me' -Token $outsiderRefresh.accessToken -ExpectedStatus 401
    $outsiderLogin = Invoke-DemoApi -Method POST -Path '/api/v1/auth/login' -Body @{email = $accounts.outsider.email; password = $accounts.outsider.password}
    $null = Invoke-DemoApi -Method POST -Path '/api/v1/auth/logout' -Token $outsiderLogin.accessToken -ExpectedStatus 204
    $null = Invoke-DemoApi -Path '/api/v1/auth/me' -Token $outsiderLogin.accessToken -ExpectedStatus 401

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
