# TeamFlow

Учебная система управления командными проектами на **Java 21 + Spring Boot 3.5**.
Монолит с REST API, PostgreSQL, миграциями Flyway и разделением на controller → service → repository.

Реализована основа первого этапа: рабочие пространства, проекты и задачи. Регистрация,
JWT и участники появятся на следующих этапах. Сейчас API не требует авторизации;
локальный запуск и порты Docker Compose по умолчанию доступны только на `127.0.0.1`.

## Что работает

- CRUD рабочих пространств, проектов и задач.
- Проверка принадлежности проекта рабочему пространству и задачи проекту для чтения и изменений.
- Статусы `TODO`, `IN_PROGRESS`, `DONE`; приоритеты `LOW`, `MEDIUM`, `HIGH`; срок выполнения.
- Пагинация всех списков и фильтрация задач по статусу и приоритету.
- DTO, Bean Validation, ошибки в формате `application/problem+json`.
- UUID, время создания/обновления в UTC, транзакции и optimistic locking при конкурентных транзакциях.
- PostgreSQL, Flyway, Swagger UI, Actuator health, Docker Compose и GitHub Actions.

## Быстрый запуск через Docker

Нужен запущенный Docker Desktop в режиме Linux containers. Java на компьютере для этого варианта не нужна.

```powershell
Copy-Item .env.example .env
docker compose up --build -d
docker compose logs -f app
```

После запуска:

- Swagger UI: <http://localhost:8080/swagger-ui/index.html>
- OpenAPI JSON: <http://localhost:8080/v3/api-docs>
- Health: <http://localhost:8080/actuator/health>

Остановка: `docker compose down`. Данные сохраняются в Docker volume `postgres_data`.
Значения в `.env.example` предназначены для локальной разработки. `.env` исключён из Git.

## Запуск из IDE / PowerShell

Установи JDK 21 и укажи его в `JAVA_HOME` и Project SDK в IDE.
Отдельно устанавливать Maven не нужно: в проекте есть Maven Wrapper.
Открой `pom.xml` как Maven-проект в IntelliJ IDEA.

```powershell
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

Файл `.env` читает Docker Compose. Запуск через Maven или IDE не загружает его автоматически.
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
$workspace = Invoke-RestMethod -Method Post `
  -Uri 'http://localhost:8080/api/v1/workspaces' `
  -ContentType 'application/json; charset=utf-8' `
  -Body '{"name":"My team","description":"Learning Java backend"}'

$project = Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/api/v1/workspaces/$($workspace.id)/projects" `
  -ContentType 'application/json; charset=utf-8' `
  -Body '{"name":"TeamFlow"}'

Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/api/v1/workspaces/$($workspace.id)/projects/$($project.id)/tasks" `
  -ContentType 'application/json; charset=utf-8' `
  -Body '{"title":"Build REST API","status":"TODO","priority":"HIGH"}'
```

В [requests/teamflow.http](requests/teamflow.http) есть последовательные примеры для IntelliJ HTTP Client.
Идентификаторы созданных ресурсов сохраняются автоматически в переменные следующих запросов.

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

Профиль `integration` запускает `*IT`: применение миграций, полный CRUD, фильтры и пагинацию,
проверки связей при чтении/изменении, каскадное удаление, Swagger и health.
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
рабочих пространств, проектов и задач очищаются. Обычные `DB_*` не выбирают базу для интеграционных тестов.

Результат сборки: `target/teamflow-0.1.0-SNAPSHOT.jar`.

## Структура и следующие шаги

```text
src/main/java/com/teamflow/
  common/       базовая сущность, пагинация, обработка ошибок
  config/       OpenAPI
  workspace/    рабочие пространства: controller, service, repository, entity, DTO
  project/      проекты
  task/         задачи
src/main/resources/db/migration/   версии схемы PostgreSQL
src/test/java/com/teamflow/        unit, MVC и PostgreSQL integration tests
```

Подробности: [архитектура](docs/architecture.md) и [roadmap](docs/roadmap.md).
Следующий этап — пользователи, Spring Security, JWT и членство в рабочих пространствах.
Роли `ADMIN` / `MEMBER` будут принадлежать членству, а не пользователю глобально.

Версии Java/Spring Boot и Springdoc выбраны по официальным таблицам совместимости:
[Spring Boot](https://docs.spring.io/spring-boot/3.5/system-requirements.html),
[Springdoc](https://springdoc.org/v2/).
