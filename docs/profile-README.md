# HiliyItishnik · Java Backend

Развиваюсь в Java backend: от консольных приложений и файлового хранения к REST API,
PostgreSQL, безопасности, транзакциям и тестированию.

**Практикую:** Java 21, Spring Boot, Spring Security, JPA, PostgreSQL, Flyway, Docker,
Maven, JUnit, Testcontainers и GitHub Actions.

## Проекты

| Проект | Что показывает |
| --- | --- |
| [TeamFlow](https://github.com/HiliyItishnik/teamflow) — командные проекты и задачи | Java 21 / Spring Boot: роли и изоляция команд, JWT-сессии с ротацией refresh token, транзакции, ETag и идемпотентность, очередь PostgreSQL, интеграционные тесты |
| [BookingFlow](https://github.com/HiliyItishnik/bookingflow) | Java 21 / Spring Boot 4: бронирование ресурсов, PostgreSQL range/exclusion constraints, отмена, конкурентные HTTP-запросы и Testcontainers |
| [Credit Payment Tracker](https://github.com/HiliyItishnik/credit-payment-tracker) | Spring Boot / PostgreSQL: кредиты и история платежей, расчёты BigDecimal, защита от повторной записи, миграции и API-тесты |
| [Java Core Task Manager](https://github.com/HiliyItishnik/java-core-task-manager) | Java Core: общий сервис для консольного и веб-интерфейса, коллекции, файлы, поиск и JUnit |
| [Java Console Calculator](https://github.com/HiliyItishnik/java-console-calculator) | Java 21: десятичная арифметика BigDecimal, проверка ввода, история, тесты и CI |

Проекты учебные. В README каждого открытого репозитория есть инструкции запуска,
описание ограничений и способы проверки.

Сейчас развиваю BookingFlow: первый этап с атомарным бронированием готов; далее — авторизация, перенос бронирований и календарь. В TeamFlow подготовлены диагностика, метрики, резервное восстановление и конфигурация HTTPS-демо.
