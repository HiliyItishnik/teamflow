# TeamFlow

Учебная система управления командными проектами на **Java 21 + Spring Boot 3.5**.
Монолит с REST API, PostgreSQL, миграциями Flyway и разделением на controller → service → repository.

Реализованы первые два этапа: рабочие пространства, проекты, задачи, регистрация,
вход по JWT и роли участников. Локальный запуск и порты Docker Compose
по умолчанию доступны только на `127.0.0.1`.

## Что работает

- CRUD рабочих пространств, проектов и задач.
- Регистрация, вход, текущий пользователь, BCrypt и JWT Bearer.
- Роли `ADMIN` / `MEMBER` внутри рабочего пространства; управление участниками.
- Изоляция данных команд и защита последнего администратора, включая конкурентные изменения.
- Проверка принадлежности проекта рабочему пространству и задачи проекту для чтения и изменений.
- Статусы `TODO`, `IN_PROGRESS`, `DONE`; приоритеты `LOW`, `MEDIUM`, `HIGH`; срок выполнения.
- Пагинация всех списков и фильтрация задач по статусу и приоритету.
- DTO, Bean Validation, ошибки в формате `application/problem+json`.
- UUID, время создания/обновления в UTC, транзакции и optimistic locking при конкурентных транзакциях.
- PostgreSQL, Flyway, Swagger UI, Actuator health, Docker Compose и GitHub Actions.

## Быстрый запуск через Docker

Нужен запущенный Docker Desktop в режиме Linux containers. Java на компьютере для этого варианта не нужна.

```powershell
.\scripts\init-env.ps1
docker compose up --build -d
docker compose logs -f app
```

После запуска:

- Swagger UI: <http://localhost:8080/swagger-ui/index.html>
- OpenAPI JSON: <http://localhost:8080/v3/api-docs>
- Health: <http://localhost:8080/actuator/health>

Остановка: `docker compose down`. Данные сохраняются в Docker volume `postgres_data`.
Значения в `.env.example` предназначены для локальной разработки. `.env` исключён из Git.

`init-env.ps1` создаёт `.env`, генерирует случайный ключ подписи JWT из 32 байтов
и загружает его в текущую PowerShell-сессию. Повторный запуск сохраняет существующий ключ.
Не копируй `.env.example` поверх настроенного `.env`.

На Linux/macOS перед первым запуском скопируй `.env.example` в `.env` и задай
`JWT_SECRET`: `export JWT_SECRET="$(openssl rand -base64 32)"`.
Сохрани ключ в локальном `.env` или хранилище секретов, чтобы он не менялся после перезапуска.

## Запуск из IDE / PowerShell

Установи JDK 21 и укажи его в `JAVA_HOME` и Project SDK в IDE.
Отдельно устанавливать Maven не нужно: в проекте есть Maven Wrapper.
Открой `pom.xml` как Maven-проект в IntelliJ IDEA.

```powershell
# Подготовить .env и JWT_SECRET в текущем терминале
.\scripts\init-env.ps1

# Запустить только базу данных
docker compose up -d postgres

# Проверить Java, затем запустить приложение
java -version
.\mvnw.cmd spring-boot:run
```

На Linux/macOS используй `./mvnw` вместо `.\mvnw.cmd`.
При необходимости: `chmod +x mvnw`.

Настройки приложения можно переопределить переменными окружения:

| Переменная | Значение по умолчанию |
| --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/teamflow` |
| `DB_USERNAME` | `teamflow` |
| `DB_PASSWORD` | `teamflow_dev` |
| `SERVER_PORT` | `8080` |
| `SERVER_ADDRESS` | `127.0.0.1` |
| `JWT_SECRET` | Обязательный Base64-ключ: минимум 32 случайных байта |

Файл `.env` читает Docker Compose. Запуск через Maven или IDE не загружает его автоматически.
Скрипт `init-env.ps1` экспортирует `JWT_SECRET` в текущий терминал; в IDE укажи ключ
в переменных окружения Run Configuration (не сохраняй его в общие настройки проекта).
Если поменял параметры PostgreSQL в `.env`, передай соответствующие `DB_*` приложению:

```powershell
$env:DB_URL = 'jdbc:postgresql://localhost:5432/teamflow'
$env:DB_USERNAME = 'teamflow'
$env:DB_PASSWORD = 'teamflow_dev'
.\mvnw.cmd spring-boot:run
```

Можно использовать уже установленный PostgreSQL 17 вместо Docker: создай отдельную базу
`teamflow` и задай `DB_*`. Таблицы создаёт Flyway; Hibernate только проверяет схему
(`ddl-auto: validate`). Приложение не создаёт саму базу данных.

## Первый запрос

```powershell
$auth = Invoke-RestMethod -Method Post `
  -Uri 'http://localhost:8080/api/v1/auth/register' `
  -ContentType 'application/json; charset=utf-8' `
  -Body '{"email":"owner@example.test","displayName":"Owner","password":"Change-this-password-123"}'

$headers = @{ Authorization = "Bearer $($auth.accessToken)" }

$workspace = Invoke-RestMethod -Method Post `
  -Uri 'http://localhost:8080/api/v1/workspaces' `
  -Headers $headers `
  -ContentType 'application/json; charset=utf-8' `
  -Body '{"name":"My team","description":"Learning Java backend"}'

$project = Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/api/v1/workspaces/$($workspace.id)/projects" `
  -Headers $headers `
  -ContentType 'application/json; charset=utf-8' `
  -Body '{"name":"TeamFlow"}'

Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/api/v1/workspaces/$($workspace.id)/projects/$($project.id)/tasks" `
  -Headers $headers `
  -ContentType 'application/json; charset=utf-8' `
  -Body '{"title":"Build REST API","status":"TODO","priority":"HIGH"}'
