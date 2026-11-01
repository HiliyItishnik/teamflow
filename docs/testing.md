# Проверки TeamFlow

Базовая версия содержит **67 JUnit-тестов**: 24 unit/MVC и 43 интеграционных.
Это количество сценариев, а не процент покрытия кода.
В дополнение к ним `scripts/demo.ps1` проверяет пользовательский сценарий через реальный HTTP.

## Уровни проверки

| Набор | Количество | Что проверяет |
| --- | ---: | --- |
| `ApiValidationTest` | 13 | DTO, параметры списков, enum, даты и неизвестные JSON-поля |
| `PasswordValidatorTest` | 2 | Длина пароля в символах и байтах UTF-8 |
| `SecurityConfigTest` | 2 | Ключ JWT и допустимый срок токена |
| `TaskServiceTest` | 7 | Запрет создания и доступа к задаче через чужой workspace или другой проект |
| `TeamFlowApiIT` | 7 | CRUD, фильтры, пагинация, иерархия URL, каскады, health и OpenAPI |
| `SecurityApiIT` | 23 | JWT, роли, чужие команды, немедленный отзыв доступа и защита последнего ADMIN |
| `CollaborationApiIT` | 12 | Участники, исполнители, комментарии, аудит, внешние ключи и конкурентные изменения |
| `CollaborationMigrationIT` | 1 | Обновление существующей базы с `V2` на `V3` без потери доступа и задач |

Особые случаи: одновременное понижение администраторов, конкурирующие изменения статуса,
назначение во время удаления участника и сохранение устаревшей транзакции после его повторного добавления.
После гонок проверяются итоговые данные, а не только HTTP-коды.

## Команды

Нужен JDK 21; Maven скачивается Wrapper-скриптом.

```powershell
# Быстрые проверки, PostgreSQL и Docker не нужны
.\mvnw.cmd -B -ntp test

# Полная сборка и PostgreSQL integration tests
.\mvnw.cmd -B -ntp verify -Pintegration
```

На Linux/macOS используй `./mvnw`. По умолчанию профиль `integration` запускает
PostgreSQL 17 через Testcontainers. Если Docker недоступен, сборка падает;
интеграционные тесты не пропускаются автоматически.

Альтернатива — **отдельная одноразовая PostgreSQL 17**:

```powershell
$env:TEAMFLOW_TEST_DB_URL = 'jdbc:postgresql://localhost:55432/teamflow_test'
$env:TEAMFLOW_TEST_DB_USERNAME = 'teamflow_test'
$env:TEAMFLOW_TEST_DB_PASSWORD = 'your-test-password'
.\mvnw.cmd -B -ntp verify -Pintegration
```

Тесты очищают таблицы через `TRUNCATE ... CASCADE`. Не указывай рабочую или демонстрационную базу.
Для теста миграции роль также должна иметь право создавать схему в тестовой базе.
JWT-ключ создаётся в памяти; `.env` тестам не нужен. Настройки `DB_*` тесты не выбирают.
После работы убери `TEAMFLOW_TEST_DB_*` из сессии, чтобы снова использовать Testcontainers.

Отчёты Maven находятся в `target/surefire-reports/` и `target/failsafe-reports/`.
`verify` без `-Pintegration` не проверяет PostgreSQL.

## CI

Workflow [Build and test](https://github.com/HiliyItishnik/teamflow/actions/workflows/ci.yml)
запускается на push и pull request:

1. JDK 21, `verify -Pintegration`, PostgreSQL в Testcontainers.
2. Сборка Docker-образа и запуск приложения с отдельной PostgreSQL через Compose.
3. `scripts/demo.ps1 -NoSessionFile` в PowerShell: готовность сервиса, регистрация,
   роли, изоляция проектов, задачи, комментарии, история и отзыв доступа.
4. Логи Compose при ошибке и остановка контейнеров в любом исходе.

Ключ JWT генерируется для запуска. Токены и пароли демо не выводятся и не сохраняются в CI.
Сценарий локального показа и CI использует один скрипт, поэтому изменения API проверяются
также на совместимость с инструкцией демонстрации.

## Если запуск не удался

| Симптом | Что проверить |
| --- | --- |
| `Docker` / Testcontainers не находит daemon | Запущен ли Docker Desktop, выбраны ли Linux containers; выполни `docker info` |
| `JWT_SECRET must contain...` или Compose требует ключ | Запусти `scripts/init-env.ps1` в текущем терминале; для IDE настрой переменную Run Configuration |
| `Connection refused` к PostgreSQL | `docker compose ps`, `DB_URL`, порт и учётные данные; база должна существовать |
| Порт занят | Измени `APP_PORT` / `POSTGRES_PORT` в `.env`; для демо передай соответствующий `-BaseUrl` |
| Демо не дождалось API | `docker compose logs --tail 100 app`; при медленном старте увеличь `-WaitSeconds` |
| PowerShell пишет `running scripts is disabled` | Проверь `Get-ExecutionPolicy -List`; для проверенных локальных скриптов можно открыть отдельную сессию `powershell.exe -NoProfile -ExecutionPolicy RemoteSigned`. Выполни команды в ней; постоянные настройки не изменяются, групповые политики сохраняют приоритет |
| `401` при ручном показе | JWT живёт 30 минут; повтори login из `docs/demo.md` |
| Flyway checksum mismatch | Проверь локальные изменения опубликованных миграций; новые изменения схемы оформляются следующей миграцией |

Поведение политик выполнения описано в [документации PowerShell](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_execution_policies).

## Границы проверок

JUnit проверяет бизнес-логику, API и PostgreSQL; демо — собранное приложение через HTTP.
Нагрузочные тесты, проверка внешнего frontend, доставки email и production-инфраструктуры
не входят в базовую версию. Успешный CI подтверждает эти сценарии, но не заменяет проверку
конфигурации при публичном развёртывании.
