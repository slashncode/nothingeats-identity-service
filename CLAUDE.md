# CLAUDE.md

Identity service of NothingEats (accounts, verification, login, tokens, JWKS). Created from the Kotlin service template. The cross-repo plan and session rules live in `../CLAUDE.md` (workspace root), if present — read it first.

## Commands

- `./gradlew build` — compile (warnings are errors), ktlint, tests. Needs Docker for Testcontainers.
- `./gradlew ktlintFormat` — auto-format
- `./gradlew :app:run --args=migrate` / `./gradlew :app:run` — needs `DATABASE__URL`, `DATABASE__USER`, `DATABASE__PASSWORD`, `KEYS__ENCRYPTION_KEY`
- `./gradlew :app:jibDockerBuild -Djib.from.platforms=linux/arm64` — local image

## Rules

- Respect the module direction: `domain` ← `application` ← `adapters` ← `app`. Never add a framework dependency to `domain`.
- Wiring happens only in `app/.../ServiceComponent.kt`. Lifecycle components, health checks, routes and cron tasks are contributed with `@IntoSet`.
- Expected failures are `AppError` subtypes with an `UPPER_SNAKE` code, thrown from use cases. Domain code throws its own plain exceptions.
- Schema changes are new Flyway files in `adapters/src/main/resources/db/migration`; never edit an applied migration. Serving pods refuse to start with pending migrations.
- Repositories map constraint violations by SQLSTATE and constraint name (helpers in `platform-persistence`).
- The platform (`../nothingeats-kotlin-platform`) is used as a composite build when checked out; changes there are picked up without publishing.
- Security-sensitive: never log tokens, passwords, hashes or full email addresses; keep registration and login answers uniform (no enumeration).
- The event contract is `docs/events.md`; change it together with the payload classes in `adapters/.../messaging`.