```

В [requests/teamflow.http](requests/teamflow.http) есть последовательные примеры для IntelliJ HTTP Client.
Идентификаторы созданных ресурсов сохраняются автоматически в переменные следующих запросов.

## Авторизация и права

| Метод и путь | Назначение |
| --- | --- |
| `POST /api/v1/auth/register` | Email, displayName, password → пользователь и access token; `201` |
| `POST /api/v1/auth/login` | Email, password → access token; `200` |
| `GET /api/v1/auth/me` | Текущий пользователь; нужен Bearer token |

Email нормализуется: пробелы по краям удаляются, регистр приводится к нижнему.
Пароль: минимум 10 символов, максимум 72 байта UTF-8 (ограничение BCrypt).
Пароли не возвращаются в API; в БД хранится только BCrypt-хеш с cost 12.

Токен живёт 30 минут. Передавай `Authorization: Bearer <accessToken>`.
В Swagger нажми **Authorize** и вставь токен. После истечения срока выполни login повторно.
Refresh token и серверный logout пока не реализованы: клиент удаляет токен у себя,
но выданный токен действует до истечения срока. Замена ключа подписи инвалидирует все токены.
Сервер проверяет подпись HS256, issuer, audience, UUID subject и время действия.
Сессии и cookies для входа не используются.

| Действие | ADMIN | MEMBER |
| --- | --- | --- |
| Читать workspace, проекты, задачи, участников | Да | Да |
| Создавать, изменять и удалять задачи | Да | Да |
| Создавать, изменять и удалять проекты | Да | Нет |
| Изменять и удалять workspace | Да | Нет |
| Добавлять/удалять участников, менять роли | Да | Нет |

Создать workspace может любой вошедший пользователь: он становится `ADMIN`.
Список workspace содержит только команды текущего пользователя.
Чужая команда и её данные возвращают `404`; недостаток прав внутри своей команды — `403`;
отсутствующий/невалидный токен или неправильный пароль — `401`.
Роли загружаются из БД при запросах, поэтому удаление/понижение участника действует
сразу для следующих запросов с уже выданным токеном. Один пользователь может иметь разные роли в разных командах.

Участники рабочего пространства:

| Метод | Путь относительно `/api/v1/workspaces/{workspaceId}` | Тело |
| --- | --- | --- |
| `GET` | `/members?page=0&size=20` | — |
| `POST` | `/members` | `{"email":"member@example.test","role":"MEMBER"}` |
| `PUT` | `/members/{userId}` | `{"role":"ADMIN"}` |
| `DELETE` | `/members/{userId}` | — |

Добавляемый пользователь должен уже зарегистрироваться. Приглашения по email — следующий этап.
Повторное добавление участника возвращает `409`. Последнего администратора нельзя удалить
или понизить: `409`. Проверка и изменение выполняются под блокировкой строки workspace.
Участники отдельных проектов ещё не реализованы: сейчас участник workspace имеет доступ ко всем его проектам.

## REST API

| Ресурс | Базовый путь |
| --- | --- |
| Рабочие пространства | `/api/v1/workspaces` |
| Проекты | `/api/v1/workspaces/{workspaceId}/projects` |
| Задачи | `/api/v1/workspaces/{workspaceId}/projects/{projectId}/tasks` |

Для каждого базового пути доступны:

| Метод | Путь | Результат |
| --- | --- | --- |
| `POST` | базовый путь | Создание, `201 Created` и заголовок `Location` |
| `GET` | базовый путь | Список, `200 OK` |
| `GET` | базовый путь + `/{id}` | Одна запись, `200 OK` |
| `PUT` | базовый путь + `/{id}` | Полное обновление изменяемых полей, `200 OK` |
| `DELETE` | базовый путь + `/{id}` | Удаление, `204 No Content` |

`PUT` заменяет изменяемые поля: пропущенные необязательные значения очищаются.
Поля `id`, связи, `createdAt` и `updatedAt` назначает сервер. Неизвестные поля JSON отклоняются.
При создании и обновлении задачи обязательны `title`, `status` и `priority`.
Срок `dueDate` необязателен, формат `YYYY-MM-DD`; прошедшая дата разрешена для просроченных задач.

Параметры списков: `page` (с 0, по умолчанию 0) и `size` (1–100, по умолчанию 20).
Сортировка: `createdAt DESC, id DESC`. Задачи поддерживают `status` и `priority` одновременно:

```text
GET .../tasks?status=TODO&priority=HIGH&page=0&size=20
```

Списки возвращают `content`, `page`, `size`, `totalElements`, `totalPages`.
Несуществующий родитель или неправильная связь в URL дают `404`, некорректный запрос — `400`,
конфликт данных или конкурентных транзакций — `409`.

**Удаление рабочего пространства удаляет все его проекты и задачи.**
Удаление проекта удаляет все его задачи. Это обеспечивают внешние ключи PostgreSQL `ON DELETE CASCADE`.

## Проверки

```powershell
# Unit-тесты и тесты HTTP-валидации, Docker не нужен
.\mvnw.cmd test

