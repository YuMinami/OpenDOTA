# Repository Guidelines

## Project Structure & Module Organization

`opendota-server/` is the Maven parent project. The only runnable module is `opendota-app/`; shared protocol types live in `opendota-common/`; MQTT transport is in `opendota-mqtt/`; REST and dispatch flow are in `opendota-diag/`. Supporting modules include `opendota-task/`, `opendota-security/`, `opendota-observability/`, `opendota-admin/`, and `opendota-odx/`.

Repository-level docs in `doc/` are the source of truth for protocol, architecture, REST, deployment, and vehicle-agent behavior. Local infra lives in `deploy/`; SQL bootstrap and migrations live in `sql/` and `opendota-app/src/main/resources/db/`.

## Build, Test, and Development Commands

Run all Maven commands from the repo root with `-f opendota-server/pom.xml`, or `cd opendota-server` first.

- `jenv exec mvn -B -ntp -f opendota-server/pom.xml clean install -DskipTests`: full multi-module build.
- `jenv exec mvn -B -ntp -f opendota-server/pom.xml test`: unit-test gate.
- `jenv exec mvn -B -ntp -f opendota-server/pom.xml verify -Pintegration -DskipITs=false`: integration gate placeholder.
- `jenv exec mvn -B -ntp -f opendota-server/pom.xml test -Pconformance`: conformance gate placeholder.
- `cd opendota-server && mvn spring-boot:run -f opendota-app/pom.xml -Dspring-boot.run.profiles=dev`: run the app with the local simulator.
- `cd deploy && docker compose up -d && bash kafka-init.sh`: start local dependencies.

## Coding Style & Naming Conventions

Use Java 21, 4-space indentation, and `com.opendota.{module}.*` packages. Prefer immutable DTOs as Java `record`s. Avoid magic protocol strings; add enums or constants for `act`, status, and macro names. Keep comments in Chinese when editing files that already use Chinese comments. New Flyway migrations use `V{n}__name.sql`; repeatable dev seed files use `R__name.sql`.

## Testing Guidelines

Maven Surefire is the active test runner. There are currently few or no committed test classes, so new work should add targeted tests alongside the affected module under `src/test/java/`. Name unit tests `*Test`. Reserve `integration` and `conformance` Maven profiles for broader suites as they are introduced.

## Commit & Pull Request Guidelines

Use conventional commits seen in history: `feat:`, `fix:`, `docs:`, `ci:`, `chore:`. Prefer scoped summaries such as `feat(diag): add channel open validation`.

PRs should follow `.github/pull_request_template.md`: cite the Phase/Step, summarize why, list impact, and include verification evidence. For runtime changes, include the exact command used, for example the `spring-boot:run` dev command above.

## Security & Configuration Tips

Do not commit `.env`, secrets, or local override files. Keep `application-dev.yml` for local simulator use only. Validate new behavior against the specs in `doc/` before changing code; in this repository, docs intentionally lead implementation.
