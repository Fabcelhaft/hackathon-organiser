# Implementation Plan: Core Domain Model & Organiser Management

**Branch**: `002-core-domain-model` | **Date**: 2026-08-23 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/002-core-domain-model/spec.md`

## Summary

Introduce the hackathon's core domain model — User, Participant, Skill, Custom Field Definition/Value, Topic, and Group — backed by reactive R2DBC/PostgreSQL persistence, OIDC-only authentication with a database-derived Organiser privilege, and a full set of organiser-only Thymeleaf CRUD views for managing all of it, isolated in a dedicated path and package. No participant-facing or self-service UI is built in this feature.

## Technical Context

**Language/Version**: Java 25 LTS (matches 001)

**Primary Dependencies**:
- `spring-boot-starter-webflux` — reactive HTTP layer (already present)
- `spring-boot-starter-data-r2dbc` — reactive persistence (already present)
- `org.postgresql:r2dbc-postgresql` (runtime, already present)
- `spring-boot-starter-thymeleaf` — server-side rendering for organiser views (new; auto-configures the reactive Thymeleaf engine when WebFlux is on the classpath and Spring MVC is not)
- `spring-boot-starter-oauth2-client` — reactive OIDC login (new; brings `spring-security-core`/`-config`/`-oauth2-client`/`-oauth2-jose`, no `spring-webmvc`)
- Pico CSS — vendored static asset, no new dependency
- No new library for UUID v7 and no application code: PostgreSQL 18's native `uuidv7()` function is used as
  each UUIDv7 primary key column's `DEFAULT` (see [research.md](research.md) §1)

**Storage**: PostgreSQL (existing Testcontainers/`docker-compose.yml` setup from 001) via R2DBC. Schema extended in `schema.sql` using the established idempotent `CREATE TABLE IF NOT EXISTS` convention — no Flyway/Liquibase (consistent with 001).

**Testing**: JUnit 5 + Mockito (unit); `WebTestClient` + `spring-security-test` reactive mock-login support (integration, no live IdP required — see [research.md](research.md) §6); Testcontainers PostgreSQL for repository/service integration tests. Test-First required (Constitution V).

**Target Platform**: Linux container (existing Dockerfile from 001) — no new deployment concerns.

**Project Type**: Web application — single reactive Spring Boot monolith serving server-rendered Thymeleaf pages (no separate frontend project).

**Performance Goals**: Not independently constrained by this feature; inherits 001's non-blocking, single-instance profile. Organiser list/detail views should render well under 500ms server-side at the roster sizes implied by a single hackathon (Assumptions: single ongoing hackathon, no multi-tenant scale).

**Constraints**: End-to-end non-blocking I/O (Constitution II) — no blocking JDBC/File calls on Reactor threads, including in the OIDC user-upsert path. No local credential store (FR-001). Organiser web layer physically separated by path (`/organiser/**`) and Java package (Constitution-adjacent, driven by FR-024).

**Scale/Scope**: Single ongoing hackathon (per spec Assumptions); 7 core entities, ~6 organiser CRUD areas, no multi-tenancy.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Spring Boot Native Only | ✅ PASS | `spring-boot-starter-thymeleaf` and `spring-boot-starter-oauth2-client` are official Spring Boot starters. UUID v7 is generated natively by PostgreSQL 18's built-in `uuidv7()` function as a column default — no third-party library and no application code at all. No non-Spring DI container or web framework introduced. |
| II. Reactive-First (WebFlux) | ✅ PASS | OAuth2/OIDC login uses Spring Security's reactive `SecurityWebFilterChain`; the custom `ReactiveOAuth2UserService` user-upsert runs entirely through R2DBC (non-blocking). All new controllers return `Mono<Rendering>`/`Mono<String>`/`Flux<T>`. No `spring-webmvc` introduced by any new dependency. |
| III. Thymeleaf SSR | ✅ PASS | All organiser views are Thymeleaf templates rendered reactively; no client-side rendering framework introduced. |
| IV. Pico CSS | ✅ PASS | Pico CSS vendored under `src/main/resources/static/css/`; no additional CSS framework. |
| V. Test-First Development | ✅ REQUIRED | Failing `WebTestClient` integration tests (using `mockOidcLogin()`/`mockUser()`) and Mockito unit tests for each service invariant MUST be committed before the corresponding implementation, per `tasks.md`. Reactive service chains (ParticipantService, GroupService, CustomFieldService) are additionally verified with `StepVerifier` per Development Workflow #4. |

**Post-design re-check**: See end of this document, after Phase 1.

## Project Structure

### Documentation (this feature)

```text
specs/002-core-domain-model/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   ├── user-management.md
│   ├── catalog-management.md
│   ├── participant-management.md
│   ├── topic-management.md
│   └── group-management.md
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
pom.xml                          # + spring-boot-starter-thymeleaf, spring-boot-starter-oauth2-client, spring-security-test (test)

src/
├── main/
│   ├── java/net/fabcelhaft/hackathonorganiser/
│   │   ├── HackathonOrganiserApplication.java   # existing (001)
│   │   ├── security/                            # cross-cutting: not a business domain, applies to all of them
│   │   │   ├── SecurityConfig.java              # SecurityWebFilterChain, /organiser/** -> ROLE_ORGANISER
│   │   │   ├── HackathonOidcUserService.java     # ReactiveOAuth2UserService: upsert User on login
│   │   │   └── HackathonOidcUser.java            # OidcUser wrapper exposing ROLE_ORGANISER
│   │   ├── user/                                # one package per business domain: entity + repo + service
│   │   │   ├── User.java                        # id UUID left null on insert; DB fills it (uuidv7() default)
│   │   │   ├── UserRepository.java
│   │   │   └── UserService.java
│   │   ├── participant/
│   │   │   ├── Participant.java, ParticipantStatus.java
│   │   │   ├── CustomFieldValue.java            # a Participant's answer to a Custom Field (composite key)
│   │   │   ├── ParticipantRepository.java
│   │   │   └── ParticipantService.java
│   │   ├── skill/
│   │   │   ├── Skill.java
│   │   │   ├── SkillRepository.java
│   │   │   └── SkillService.java
│   │   ├── customfield/
│   │   │   ├── CustomFieldDefinition.java, CustomFieldType.java, CustomFieldOption.java
│   │   │   ├── CustomFieldDefinitionRepository.java, CustomFieldOptionRepository.java
│   │   │   └── CustomFieldService.java
│   │   ├── topic/
│   │   │   ├── Topic.java
│   │   │   ├── TopicRepository.java
│   │   │   └── TopicService.java
│   │   ├── group/
│   │   │   ├── Group.java, GroupStatus.java, GroupMember.java
│   │   │   ├── GroupRepository.java
│   │   │   └── GroupService.java
│   │   └── organiser/                           # FR-024: distinct package for the organiser web layer
│   │       ├── user/UserController.java
│   │       ├── participant/ParticipantController.java
│   │       ├── skill/SkillController.java
│   │       ├── customfield/CustomFieldController.java
│   │       ├── topic/TopicController.java
│   │       └── group/GroupController.java
│   └── resources/
│       ├── application.yml                     # + spring.security.oauth2.client.* (env-driven)
│       ├── schema.sql                           # extended with new tables; UUIDv7 PKs use `DEFAULT uuidv7()`
│       ├── static/css/pico.min.css              # vendored (Constitution IV)
│       └── templates/organiser/                 # FR-024: templates isolated under organiser/
│           ├── fragments/layout.html
│           ├── users/, participants/, skills/, custom-fields/, topics/, groups/
└── test/
    └── java/net/fabcelhaft/hackathonorganiser/
        ├── organiser/{user,participant,skill,customfield,topic,group}/  # WebTestClient + mockOidcLogin() ITs
        ├── security/                            # HackathonOidcUserService unit + IT
        └── {user,participant,skill,customfield,topic,group}/            # Mockito unit tests for invariants

docker-compose.yml                   # + dex service (dev-only OIDC, manual visual smoke-test only)
dex/config.yaml                      # Dex static client + static test user (quickstart.md)
```

**Structure Decision**: Single Maven/Spring Boot project (no frontend/backend split — Thymeleaf renders server-side into the same process per Constitution III). Packages are organized by *business domain*, not by technical layer: `user`, `participant`, `skill`, `customfield`, `topic`, and `group` each bundle that domain's entity, repository, and service together, rather than spreading them across flat `domain`/`repository`/`service` buckets. These per-domain packages stay in the top-level `net.fabcelhaft.hackathonorganiser` package tree because they are shared foundations the spec's own Assumptions say future (non-organiser) features will reuse; only the organiser-facing controllers and templates are isolated under `organiser/` (package, itself split into the same six business-domain subpackages) and `templates/organiser/` + `/organiser/**` routes (path), satisfying FR-024 without forcing a package move when participant-facing views arrive later. `security` remains a standalone cross-cutting package since OIDC login/authorization applies uniformly across every business domain rather than belonging to one.

## Complexity Tracking

*No constitution violations. No complexity justification required.*

## Post-Design Constitution Re-check

*Re-evaluated after Phase 1 (data-model.md, contracts/, quickstart.md).*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Spring Boot Native Only | ✅ PASS | Final dependency set unchanged from the initial check: two official starters added, zero third-party libraries. UUID v7 generation is delegated entirely to PostgreSQL 18's native `uuidv7()` column default; the application contains no ID-generation code at all. |
| II. Reactive-First (WebFlux) | ✅ PASS | Data model's invariants (data-model.md) are enforced either by Postgres partial unique indexes (no app-side blocking) or single-statement service-layer pre-checks composed into the same reactive chain as the write — no blocking calls introduced. |
| III. Thymeleaf SSR | ✅ PASS | All contracts/*.md routes are server-rendered form/list/detail pages; no JSON API surface was introduced. |
| IV. Pico CSS | ✅ PASS | No change from initial check. |
| V. Test-First Development | ✅ PASS | quickstart.md enumerates the `*IT` integration test per story and the invariant unit tests, all to be written before their corresponding implementation per `tasks.md`. `StepVerifier` usage for non-trivial reactive chains (Development Workflow #4) is called out explicitly in each relevant task. |

No violations. Complexity Tracking remains empty.
