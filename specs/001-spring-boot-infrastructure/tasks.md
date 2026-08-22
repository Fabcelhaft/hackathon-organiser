---
description: "Task list for Spring Boot Service & Infrastructure Bootstrap"
---

# Tasks: Spring Boot Service & Infrastructure Bootstrap

**Input**: Design documents from `/specs/001-spring-boot-infrastructure/`

**Branch**: `001-spring-boot-infrastructure`

**Constitution note**: Test-First Development is NON-NEGOTIABLE (Constitution §V). Every implementation task MUST be preceded by a failing test committed to the repository. Integration tests use `WebTestClient` (not MockMvc). Tests use Testcontainers with `@ServiceConnection` for real PostgreSQL.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on other in-progress tasks)
- **[Story]**: User story this task belongs to

## Path Conventions

Standard Maven single-project layout as defined in `plan.md`:

- Source: `src/main/java/net/fabcelhaft/hackathonorganiser/`
- Resources: `src/main/resources/`
- Tests: `src/test/java/net/fabcelhaft/hackathonorganiser/`

---

## Phase 1: Setup (Project Initialisation)

**Purpose**: Establish the Maven project skeleton and main application class. All subsequent phases depend on this foundation.

- [X] T001 Create `pom.xml` at the repository root with: Spring Boot parent `4.1.1`, Java version `25`, coordinates `groupId: net.fabcelhaft` / `artifactId: hackathon-organiser`, and dependencies: `spring-boot-starter-webflux`, `spring-boot-starter-data-r2dbc`, `spring-boot-starter-actuator`, `org.postgresql:r2dbc-postgresql` (runtime), `spring-boot-starter-test` (test), `io.projectreactor:reactor-test` (test), `org.testcontainers:testcontainers` (test), `org.testcontainers:postgresql` (test), `org.springframework.boot:spring-boot-testcontainers` (test — provides `@ServiceConnection` wiring for Spring Boot 4.x)
- [X] T002 Create `src/main/java/net/fabcelhaft/hackathonorganiser/HackathonOrganiserApplication.java` — standard `@SpringBootApplication` main class; confirm `mvn compile` passes

