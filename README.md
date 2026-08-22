# Hackathon Organiser

A reactive Spring Boot service for organising hackathons.

| | |
|---|---|
| Language | Java 25 (LTS) |
| Framework | Spring Boot 4.1.1, Spring WebFlux |
| Database | PostgreSQL 18.6 via R2DBC (non-blocking) |
| Build | Maven |
| Image | `ghcr.io/fabcelhaft/hackathon-organiser` |

## Prerequisites

A container runtime (Docker or Podman) is the only thing you need installed. Java and Maven
are provided by the dev container.

## Quickstart — dev container (recommended)

1. Open the repository in VS Code and accept **Reopen in Container**.
   Java 25, Maven, and the Docker CLI are available inside.
2. Start the database:

   ```bash
   docker compose up db -d
   ```

3. Start the application:

   ```bash
   mvn spring-boot:run
   ```

4. Check health at <http://localhost:8080/actuator/health>.

## Quickstart — Docker Compose

The `app` image is single-stage and copies a pre-built JAR, so package first:

```bash
mvn package -DskipTests
docker compose up --build
```

Then check <http://localhost:8080/actuator/health>.

> If port 8080 is already bound on your host (the dev container forwards it), publish the app
> on another port with a compose override rather than editing `docker-compose.yml`.

## Health check

`GET /actuator/health` returns `200` with `status: UP` when the database is reachable, and
`503` with `components.r2dbc.status: DOWN` when it is not.

```json
{
  "status": "UP",
  "components": {
    "r2dbc": { "status": "UP", "details": { "database": "PostgreSQL" } }
  }
}
```

## Configuration

Connection settings come from the environment — there are no defaults in `application.yml`, so a
misconfigured deployment fails fast with `Failed to configure a ConnectionFactory` rather than
starting in a broken state.

| Variable | Example |
|---|---|
| `SPRING_R2DBC_URL` | `r2dbc:postgresql://db:5432/hackathon` |
| `SPRING_R2DBC_USERNAME` | `hackathon` |
| `SPRING_R2DBC_PASSWORD` | `hackathon` |

Schema is managed by `src/main/resources/schema.sql` with `spring.sql.init.mode=always`. All DDL
must use `CREATE TABLE IF NOT EXISTS` so startup stays idempotent — there is no Flyway or
Liquibase.

## Tests

```bash
mvn verify
```

Unit tests run under Surefire; `*IT` integration tests run under Failsafe. `ActuatorHealthIT`
starts a real PostgreSQL container via Testcontainers (`@ServiceConnection`), so a running
container runtime is required. Integration tests use `WebTestClient`, never `MockMvc`.

## Building the image locally

```bash
mvn package -DskipTests
docker build -t hackathon-organiser:local .
```

## CI and publishing

`.github/workflows/ci.yml` runs on every push:

- **compile-and-test** — every branch. Runs `mvn verify` and uploads the JAR.
- **build-and-push** — `main` and `v*` tags only, and only after tests pass. Pushes to GHCR
  tagged with the commit SHA, plus `latest` on `main` and both `v1.2.3` and `1.2.3` on a tag.

A failing test blocks the publish job entirely.

## Contributing

This project follows a constitution (`.specify/memory/constitution.md`). The load-bearing rules:

- Spring Boot native features only — no third-party web frameworks or DI containers.
- Reactive-first: WebFlux and R2DBC; no `spring-webmvc` on the classpath, no blocking I/O on
  Reactor threads; handlers return `Mono` or `Flux`.
- Server-side rendering with Thymeleaf; Pico CSS as the only CSS framework.
- **Test-first is non-negotiable.** Write the failing test, commit it, then implement.
