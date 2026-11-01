# TeamFlow project instructions

- Develop the Java 21 / Spring Boot monolith in this checkout. Keep the layered structure within feature packages.
- Use Maven Wrapper. Run `./mvnw test` for changes to business logic or API behavior.
- Run `./mvnw verify -Pintegration` for changes to persistence or the complete API. This requires Docker, or an explicitly configured disposable PostgreSQL database via `TEAMFLOW_TEST_DB_*`.
- Keep Flyway migrations as the source of schema changes; add a new migration after an existing one has been published.
- Keep documentation and examples in Russian; identifiers and commit messages may be in English.
- The user requested that substantial completed changes be committed and pushed to the GitHub repository. Validate each substantial change before committing and pushing. Do not include `.env`, generated artifacts, credentials, or local tool installations.
- Finish the current stage before expanding into later roadmap stages unless the user asks otherwise.