**Checkpoint**: `mvn compile` succeeds with zero source files other than the main class.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Configuration, schema, local compose stack, and devcontainer — these must be complete before any user story can be tested end-to-end.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T003 Create `src/main/resources/application.yml` with R2DBC connection properties from environment variables (`SPRING_R2DBC_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`), `spring.sql.init.mode: always`, and Actuator configuration (`management.endpoints.web.exposure.include: health`, `management.endpoint.health.show-details: always`)
- [X] T004 Create `src/main/resources/schema.sql` — empty placeholder file with a comment block explaining the `CREATE TABLE IF NOT EXISTS` convention required for idempotent schema initialisation
- [X] T005 [P] Create `docker-compose.yml` at the repository root with two services: `app` (built from Dockerfile, port `8080:8080`, env vars for R2DBC URL/user/password pointing to the `db` service, `depends_on: db: condition: service_healthy`) and `db` (`postgres:18.6-alpine`, named volume `postgres-data`, env vars `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `pg_isready` healthcheck)
- [X] T006 [P] Create `.devcontainer/devcontainer.json` with: `image: mcr.microsoft.com/devcontainers/java:25`, features `ghcr.io/devcontainers/features/java:1` (version 25, Maven enabled) and `ghcr.io/devcontainers/features/docker-outside-of-docker:1`, `forwardPorts: [8080]`, and VS Code extensions `vscjava.vscode-java-pack`, `vmware.vscode-spring-boot`, `cweijan.vscode-postgresql-client2`

**Checkpoint**: Foundation ready — open repo in devcontainer, confirm Java 25 + Maven + `docker` CLI are all available inside the container.

---

## Phase 3: User Story 1 — Developer Runs Application Locally (Priority: P1) 🎯 MVP

**Goal**: The application starts in the compose stack or devcontainer, connects to PostgreSQL, and responds with a healthy status on `GET /actuator/health` including the `r2dbc` sub-indicator.

**Independent Test**: `docker compose up --build` → `curl http://localhost:8080/actuator/health` → HTTP 200, `status: UP`, `components.r2dbc.status: UP`.

### Tests for User Story 1 (Constitution §V — write FIRST, ensure they FAIL before implementation)

- [X] T007 [US1] Write failing `WebTestClient` integration test `src/test/java/net/fabcelhaft/hackathonorganiser/ActuatorHealthIT.java` — annotate with `@SpringBootTest(webEnvironment = RANDOM_PORT)`, `@Testcontainers`; declare `@Container @ServiceConnection static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6-alpine")`; inject `WebTestClient`; write test method `healthEndpointReturnsUpWithR2dbcIndicator()` that calls `GET /actuator/health` and asserts HTTP 200, `$.status == "UP"`, `$.components.r2dbc.status == "UP"`
- [X] T008 [US1] Run `mvn test -Dtest=ActuatorHealthIT` and **confirm the test fails** (no Actuator endpoint wired yet); commit the failing test to the repository before proceeding

### Implementation for User Story 1

- [X] T009 [US1] Verify that `spring-boot-starter-actuator` (already in `pom.xml` from T001) auto-configures `/actuator/health` with `r2dbcHealthIndicator` — run `mvn test -Dtest=ActuatorHealthIT` and confirm the test now **passes** with a running Testcontainers PostgreSQL; fix any configuration issues in `application.yml` (T003) until the test is green
- [X] T010 [US1] Smoke-test the full compose stack: run `docker compose up --build`, wait for both services to be healthy, then `curl http://localhost:8080/actuator/health` from the host — verify HTTP 200, `status: UP`, `components.r2dbc.status: UP`
- [X] T011 [US1] Validate edge case — database unavailable: stop only the `db` service (`docker compose stop db`), wait for the `app` service to fail its health check, verify `GET /actuator/health` returns HTTP 503 with `components.r2dbc.status: DOWN`

**Checkpoint**: User Story 1 is fully functional. A developer can open the repo in a devcontainer, run `docker compose up db -d`, start the app with `mvn spring-boot:run`, and reach a healthy health endpoint from the host browser.

---

## Phase 4: User Story 2 — Developer Builds and Publishes a Container Image (Priority: P2)

**Goal**: A `Dockerfile` produces a runnable image; the GitHub Actions pipeline compiles, tests, builds the image, and pushes it to GHCR on every successful push to `main` or when a `v*` git tag is pushed.

**Independent Test**: Push to `main` on GitHub → CI completes → image appears in repository's Packages namespace tagged with commit SHA and `latest`.

### Tests for User Story 2 (Constitution §V)

- [X] T012 [US2] Pre-implementation gate for Dockerfile (Constitution §V infrastructure exemption): run `docker build .` from the repository root and confirm it fails with "no such file: Dockerfile" — infrastructure artefacts have no JUnit equivalent, so the absent-file failure IS the failing state. Note this in the commit message before proceeding to T013.

### Implementation for User Story 2

- [X] T013 [US2] Create `Dockerfile` at the repository root — single-stage build: `FROM eclipse-temurin:25-jre-alpine`, `WORKDIR /app`, `ARG JAR_FILE=target/*.jar`, `COPY ${JAR_FILE} app.jar`, `ENTRYPOINT ["java", "-jar", "app.jar"]`
- [X] T014 [US2] Create `.github/workflows/ci.yml` with two jobs:
  - **Job `compile-and-test`** (trigger: push to any branch): checkout → setup Java 25 with Temurin + Maven cache → `mvn verify` (compiles + runs tests including `ActuatorHealthIT` via Testcontainers)
  - **Job `build-and-push`** (trigger: push to `main` or `v*` tags; `needs: compile-and-test`): checkout → setup Buildx → login to GHCR with `GITHUB_TOKEN` → `docker/metadata-action@v5` (tags: `type=sha`, `type=raw,value=latest,enable={{is_default_branch}}`, `type=ref,event=tag` — preserves raw git tag name including `v` prefix, e.g. `v1.0.0`) → `docker/build-push-action@v6` (push: true, tags from metadata step, GHA cache)
- [X] T015 [US2] Build the fat JAR locally (`mvn package -DskipTests`) then build the Docker image (`docker build -t hackathon-organiser:local .`) and run it against the compose `db` service — confirm the container starts and `/actuator/health` returns UP (validates the Dockerfile produces a working image)

**Checkpoint**: Push `main` to GitHub, wait for CI to complete, confirm image appears in Packages tab tagged with the commit SHA and `latest`.

---

## Phase 5: User Story 3 — Continuous Integration Validates Every Change (Priority: P3)

**Goal**: Every push to any branch triggers compile + test; a test failure prevents the image from being built or pushed; a passing push to `main` publishes the image.

**Independent Test**: Open a PR on GitHub → CI runs on the PR branch → Actions status check appears on the PR.

### Implementation for User Story 3

- [ ] T016 [US3] Push the current branch to GitHub and verify `compile-and-test` triggers on the non-main branch, and that `build-and-push` does NOT trigger (only `main` and `v*` tags should trigger the publish job)
- [ ] T017 [US3] Validate the failure gate: temporarily break `ActuatorHealthIT` (assert wrong status), push to a branch, confirm CI fails on `compile-and-test` and `build-and-push` is skipped; revert the intentional break and push again to confirm green
- [ ] T018 [US3] Validate git tag publishing: create and push a `v0.1.0` tag to `main`, confirm CI publishes the image with a `0.1.0` semver tag in addition to the SHA tag

**Checkpoint**: All three user story independent tests pass. CI behaviour matches spec acceptance criteria exactly.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T019 [P] Add `.dockerignore` at repository root to exclude `target/`, `.devcontainer/`, `.github/`, `specs/`, and `.specify/` from the Docker build context — reduces build time and image layer cache invalidation (supports SC-002 CI < 10 min, SC-003 image available < 5 min)
- [X] T020 Run all quickstart validation scenarios from `specs/001-spring-boot-infrastructure/quickstart.md` (scenarios 1–7) and confirm each passes or is documented as a known limitation
- [X] T021 [P] Create `README.md` at the repository root documenting: prerequisites (container runtime only), devcontainer quickstart (open in VS Code → Reopen in Container → `docker compose up db -d` → `mvn spring-boot:run`), compose quickstart (`docker compose up --build`), health check URL (`http://localhost:8080/actuator/health`), and CI/publishing overview — satisfies SC-001 (10-minute setup following only README steps)
- [X] T022 [P] Validate Constitution §II (Reactive-First): run `mvn dependency:tree -Dincludes=org.springframework:spring-webmvc` from the repository root and assert the output contains no matches — confirms `spring-webmvc` is not on the classpath as a transitive dependency; fail the task and investigate if any match is found

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **Foundational (Phase 2)**: Depends on Phase 1 — BLOCKS all user stories
- **US1 (Phase 3)**: Depends on Phase 2 — **MVP deliverable**
- **US2 (Phase 4)**: Depends on Phase 2; US1 completion recommended (needs working app to build image from)
- **US3 (Phase 5)**: Depends on Phase 4 (CI workflow must exist to validate)
- **Polish (Phase 6)**: Depends on Phases 3–5

### User Story Dependencies

- **US1 (P1)**: Independent after Phase 2
- **US2 (P2)**: Independent after Phase 2; Dockerfile is new code, CI is new file — no conflict with US1
- **US3 (P3)**: Depends on US2's CI workflow existing

### Within Each User Story

1. Write test → confirm it FAILS → commit (Constitution §V)
2. Implement → confirm test PASSES
3. Validate acceptance scenarios from quickstart.md
4. Commit and move to next task

### Parallel Opportunities

- T005 (`docker-compose.yml`) and T006 (`.devcontainer/devcontainer.json`) can run in parallel — different files, no shared dependencies
- T013 (`Dockerfile`) and T014 (`ci.yml`) can be drafted in parallel — different files
- T016, T017, T018 (Phase 5 validations) are sequential (each depends on the previous CI run result)

---

## Parallel Example: Phase 2 Foundational

```bash
# T005 and T006 can proceed simultaneously:
Task A: "Create docker-compose.yml with app + postgres:18.6-alpine services"
Task B: "Create .devcontainer/devcontainer.json with single-image + features"
```

## Parallel Example: Phase 4 (US2)

```bash
# T013 and T014 can be drafted simultaneously:
Task A: "Create Dockerfile (single-stage, eclipse-temurin:25-jre-alpine)"
Task B: "Create .github/workflows/ci.yml (compile-and-test + build-and-push jobs)"
# Merge T014 after T013 exists (build-push job references the Dockerfile)
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001–T002)
2. Complete Phase 2: Foundational (T003–T006) — CRITICAL, blocks everything
3. Complete Phase 3: User Story 1 (T007–T011)
4. **STOP and VALIDATE**: `docker compose up --build`, confirm health endpoint UP
5. Demo-ready local dev environment

### Incremental Delivery

1. Phase 1 + Phase 2 → devcontainer + compose stack ready
2. Phase 3 → healthy app running locally (MVP)
3. Phase 4 → reproducible container image + CI publishing
4. Phase 5 → CI hardened, branch validation, failure gates confirmed
5. Phase 6 → polish and final quickstart validation

---

## Notes

- `[P]` tasks operate on different files with no shared dependencies
- Constitution §V: tests committed in failing state BEFORE implementation — never skip
- `WebTestClient` only — `MockMvc` is forbidden by constitution
- Testcontainers provides real PostgreSQL for integration tests without a running database in CI
- `@ServiceConnection` (Spring Boot 4.x) wires the Testcontainers PostgreSQL container directly into R2DBC auto-configuration — no manual `@DynamicPropertySource` needed
- Commit after each task or logical group; each user story phase should be a reviewable increment
