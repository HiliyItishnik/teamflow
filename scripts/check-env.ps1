#requires -Version 5.1
<#
.SYNOPSIS
Проверяет окружение TeamFlow без изменения настроек компьютера и без вывода секретов.
#>
[CmdletBinding()]
param([ValidateSet('Docker', 'Local')][string]$Mode = 'Docker')

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$checks = [Collections.Generic.List[object]]::new()
$settings = @{}
$envPath = Join-Path $projectRoot '.env'
if (Test-Path -LiteralPath $envPath) {
    foreach ($line in [IO.File]::ReadAllLines($envPath)) {
        if ($line -match '^\s*([A-Z_]+)=(.*)$') { $settings[$Matches[1]] = $Matches[2].Trim().Trim('"').Trim("'") }
    }
}
function Add-Check([string]$Name, [string]$Status, [string]$Detail) {
    $checks.Add([pscustomobject]@{ Проверка = $Name; Статус = $Status; Подсказка = $Detail })
}
function Get-Setting([string]$Name, [string]$Default, [switch]$LocalOnly) {
    $value = [Environment]::GetEnvironmentVariable($Name)
    if (-not [string]::IsNullOrWhiteSpace($value)) { return $value }
    if (-not $LocalOnly -and $settings.ContainsKey($Name)) { return $settings[$Name] }
    return $Default
}
function Invoke-CheckCommand([string]$Executable, [string]$Arguments) {
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $Executable
    $info.Arguments = $Arguments
    $info.WorkingDirectory = $projectRoot
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $info
    try {
        $null = $process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(15000)) {
            $process.Kill()
            return @{ Success = $false; Output = '' }
        }
        return @{ Success = ($process.ExitCode -eq 0); Output = ($stdout.Result + $stderr.Result) }
    } catch { return @{ Success = $false; Output = '' } }
    finally { $process.Dispose() }
}
function Test-Port([string]$HostName, [int]$Port) {
    $socket = [Net.Sockets.TcpClient]::new()
    try { return $socket.ConnectAsync($HostName, $Port).Wait(2000) -and $socket.Connected }
    catch { return $false }
    finally { $socket.Dispose() }
}
function Check-ListeningPort([string]$Name, [string]$Value) {
    $port = 0
    if (-not [int]::TryParse($Value, [ref]$port) -or $port -lt 1 -or $port -gt 65535) {
        Add-Check $Name 'ERROR' 'Порт должен быть числом от 1 до 65535.'
    } elseif (Test-Port '127.0.0.1' $port) {
        Add-Check $Name 'WARN' "Порт $port занят: возможно, сервис уже запущен. Проверь его перед повторным запуском."
    } else { Add-Check $Name 'OK' "На 127.0.0.1:$port нет слушающего сервиса." }
}

$key = Get-Setting 'JWT_SECRET' '' -LocalOnly:($Mode -eq 'Local')
$validKey = $false
try { $validKey = [Convert]::FromBase64String($key).Length -ge 32 } catch {}
if ($validKey) { Add-Check 'JWT_SECRET' 'OK' 'Base64-ключ достаточной длины; значение скрыто.' }
else { Add-Check 'JWT_SECRET' 'ERROR' 'Выполни scripts/init-env.ps1 в этом терминале; нужен Base64-ключ минимум из 32 случайных байтов.' }

