# Quickstart Validation Guide: Spring Boot Service & Infrastructure Bootstrap

**Branch**: `001-spring-boot-infrastructure`

This guide documents how to validate that the feature works end-to-end. It is a run/test guide — implementation details are in `tasks.md`.

---

## Prerequisites

| Tool | Minimum Version | Purpose |
|------|----------------|---------|
| Container runtime (Docker / Podman) | Any recent | Run compose stack, build images |
| VS Code or JetBrains IDE | Any recent | Open dev container |
| Dev Container extension (VS Code) | v0.300+ | Reopen in Container prompt |
| Java 25 + Maven | Provided by dev container | Local build (if not using dev container) |
| GitHub account with repository | — | CI pipeline validation |

---

## Scenario 1: Developer Runs in Dev Container (US-1)

**Goal**: Confirms FR-005, FR-008, FR-010.

```bash
# 1. Clone the repository
git clone <repo-url>
cd hackathon-organiser

# 2. Open in VS Code and accept "Reopen in Container"
code .
# → Single-image container starts; Java 25, Maven, and Docker CLI available

# 3. Inside the dev container terminal: start the database via Docker Compose
docker compose up db -d
# → postgres:18.6-alpine starts with a named volume; wait for healthy status

# 4. Build and start the application
mvn spring-boot:run

# 5. From host browser or dev container terminal:
curl http://localhost:8080/actuator/health
```

**Expected output**:
```json
{
  "status": "UP",
  "components": {
    "r2dbc": { "status": "UP" },
    ...
  }
}
```

**Pass criteria**: HTTP 200, `status=UP`, `components.r2dbc.status=UP`.

---

## Scenario 2: Docker Compose Stack (US-1, acceptance scenario 3)

**Goal**: Confirms FR-004, FR-008, FR-010 outside a dev container.

```bash
# From repo root (no dev container needed, just Docker)
docker compose up --build

# Wait for both services to be healthy, then:
curl http://localhost:8080/actuator/health
```

**Expected output**: HTTP 200, `status=UP`, `r2dbc` sub-indicator UP.

**Edge case — database volume survives rebuild**:
```bash
docker compose down          # does NOT remove named volume
docker compose up --build    # app connects to existing data
# → verify data is still present (no data yet at bootstrap, but no errors)
```

---

## Scenario 3: Local Container Image Build (US-2, acceptance scenario 1)

**Goal**: Confirms FR-003.

```bash
# Build the JAR first
mvn package -DskipTests

# Build the image
docker build -t hackathon-organiser:local .

# Run it with a database
docker run --rm -p 8080:8080 \
  -e SPRING_R2DBC_URL=r2dbc:postgresql://db:5432/appdb \
  -e SPRING_R2DBC_USERNAME=appuser \
  -e SPRING_R2DBC_PASSWORD=apppassword \
  hackathon-organiser:local
```

**Expected**: Container starts, application logs show healthy startup, no database connection errors (assuming DB is reachable).

**Edge case — missing env vars**:
```bash
docker run --rm hackathon-organiser:local
# → Application must fail fast with a descriptive configuration error, not hang silently
```

---

## Scenario 4: CI Pipeline on Pull Request (US-3)

**Goal**: Confirms FR-006, FR-007.

```bash
# Push a branch and open a PR
git push origin 001-spring-boot-infrastructure
# → Open PR on GitHub
```

**Expected**: GitHub Actions workflow runs automatically, compiles, runs tests, reports pass/fail on the PR. Image is NOT published (only main-branch pushes publish).

---

## Scenario 5: CI Publishes Image on Main Push (US-2, US-3)

**Goal**: Confirms FR-007 — commit SHA + latest tags.

```bash
# Merge the PR to main
# → CI runs automatically
```

**Expected in GitHub Actions log**:
- Maven build + tests pass
- Docker image built
- Image pushed to `ghcr.io/<owner>/<repo>:sha-<sha>` and `ghcr.io/<owner>/<repo>:latest`
- Image visible in repository's Packages section

---

## Scenario 6: CI Publishes Versioned Image on Git Tag (US-2)

**Goal**: Confirms FR-007 — semver tag.

```bash
git tag v1.0.0
git push origin v1.0.0
```

**Expected**: CI publishes `ghcr.io/<owner>/<repo>:1.0.0` (or `v1.0.0`) in addition to the SHA tag.

---

## Scenario 7: CI Fails on Test Failure (US-3, acceptance scenario 2)

**Goal**: Confirms FR-006 gate prevents image publish on failure.

```bash
# Introduce a breaking test (e.g., assert wrong status code)
# Push to a branch
git push origin test/break-ci
```

**Expected**: Pipeline fails at test step, image build and push steps do NOT run.

---

## Acceptance Criteria Cross-Reference

| Criterion | Validated By |
|-----------|-------------|
| SC-001: Environment up within 10 min | Scenario 1 (time the container start) |
| SC-002: CI completes within 10 min | Scenarios 4, 5 (check Actions duration) |
| SC-003: Image available within 5 min of main push | Scenario 5 (check Packages tab) |
| SC-004: Health responds within 2 sec | Scenarios 1, 2 (observe response time) |
| SC-005: Local build == CI build | Scenario 3 vs Scenario 5 (same JAR artifact) |

---

## Links

- Data model: [data-model.md](data-model.md)
- Health endpoint contract: [contracts/health-endpoint.md](contracts/health-endpoint.md)
- Feature spec: [spec.md](spec.md)
- Research findings: [research.md](research.md)
