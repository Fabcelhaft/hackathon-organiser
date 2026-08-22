# Feature Specification: Spring Boot Service & Infrastructure Bootstrap

**Feature Branch**: `001-spring-boot-infrastructure`

**Created**: 2026-08-22

**Status**: Draft

**Input**: User description: "initialize general service and infrastructure. Include Spring boot service (ensure newest stable version is used with newest LTS Java). Initialize Maven. Also add container build, a basic github ci, pushing the image to github registry. A container compose with a postgres database and a devcontainer.json for this stack."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Developer Runs the Application Locally (Priority: P1)

A developer clones the repository and wants to start the application locally with all dependencies (database) already running, without having to manually install or configure anything beyond a container runtime.

**Why this priority**: This is the baseline that makes all other development work possible. Without a working local environment, no feature can be developed, tested, or demonstrated.

**Independent Test**: Can be fully tested by opening the repository in a dev container and running the application — the service starts and responds to a health check, and the database connection is confirmed active.

**Acceptance Scenarios**:

1. **Given** a developer opens the repository in a dev container-aware IDE, **When** they accept the "Reopen in Container" prompt, **Then** the container environment starts with Java, Maven, and a connected PostgreSQL instance available without any additional setup steps.
2. **Given** the dev container is running, **When** the developer starts the application, **Then** the application boots successfully and reports a healthy database connection.
3. **Given** a Docker Compose file at the project root, **When** a developer runs the compose stack outside a dev container, **Then** the application service and a PostgreSQL database start together and the application is reachable on its configured port.

---

### User Story 2 - Developer Builds and Publishes a Container Image (Priority: P2)

A developer or CI system wants to produce a container image from the application source code and publish it to the GitHub Container Registry so that it can be deployed to any environment.

**Why this priority**: Containerised delivery is the prerequisite for all environment-independent deployments. Without a reproducible image build, there is no consistent artefact to deploy or share.

**Independent Test**: Can be fully tested by triggering the CI pipeline on a push to the main branch — the pipeline builds the image and the image appears in the GitHub Container Registry under the repository's package namespace.

**Acceptance Scenarios**:

1. **Given** a Dockerfile at the project root, **When** the container build command is executed locally, **Then** a runnable container image is produced that starts the application successfully.
2. **Given** a push to the main branch on GitHub, **When** the CI pipeline runs, **Then** the pipeline compiles the project, runs all tests, builds the container image, and pushes it to the GitHub Container Registry without manual intervention.
3. **Given** the CI pipeline completes successfully, **When** the published image is pulled and started with a compatible database, **Then** the application starts and handles requests correctly.

---

### User Story 3 - Continuous Integration Validates Every Change (Priority: P3)

Any code pushed to the repository is automatically compiled and all tests are run, giving contributors immediate feedback on whether their change breaks existing functionality.

**Why this priority**: Automated validation on every push prevents regressions from being merged. It is foundational discipline for a project that follows Test-First Development.

**Independent Test**: Can be fully tested by opening a pull request — the CI workflow runs automatically and reports pass or fail status on the PR before any merge is allowed.

**Acceptance Scenarios**:

1. **Given** a developer pushes a commit to any branch, **When** the GitHub CI workflow triggers, **Then** the project is compiled and all tests are executed.
2. **Given** a test failure is introduced, **When** the CI pipeline runs, **Then** the pipeline reports failure and does not proceed to the image build or publish step.
3. **Given** all tests pass on the main branch, **When** the full CI pipeline completes, **Then** a container image tagged with the commit SHA and `latest` is published to the registry.

---

### Edge Cases

