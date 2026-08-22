# Data Model: Spring Boot Service & Infrastructure Bootstrap

**Phase**: 1 | **Branch**: `001-spring-boot-infrastructure`

> This feature is a pure infrastructure bootstrap. There are no domain entities introduced yet. This document records the infrastructure-level "entities" (runtime components and their configuration contracts) and the minimal schema that the R2DBC initialiser will apply.

---

## Runtime Entities

### Application Service

The Spring Boot process. No persisted state of its own; owns HTTP request handling and health reporting.

| Property | Value |
|----------|-------|
| Startup class | `net.fabcelhaft.hackathonorganiser.HackathonOrganiserApplication` |
| HTTP port | `8080` |
| Health endpoint | `GET /actuator/health` |
| DB dependency | Required at startup — fails fast if unavailable |
| Config source | Environment variables (see below) |

**Configuration** (`application.yml` with environment variable overrides):

| Variable | Example | Description |
|----------|---------|-------------|
| `SPRING_R2DBC_URL` | `r2dbc:postgresql://localhost:5432/appdb` | R2DBC connection URL |
| `SPRING_R2DBC_USERNAME` | `appuser` | Database user |
| `SPRING_R2DBC_PASSWORD` | `apppassword` | Database password |
| `SPRING_SQL_INIT_MODE` | `always` | Controls schema.sql execution |

### PostgreSQL Database

The relational data store. Managed via Docker Compose (both in the dev environment and the root compose file).

| Property | Value |
|----------|-------|
| Image | `postgres:18.6-alpine` |
| Default database | `appdb` |
| Default user | `appuser` |
| Port | `5432` |
| Data persistence | Named Docker volume (`postgres-data`) |
| Health signal | `pg_isready -U appuser -d appdb` |

### Container Image

The packaged artefact produced from source.

| Property | Value |
|----------|-------|
| Base image | `eclipse-temurin:25-jre-alpine` |
| JAR path in image | `/app/app.jar` |
| Registry | `ghcr.io/<owner>/<repo>` |
| Tag (main push) | `sha-<7-char-sha>`, `latest` |
| Tag (git tag push) | `sha-<7-char-sha>`, `<semver>` |

---

## Database Schema (bootstrap)

The `schema.sql` at this stage only creates the structural scaffolding. No domain tables exist yet — they will be introduced by subsequent features.

```sql
-- Placeholder: no domain tables yet.
-- Subsequent features will add CREATE TABLE IF NOT EXISTS statements here.
-- Spring Boot applies this file automatically via spring.sql.init.mode=always.
```

**Conventions for future tables** (to record now, before any table is added):

- All tables use `BIGINT GENERATED ALWAYS AS IDENTITY` as the primary key (reactive-safe, no sequence fetch on insert with R2DBC).
- All timestamps stored as `TIMESTAMPTZ` (UTC).
- `CREATE TABLE IF NOT EXISTS` required for all DDL to make schema.sql idempotent across restarts.

---

## State Transitions

### Application Startup Sequence

```
Container starts
    → Spring Boot reads environment variables
    → R2DBC connection pool initialised (connects to PostgreSQL)
    → If connection fails → application exits with non-zero code and error message
    → spring.sql.init.mode=always → schema.sql applied via R2dbcScriptDatabaseInitializer
    → Actuator health indicators registered (including r2dbcHealthIndicator)
    → HTTP server starts on port 8080
    → GET /actuator/health → { "status": "UP", "components": { "r2dbc": { "status": "UP" } } }
```

### Validation Rules

- R2DBC URL must be a valid `r2dbc:postgresql://` URI — Spring Boot fails fast with a descriptive error otherwise.
- `spring.sql.init.mode` must be `always` in development and compose environments; leave unset (defaults to `embedded`, effectively `never` for PostgreSQL) in production to avoid re-running DDL.