# Сборка исполняемого JAR и быстрые тесты
.\mvnw.cmd verify

# Полная проверка с PostgreSQL через Testcontainers, Docker должен работать
.\mvnw.cmd verify -Pintegration
```

Профиль `integration` запускает `*IT`: миграции, CRUD, фильтры, каскадное удаление,
регистрацию/login, проверку настоящих JWT, права ролей, изоляцию команд,
конкурентное понижение администраторов, Swagger и health. Тестовый ключ генерируется в памяти;
тестам не нужен ключ из `.env`.
Если Docker недоступен, этот профиль завершится ошибкой, а не молча пропустит проверки.
В GitHub Actions запускается именно `verify -Pintegration`.

Для запуска тех же интеграционных тестов без Docker можно явно задать отдельную тестовую БД:

```powershell
$env:TEAMFLOW_TEST_DB_URL = 'jdbc:postgresql://localhost:55432/teamflow_test'
$env:TEAMFLOW_TEST_DB_USERNAME = 'teamflow_test'
$env:TEAMFLOW_TEST_DB_PASSWORD = 'your-test-password'
.\mvnw.cmd verify -Pintegration
```

**Указывай только одноразовую тестовую базу:** перед каждым интеграционным тестом таблицы
пользователей, рабочих пространств, участников, проектов и задач очищаются.
Обычные `DB_*` не выбирают базу для интеграционных тестов.

Результат сборки: `target/teamflow-0.1.0-SNAPSHOT.jar`.

## Структура и следующие шаги

```text
src/main/java/com/teamflow/
  common/       базовая сущность, пагинация, обработка ошибок
  config/       OpenAPI
  auth/         регистрация, вход и DTO
  security/     фильтры Spring Security, JWT, текущий пользователь
  user/         пользователи и хеши паролей
  membership/   роли и участники рабочих пространств
  workspace/    рабочие пространства: controller, service, repository, entity, DTO
  project/      проекты
  task/         задачи
src/main/resources/db/migration/   версии схемы PostgreSQL
src/test/java/com/teamflow/        unit, MVC и PostgreSQL integration tests
```

Подробности: [архитектура](docs/architecture.md) и [roadmap](docs/roadmap.md).
Следующий этап — участники проектов, исполнители задач, комментарии и история статусов.

## Обновление базы с первого этапа

Flyway применит `V2` поверх `V1` без удаления прежних проектов и задач.
У старых workspace нет владельца: автоматически назначать их первому зарегистрированному
пользователю небезопасно. Они остаются скрыты, пока владелец БД явно не добавит членство.
Для новой учебной команды достаточно зарегистрироваться и создать новый workspace.
Если нужно сохранить доступ к старым данным, пример ручного назначения администратора
приведён в [архитектуре](docs/architecture.md#перенос-данных-из-этапа-1).

Версии Java/Spring Boot и Springdoc выбраны по официальным таблицам совместимости:
[Spring Boot](https://docs.spring.io/spring-boot/3.5/system-requirements.html),
[Springdoc](https://springdoc.org/v2/).
Авторизация использует стандартные компоненты [Spring Security JWT](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html)
и [PasswordEncoder](https://docs.spring.io/spring-security/reference/6.5/features/authentication/password-storage.html).
