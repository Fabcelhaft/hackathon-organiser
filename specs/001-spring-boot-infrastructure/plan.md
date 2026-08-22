# Implementation Plan: Spring Boot Service & Infrastructure Bootstrap

**Branch**: `001-spring-boot-infrastructure` | **Date**: 2026-08-22 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/001-spring-boot-infrastructure/spec.md`

## Summary

Bootstrap the Hackathon Organiser project as a reactive Spring Boot 4.1.1 service (Java 25 LTS) with Maven, PostgreSQL via R2DBC, a health-check endpoint, a single-stage Dockerfile, Docker Compose for local and CI use, a devcontainer, and a GitHub Actions pipeline that compiles, tests, and publishes to GHCR.

## Technical Context

**Language/Version**: Java 25 LTS (newest LTS, released September 2025)

**Framework**: Spring Boot 4.1.1 (newest stable as of August 2026)

**Primary Dependencies**:
- `spring-boot-starter-webflux` — reactive HTTP layer (WebFlux / Project Reactor)
- `spring-boot-starter-data-r2dbc` — reactive database access
- `spring-boot-starter-actuator` — health-check endpoint at `/actuator/health`
- `org.postgresql:r2dbc-postgresql` (runtime) — R2DBC driver for PostgreSQL (pgjdbc project)
- `spring-boot-starter-test` (test) — JUnit 5 + Mockito
- `io.projectreactor:reactor-test` (test) — StepVerifier

**Build Tool**: Maven | **Maven Coordinates**: `groupId: net.fabcelhaft`, `artifactId: hackathon-organiser`

**Storage**: PostgreSQL 18.6 (alpine) via R2DBC. Connection configured through environment variables.

**Testing**: JUnit 5, Mockito (unit); `WebTestClient` (integration). Test-First required.

**Target Platform**: Linux container (`eclipse-temurin:25-jre-alpine`)

**Performance Goals**: Health endpoint responds within 2 seconds of container reaching running state (SC-004). CI completes within 10 minutes (SC-002).

**Constraints**: No Flyway/Liquibase — schema managed via `schema.sql` + `spring.sql.init.mode=always`. No Spring MVC (`spring-webmvc`) — WebFlux only.

**Scale/Scope**: Infrastructure bootstrap — no domain entities yet.

## Constitution Check

*GATE: Evaluated pre-research and re-evaluated post-design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Spring Boot Native Only | ✅ PASS | All dependencies are official Spring Boot starters or officially supported drivers. No third-party web framework or DI container. |
| II. Reactive-First (WebFlux) | ✅ PASS | `spring-boot-starter-webflux` mandated. Database access via R2DBC (non-blocking). `spring-boot-starter-data-r2dbc` does not pull in `spring-webmvc`. Actuator auto-configures for WebFlux. |
| III. Thymeleaf SSR | N/A | No user-facing pages in this feature. Principle applies to future features that add HTML views. |
| IV. Pico CSS | N/A | No UI in this feature. |
| V. Test-First Development | ✅ REQUIRED | Integration test for `/actuator/health` MUST be written and committed (failing) before the Actuator dependency is added to `pom.xml`. All tasks in `tasks.md` must follow Red-Green-Refactor. |

**Post-design re-check**: No violations identified. `spring-boot-starter-actuator` does not pull in `spring-webmvc` when WebFlux is on the classpath — Actuator auto-configures reactive endpoints.

## Project Structure

### Documentation (this feature)

```text
specs/001-spring-boot-infrastructure/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── health-endpoint.md  # Phase 1 output
└── tasks.md             # Phase 2 output (created by /speckit-tasks)
```

### Source Code (repository root)

```text
pom.xml                          # Maven build, Spring Boot parent 4.1.1

src/
├── main/
│   ├── java/net/fabcelhaft/hackathonorganiser/
│   │   └── HackathonOrganiserApplication.java
│   └── resources/
│       ├── application.yml          # R2DBC URL, actuator config, sql.init.mode
│       └── schema.sql               # Empty placeholder (IF NOT EXISTS DDL)
└── test/
    └── java/net/fabcelhaft/hackathonorganiser/
        └── ActuatorHealthIT.java    # WebTestClient integration test (written first)

Dockerfile                       # Single-stage: eclipse-temurin:25-jre-alpine + fat JAR
docker-compose.yml               # App service + postgres:18.6-alpine, port 8080

.devcontainer/
└── devcontainer.json            # single-image, features: java+maven, docker-outside-of-docker; forwardPorts: [8080]

.github/
└── workflows/
    └── ci.yml                   # CI: compile+test on all pushes; build+push GHCR on main/tags
```

**Structure Decision**: Standard Maven single-project layout. No frontend or mobile components. Devcontainer is a single image with devcontainer features; developers start PostgreSQL on demand via `docker compose up db` using the Docker CLI available inside the container.

## Complexity Tracking

No constitution violations. No complexity justification required.