- What happens when the database is unavailable at application startup? The application should report a clear error and exit rather than running in a degraded silent state.
- How does the CI pipeline behave when GitHub Container Registry is temporarily unreachable? The push step should fail with an actionable error message and not silently succeed with an incomplete image.
- What happens when the dev container is rebuilt without clearing the database volume? Existing data should be preserved across container rebuilds unless explicitly cleared.
- How does the container image behave when required environment variables (e.g., database connection details) are missing? The application should fail fast with a descriptive message at startup.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The project MUST be initialised with the newest stable release of Spring Boot and the newest Java LTS release as the runtime target.
- **FR-002**: The project MUST use Maven as its build tool, with a standard `pom.xml` at the project root. The Maven coordinates MUST be `groupId: net.fabcelhaft`, `artifactId: hackathon-organiser`.
- **FR-003**: The project MUST include a `Dockerfile` that produces a runnable container image of the application from the Maven build output. The Dockerfile MUST use a single-stage build that copies the assembled fat JAR produced by Maven into the image. The base image MUST be `eclipse-temurin:<java-lts-version>-jre-alpine` (Temurin JRE on Alpine Linux, runtime-only, minimal size).
- **FR-004**: The project MUST include a Docker Compose file that starts the application together with a PostgreSQL database, wiring the two together via environment-based configuration. The application service MUST expose port `8080` on the host.
- **FR-005**: The project MUST include a `.devcontainer/devcontainer.json` that gives developers a fully configured local environment (Java, Maven, running PostgreSQL) without manual installation steps. Port `8080` MUST be listed in `forwardPorts` so the running application is reachable from the host browser.
- **FR-006**: The project MUST include a GitHub Actions workflow that automatically compiles the project and runs all tests on every push to any branch.
- **FR-007**: The GitHub Actions workflow MUST build the container image and push it to the GitHub Container Registry on every successful push to the main branch, tagging the image with both the commit SHA and `latest`. When a git tag is pushed, the workflow MUST additionally publish the image tagged with that git tag name (e.g., `v1.0.0`).
- **FR-008**: The application MUST expose a health-check endpoint at `/actuator/health` (via Spring Boot Actuator) that confirms the service is running and its database connection is active. The endpoint MUST report database connectivity status as a sub-indicator.
- **FR-009**: The application MUST follow the Reactive-First principle from the project constitution: all HTTP handling MUST use Spring WebFlux.
- **FR-010**: Database connectivity configuration MUST be supplied through environment variables so that the same image can run against different database instances without rebuilding.
- **FR-011**: The service MUST manage its database schema using Spring Boot's built-in `schema.sql` initialisation (R2DBC schema runner). No external migration tooling is required at this stage. The `schema.sql` file MUST be placed under `src/main/resources/` and applied automatically on startup.

### Key Entities

- **Application Service**: The Spring Boot process that handles HTTP requests and owns the business logic. Depends on the database at startup.
- **PostgreSQL Database**: The relational data store. Used by the application service. Managed as a compose service with a named volume for data persistence.
- **Container Image**: The packaged, runnable artefact produced from the application source code. Published to GitHub Container Registry and tagged per build.
- **CI Pipeline**: The automated workflow that validates code quality and publishes the container image. Triggered on push events.
- **Dev Container**: The local development environment definition that provides a consistent toolchain and a running database to all contributors.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A developer with only a container runtime installed can have the full local development environment running within 10 minutes of cloning the repository, following only the steps documented in the README.
- **SC-002**: Every push to the repository triggers a CI run that completes (pass or fail) within 10 minutes.
- **SC-003**: A successfully built container image is available in the GitHub Container Registry within 5 minutes of a passing push to the main branch.
- **SC-004**: The application's health-check endpoint responds within 2 seconds of the container reaching a running state with a connected database.
- **SC-005**: A developer can reproduce the exact same build result locally as the CI pipeline produces, with no environment-specific differences in test outcomes.

## Clarifications

### Session 2026-08-22

- Q: What path should the health-check endpoint be available at? → A: `/actuator/health` (Spring Boot Actuator default, includes database liveness sub-indicators)
- Q: Should the service manage its own database schema at startup, or start against a bare empty schema? → A: Spring Boot built-in `schema.sql` initialisation via R2DBC schema runner (no external migration tooling)
- Q: Should the Dockerfile use a multi-stage layered build or a single-stage fat JAR build? → A: Single-stage fat JAR (simpler Dockerfile, copies assembled JAR directly into image)
- Q: What container image tags should CI publish besides the commit SHA? → A: `latest` on every main-branch push; git tag name (e.g., `v1.0.0`) when a git tag is pushed
- Q: What port should the application listen on and be forwarded in the dev container and Compose service? → A: `8080` (Spring Boot default)
- Q: What Maven groupId and artifactId should the project use? → A: `groupId: net.fabcelhaft`, `artifactId: hackathon-organiser`
- Q: What base container image should the Dockerfile use to run the application JAR? → A: `eclipse-temurin:<lts-version>-jre-alpine` (Temurin JRE on Alpine, minimal runtime image)

## Assumptions

- The GitHub repository is already created and the GitHub Actions environment is available to the project.
- The project's GitHub Container Registry namespace matches the repository owner/name (standard GitHub Packages convention).
- The PostgreSQL version used in the compose stack and dev container is the current stable release; specific version pinning can be adjusted per operational requirements.
- The application does not require persistent data to be pre-seeded for development; an empty database at startup is sufficient.
- The dev container targets VS Code and JetBrains IDEs that support the `devcontainer.json` specification; other editors are out of scope.
- Multi-architecture image builds (e.g., ARM + AMD64) are out of scope for the initial bootstrap; a single-architecture image targeting the CI runner is sufficient.
- Secret management for the GitHub Container Registry push uses GitHub's built-in `GITHUB_TOKEN`; no external secret store is required.