if ($Mode -eq 'Docker') {
    Add-Check 'Java' 'INFO' 'Для Docker-режима Java на компьютере не требуется.'
    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $docker) { Add-Check 'Docker' 'ERROR' 'Docker CLI не найден. Установи и запусти Docker Desktop с Linux containers.' }
    else {
        $engine = Invoke-CheckCommand $docker.Source 'info --format {{.OSType}}'
        if ($engine.Success -and $engine.Output.Trim() -eq 'linux') {
            Add-Check 'Docker Engine' 'OK' 'Linux-движок доступен.'
        } elseif ($engine.Success) {
            Add-Check 'Docker Engine' 'ERROR' 'Переключи Docker Desktop на Linux containers.'
        } else {
            Add-Check 'Docker Engine' 'ERROR' 'Нет подключения к движку за 15 секунд. Открой Docker Desktop и проверь сообщение о запуске.'
            if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) {
                try {
                    $cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
                    $computer = Get-CimInstance Win32_ComputerSystem
                    if (-not $computer.HypervisorPresent -and -not $cpu.VirtualizationFirmwareEnabled) {
                        Add-Check 'Виртуализация' 'ERROR' 'Windows сообщает, что виртуализация выключена. В BIOS/UEFI включи AMD SVM Mode или Intel VT-x.'
                    }
                } catch { Add-Check 'Виртуализация' 'INFO' 'Статус не удалось прочитать; проверь Диспетчер задач → Производительность → ЦП.' }
            }
        }
        $compose = Invoke-CheckCommand $docker.Source 'compose version --short'
        if ($compose.Success) { Add-Check 'Docker Compose' 'OK' 'Команда docker compose доступна.' }
        else { Add-Check 'Docker Compose' 'ERROR' 'Не удалось запустить docker compose version.' }
    }
    Check-ListeningPort 'Порт приложения' (Get-Setting 'APP_PORT' '8080')
    Check-ListeningPort 'Порт PostgreSQL' (Get-Setting 'POSTGRES_PORT' '5432')
} else {
    $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { '' }
    if ($env:JAVA_HOME -and [Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) { $java = Join-Path $env:JAVA_HOME 'bin/java' }
    if (-not $java) { $javaCommand = Get-Command java -ErrorAction SilentlyContinue; if ($javaCommand) { $java = $javaCommand.Source } }
    if ($java -and (Test-Path -LiteralPath $java)) {
        $version = Invoke-CheckCommand $java '-version'
        if ($version.Success -and $version.Output -match 'version "21[.\"]') { Add-Check 'Java' 'OK' 'Java 21 доступна.' }
        else { Add-Check 'Java' 'ERROR' 'Нужен JDK 21. Проверь JAVA_HOME и Java в терминале/IDE.' }
        $javacName = if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) { 'javac.exe' } else { 'javac' }
        if (-not (Test-Path -LiteralPath (Join-Path (Split-Path -Parent $java) $javacName))) {
            Add-Check 'Компилятор' 'ERROR' 'Рядом с java нет javac. Укажи JDK, а не только JRE.'
        }
    } else { Add-Check 'Java' 'ERROR' 'Java не найдена. Укажи путь к JDK 21 в JAVA_HOME.' }
    $dbUrl = Get-Setting 'DB_URL' 'jdbc:postgresql://localhost:5432/teamflow' -LocalOnly
    try {
        if (-not $dbUrl.StartsWith('jdbc:postgresql://')) { throw 'Unsupported URL' }
        $dbUri = [Uri]$dbUrl.Substring(5)
        $dbPort = if ($dbUri.Port -gt 0) { $dbUri.Port } else { 5432 }
        if (-not $dbUri.Host -or $dbUri.AbsolutePath -eq '/') { throw 'Missing database' }
        if (Test-Port $dbUri.Host $dbPort) {
            Add-Check 'PostgreSQL' 'OK' 'TCP-порт доступен. Логин, пароль, существование БД и миграции проверит приложение при старте.'
        } else { Add-Check 'PostgreSQL' 'ERROR' 'Порт из DB_URL недоступен. Запусти PostgreSQL и проверь адрес/порт.' }
    } catch { Add-Check 'DB_URL' 'ERROR' 'Ожидается jdbc:postgresql://host:port/database.' }
    Add-Check 'Настройки БД' 'INFO' 'Режим Local читает DB_* только из окружения. Maven и IDE не загружают .env автоматически.'
    Check-ListeningPort 'Порт приложения' (Get-Setting 'SERVER_PORT' '8080' -LocalOnly)
}
$checks | Format-Table -AutoSize -Wrap
if (@($checks | Where-Object { $_.Статус -eq 'ERROR' }).Count -gt 0) {
    throw 'Окружение не готово. Исправь строки ERROR и повтори проверку.'
}
Write-Host 'Обязательные проверки пройдены. WARN требуют проверки перед запуском; настройки компьютера не изменялись.'
