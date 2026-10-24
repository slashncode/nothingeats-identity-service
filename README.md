# nothingeats-kotlin-service-template

Template for a NothingEats bounded-context service: Kotlin, Ktor, hexagonal Gradle modules, kotlin-inject, Postgres. It builds, tests and runs out of the box with a small `Item` example that shows every pattern.

Part of [NothingEats](https://github.com/slashncode/nothingeats).

## Create a service

1. **Use this template** on GitHub → `nothingeats-<context>-service`, clone it next to the other repos.
2. `scripts/init-service.sh <context>` renames packages, project and image. CI pushes a signed image on main automatically in every repo created from the template.
3. Replace the `Item` example with the real domain.

## Modules

| Module | Contains | May depend on |
|---|---|---|
| `domain` | Aggregates, value objects, domain exceptions | nothing (pure Kotlin) |
| `application` | Use cases, ports (`ItemRepository`), test fixtures (in-memory port implementations) | `domain`, `platform-core` |
| `adapters` | Ktor routes, Exposed repositories, Flyway migrations (`db/migration`) | `application`, `platform-http`, `platform-persistence` |
| `app` | kotlin-inject composition root, typed config, `main`, logging config | everything |

The module graph enforces the dependency direction at compile time; Konsist tests in `app` cover the rest (domain imports, HTTP adapters never touching persistence).

## Run

One image, three modes: `serve` (default), `migrate`, `cron <task>`.

```bash
./gradlew build                     # compile, ktlint, tests (Docker needed for Testcontainers)

export DATABASE__URL=jdbc:postgresql://localhost:15432/example DATABASE__USER=example DATABASE__PASSWORD=...
./gradlew :app:run --args=migrate   # apply migrations
./gradlew :app:run                  # serve on :8080, readable logs

# In the k3d cluster, from the hub repo: Flux steps aside, Tilt rebuilds and redeploys on every change
just dev <context>-service           # stop with: just dev-stop <context>-service

./gradlew :app:jibDockerBuild -Djib.from.platforms=linux/arm64   # local image
```

Configuration comes from env vars only: `__` nests, `_` separates words (`HTTP__PORT`, `DATABASE__MAXIMUM_POOL_SIZE`, `LIFECYCLE__DRAIN_DELAY`). Missing values stop the service at startup with a full report.

## What the example shows

- Domain rules as plain exceptions (`InvalidItemName`, `ItemNameTaken`), mapped to `AppError` in the use cases.
- A unique constraint mapped to a domain exception by SQLSTATE and constraint name, surfacing as `409 ITEM_NAME_TAKEN`.
- RFC 9457 problem responses with stable codes and the request id.
- Lifecycle components, health checks and routes collected with `@IntoSet` in `ServiceComponent`.
- A smoke test that wires the real composition root against real Postgres.

## License

MIT
