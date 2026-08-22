# Research: Spring Boot Service & Infrastructure Bootstrap

**Phase**: 0 | **Branch**: `001-spring-boot-infrastructure`

## Technology Versions

### Decision: Java 25 LTS
- **Chosen**: Java 25 (released September 2025, current LTS)
- **Rationale**: Java 25 is the newest LTS release at time of planning (August 2026). LTS cadence: 17 → 21 → 25 every 2 years. Java 26 exists but is non-LTS.
- **Impact**: Dockerfile base image → `eclipse-temurin:25-jre-alpine`; `pom.xml` → `<java.version>25</java.version>`; GitHub Actions Java setup step must specify version 25.

### Decision: Spring Boot 4.1.1
- **Chosen**: Spring Boot 4.1.1 (released August 20, 2026 — latest stable on Maven Central)
- **Rationale**: Newest stable release at planning time. Requires Java 17 minimum; Java 25 is fully supported. Spring Boot 4.x continues the reactive-first auto-configuration model; no breaking changes for the dependencies in this feature.
- **Alternatives considered**: Spring Boot 3.5.x (still receiving patches but older generation); rejected in favour of newest stable per FR-001.

---

## Reactive Database Access

### Decision: Spring Data R2DBC + org.postgresql:r2dbc-postgresql
- **Chosen**: `spring-boot-starter-data-r2dbc` + `org.postgresql:r2dbc-postgresql` (runtime)
- **Rationale**: `org.postgresql:r2dbc-postgresql` is the current maintained driver (pgjdbc project). The older `io.r2dbc:r2dbc-postgresql` groupId is abandoned and must not be used.
- **R2DBC URL format**: `r2dbc:postgresql://<host>:<port>/<database>`
- **Alternatives considered**: JOOQ + R2DBC (additional abstraction not needed for bootstrap), jOOQ reactive (too early without a schema to model).

### Decision: spring.sql.init.mode=always for schema.sql
- **Chosen**: `spring.sql.init.mode=always` in application properties
- **Rationale**: The default mode `embedded` only runs against H2/HSQLDB. To have Spring Boot run `src/main/resources/schema.sql` on startup against PostgreSQL, `mode=always` is required. Spring Boot uses `R2dbcScriptDatabaseInitializer` automatically when `spring-boot-starter-data-r2dbc` is on the classpath.
- **Caution**: `mode=always` reruns DDL on every restart. Schema must use `CREATE TABLE IF NOT EXISTS` to be idempotent.
- **Alternatives considered**: Flyway, Liquibase — explicitly out of scope per spec clarification (FR-011).

---

## Container & CI

### Decision: Dockerfile — single-stage fat JAR with eclipse-temurin:25-jre-alpine
- **Chosen**: Copy the Maven Surefire fat JAR into `eclipse-temurin:25-jre-alpine`
- **Rationale**: FR-003 mandates single-stage fat JAR build. Alpine-based JRE image minimises image size. The fat JAR (`hackathon-organiser-*.jar`) is produced by `mvn package` via `spring-boot-maven-plugin`.
- **Alternatives considered**: Multi-stage build (rejected per spec), distroless (not Temurin, rejected per FR-003 constraint).

### Decision: GitHub Actions GHCR tagging with docker/metadata-action v5
- **Chosen**: `docker/metadata-action@v5` + `docker/build-push-action@v6`
- **Tag strategy**:
  - `type=sha` → commit SHA tag on every push (e.g., `sha-860c190`)
  - `type=raw,value=latest,enable={{is_default_branch}}` → `latest` on main-branch pushes only
  - `type=ref,event=tag` → raw git tag name (e.g., `v1.0.0`, preserving the `v` prefix) when a `v*` git tag is pushed
- **Auth**: `GITHUB_TOKEN` (built-in, no external secret required) per FR-007 assumption in spec.
- **Alternatives considered**: Manual tagging in bash (error-prone, not idiomatic); ko (Go-centric, wrong runtime).

---

## Dev Container

### Decision: Single-image devcontainer with devcontainer features
- **Chosen**: `devcontainer.json` using a single base image (`mcr.microsoft.com/devcontainers/java:25`) with devcontainer features for Java/Maven and Docker CLI access. No `dockerComposeFile` reference.
- **Rationale**: Simpler setup — no separate `.devcontainer/docker-compose.yml` to maintain. Developers run the root `docker-compose.yml` themselves using Docker commands available inside the container. PostgreSQL is not auto-started by the devcontainer; developers start it on demand via `docker compose up db`.
- **Docker access**: `ghcr.io/devcontainers/features/docker-outside-of-docker:1` — mounts the host Docker socket, making `docker` and `docker compose` available inside the container without a nested Docker daemon.
- **VS Code extensions**: `vscjava.vscode-java-pack` (Java), `vmware.vscode-spring-boot` (Spring Boot Tools), `cweijan.vscode-postgresql-client2` (PostgreSQL client)
- **Port forwarding**: `8080` only in `forwardPorts`
- **Alternatives considered**: Docker Compose devcontainer (more moving parts, postgres always auto-started whether needed or not); docker-in-docker feature (heavier than socket mount, unnecessary here).

---

## Resolved Clarifications

All NEEDS CLARIFICATION items resolved:

| Item | Resolution |
|------|------------|
| Java LTS version | Java 25 |
| Spring Boot version | 4.1.1 |
| Base image | `eclipse-temurin:25-jre-alpine` |
| R2DBC driver groupId | `org.postgresql:r2dbc-postgresql` |
| schema.sql activation | `spring.sql.init.mode=always` |
| GHCR tagging action | `docker/metadata-action@v5` + `docker/build-push-action@v6` |
| Devcontainer strategy | Single-image with devcontainer features (docker-outside-of-docker) |
| PostgreSQL image | `postgres:18.6-alpine` |
