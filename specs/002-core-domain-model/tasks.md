---

description: "Task list for feature implementation"
---

# Tasks: Core Domain Model & Organiser Management

**Input**: Design documents from `/specs/002-core-domain-model/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: Constitution Principle V (Test-First Development) is NON-NEGOTIABLE for this project — every task list below includes failing tests written before their implementation, per the Red-Green-Refactor cycle.

**Organization**: Tasks are grouped by user story (P1–P3 from spec.md) to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: Which user story this task belongs to (US1–US5)
- File paths are relative to the repository root

## Path Conventions

Single Maven/Spring Boot project (see [plan.md](plan.md) Project Structure):
- Main code: `src/main/java/net/fabcelhaft/hackathonorganiser/`
- Templates: `src/main/resources/templates/organiser/`
- Static assets: `src/main/resources/static/`
- Tests: `src/test/java/net/fabcelhaft/hackathonorganiser/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Add the dependencies and shared assets every story's Thymeleaf views and security-gated tests need.

- [ ] T001 Add `spring-boot-starter-thymeleaf` and `spring-boot-starter-oauth2-client` (main scope) and `spring-security-test` (test scope) dependencies to `pom.xml`
- [ ] T002 [P] Vendor the Pico CSS minified stylesheet at `src/main/resources/static/css/pico.min.css` (Constitution IV, research.md §5)
- [ ] T003 [P] Create the shared organiser Thymeleaf layout fragment at `src/main/resources/templates/organiser/fragments/layout.html`, referencing `@{/css/pico.min.css}` and providing a `content` insertion point plus a placeholder top nav (links added in Polish, T061)
- [ ] T004 Add env-driven `spring.security.oauth2.client.registration.*` / `.provider.*` placeholders to `src/main/resources/application.yml`, left undeclared/environment-only per the existing R2DBC convention in that file (001) so a missing environment fails fast rather than silently
- [ ] T005 [P] Add dummy OIDC client-registration test properties (fake `client-id`/`client-secret`/`issuer-uri` or an equivalent test-only `ClientRegistrationRepository` bean) under `src/test/resources/application.yml` so `WebTestClient` integration tests using `mockOidcLogin()`/`mockUser()` can boot the real `SecurityWebFilterChain` with no live IdP (research.md §6)
- [ ] T006 [P] Add a dev-only Dex OIDC provider (`dex/config.yaml` + a `dex` service in `docker-compose.yml`) with one static client and one static test user, used solely for the mandatory visual smoke-test in T063 (Constitution Development Workflow #3) — not used by any automated test

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Infrastructure every entity in every story depends on.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [ ] T007 [P] Write failing unit test for the UUID v7 generator — asserts version nibble `0111`, IETF variant bits, and that 10k generated values are unique and monotonically non-decreasing by timestamp — in `src/test/java/net/fabcelhaft/hackathonorganiser/common/Uuid7GeneratorTest.java`
- [ ] T008 Implement `Uuid7Generator.generate(): UUID` in `src/main/java/net/fabcelhaft/hackathonorganiser/common/Uuid7Generator.java` per research.md §1, making T007 pass

**Checkpoint**: Foundation ready — user story implementation can now begin in priority order.

---

## Phase 3: User Story 1 - Identity & Role Recognition (Priority: P1) 🎯 MVP

**Goal**: A person authenticating via OIDC is auto-recognised as a Standard user; an Organiser can grant/revoke the Organiser privilege for any user, effective on the next access check.

**Independent Test**: A new person logs in via the identity provider and a corresponding User record now exists, treated as Standard; an Organiser then flips the Organiser privilege for that record and the change takes effect on the next access check.

### Tests for User Story 1 ⚠️ write first, confirm they fail

- [ ] T009 [P] [US1] Write failing `WebTestClient` integration tests covering: first-login auto-provisioning with `organiser=false` (SC-001), listing/viewing users, an Organiser granting/revoking the privilege via `POST /organiser/users/{id}/organiser`, a revoked privilege denying the *next* request, and a non-Organiser denied on every route in [contracts/user-management.md](contracts/user-management.md) — in `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/web/UserManagementIT.java`
- [ ] T010 [P] [US1] Write failing unit tests for the OIDC user-upsert logic — creates a User on first login keyed by `sub`, refreshes `display_name`/`email` on a subsequent login without creating a duplicate, matches on `sub` even when profile fields changed — in `src/test/java/net/fabcelhaft/hackathonorganiser/security/HackathonOidcUserServiceTest.java`

### Implementation for User Story 1

- [ ] T011 [P] [US1] Create the `User` R2DBC entity (`id`, `oidcSubject`, `displayName`, `email`, `organiser`, `createdAt`, `updatedAt`) in `src/main/java/net/fabcelhaft/hackathonorganiser/domain/User.java`, IDs generated via `Uuid7Generator`
- [ ] T012 [P] [US1] Create `UserRepository extends ReactiveCrudRepository<User, UUID>` with a derived `Mono<User> findByOidcSubject(String oidcSubject)` in `src/main/java/net/fabcelhaft/hackathonorganiser/repository/UserRepository.java`
- [ ] T013 [US1] Add the `users` table DDL (`CREATE TABLE IF NOT EXISTS`, unique index on `oidc_subject`) to `src/main/resources/schema.sql`, depends on T011
- [ ] T014 [P] [US1] Implement the `HackathonOidcUser` wrapper (implements `OidcUser`, exposes `ROLE_ORGANISER` when the backing `User.organiser` is true, `ROLE_USER` otherwise) in `src/main/java/net/fabcelhaft/hackathonorganiser/security/HackathonOidcUser.java`
- [ ] T015 [US1] Implement `HackathonOidcUserService implements ReactiveOAuth2UserService<OidcUserRequest, OidcUser>` — upsert-by-`sub`, refresh profile fields, wrap in `HackathonOidcUser` — in `src/main/java/net/fabcelhaft/hackathonorganiser/security/HackathonOidcUserService.java`, depends on T012, T014 (implements T010's contract)
- [ ] T016 [US1] Implement `SecurityConfig` (`SecurityWebFilterChain`: `.oauth2Login()` wired to `HackathonOidcUserService`, `.pathMatchers("/organiser/**").hasRole("ORGANISER")`, everything else `.authenticated()`) in `src/main/java/net/fabcelhaft/hackathonorganiser/security/SecurityConfig.java`, depends on T015, T004
- [ ] T017 [P] [US1] Implement `UserService` (list all, find by id, toggle `organiser` flag — FR-004) in `src/main/java/net/fabcelhaft/hackathonorganiser/service/UserService.java`, depends on T012
- [ ] T018 [US1] Implement `UserController` (`GET /organiser/users`, `GET /organiser/users/{id}`, `POST /organiser/users/{id}/organiser`) per [contracts/user-management.md](contracts/user-management.md) in `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/web/UserController.java`, depends on T017, T016 — makes T009 pass
- [ ] T019 [P] [US1] Create Thymeleaf templates `list.html` and `detail.html` under `src/main/resources/templates/organiser/users/`, extending the shared layout fragment (T003)

**Checkpoint**: User Story 1 is fully functional and testable independently — `mvn verify -Dit.test=UserManagementIT`.

---

## Phase 4: User Story 2 - Organiser Configures Skills & Custom Fields (Priority: P1)

**Goal**: An Organiser maintains the Skill catalog and the Custom Field Definition catalog (free-text or multi-select, required or optional) that Participant records and Topics will draw on.

**Independent Test**: An Organiser creates a new Skill and a new Custom Field definition (one free-text, one multi-select with options) and both are persisted and appear in their respective catalogs.

### Tests for User Story 2 ⚠️ write first, confirm they fail

- [ ] T020 [P] [US2] Write failing `WebTestClient` integration tests for Skill catalog CRUD, case-insensitive duplicate-name rejection (FR-008a), the delete-guard (FR-023), and a non-Organiser denied on every route in that contract (FR-022, SC-004) per [contracts/catalog-management.md](contracts/catalog-management.md) — in `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/web/SkillManagementIT.java`
- [ ] T021 [P] [US2] Write failing `WebTestClient` integration tests for Custom Field Definition CRUD (free-text and multi-select with options, FR-011/FR-012), the type-lock once a value exists (FR-012a), the option delete-guard (FR-012b), and the definition delete-guard (FR-023), and a non-Organiser denied on every route in that contract (FR-022, SC-004) per [contracts/catalog-management.md](contracts/catalog-management.md) — in `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/web/CustomFieldManagementIT.java`
- [ ] T022 [P] [US2] Write failing unit tests for `SkillService` (case-insensitive create/rename uniqueness, delete blocked while referenced) in `src/test/java/net/fabcelhaft/hackathonorganiser/service/SkillServiceTest.java`
- [ ] T023 [P] [US2] Write failing unit tests for `CustomFieldService` (multi-select requires ≥1 option, type-lock once a value exists, option removal blocked while referenced, definition removal blocked while referenced) in `src/test/java/net/fabcelhaft/hackathonorganiser/service/CustomFieldServiceTest.java`

### Implementation for User Story 2

- [ ] T024 [P] [US2] Create the `Skill` entity (`id`, `name`, `createdAt`, `updatedAt`) in `src/main/java/net/fabcelhaft/hackathonorganiser/domain/Skill.java`
- [ ] T025 [P] [US2] Create the `CustomFieldType` enum (`FREE_TEXT`, `MULTI_SELECT`) and `CustomFieldDefinition` entity (`id`, `label`, `fieldType`, `required`, `createdAt`, `updatedAt`) in `src/main/java/net/fabcelhaft/hackathonorganiser/domain/CustomFieldType.java` and `.../domain/CustomFieldDefinition.java`
- [ ] T026 [P] [US2] Create the `CustomFieldOption` entity (`id`, `customFieldDefinitionId`, `label`, `createdAt`, `updatedAt`) in `src/main/java/net/fabcelhaft/hackathonorganiser/domain/CustomFieldOption.java`
- [ ] T027 [US2] Add `skills` (unique index on `lower(name)`), `custom_field_definitions`, and `custom_field_options` (unique index on `(custom_field_definition_id, lower(label))`) table DDL to `src/main/resources/schema.sql`, depends on T024–T026
- [ ] T028 [P] [US2] Create `SkillRepository extends ReactiveCrudRepository<Skill, UUID>` in `src/main/java/net/fabcelhaft/hackathonorganiser/repository/SkillRepository.java`
- [ ] T029 [P] [US2] Create `CustomFieldDefinitionRepository` and `CustomFieldOptionRepository` (both `ReactiveCrudRepository`, latter with a derived `Flux<CustomFieldOption> findByCustomFieldDefinitionId(UUID id)`) in `src/main/java/net/fabcelhaft/hackathonorganiser/repository/`
- [ ] T030 [US2] Implement `SkillService` (create/rename with case-insensitive uniqueness FR-008a, delete with reference-guard FR-023 checking `participant_skills`/`topic_skills` via `R2dbcEntityTemplate`) in `src/main/java/net/fabcelhaft/hackathonorganiser/service/SkillService.java`, depends on T028 — makes T022 pass
- [ ] T031 [US2] Implement `CustomFieldService` (create/edit definitions incl. type-lock FR-012a, options CRUD incl. option delete-guard FR-012b via `R2dbcEntityTemplate` against `custom_field_value_options`, definition delete-guard FR-023 against `custom_field_values`) in `src/main/java/net/fabcelhaft/hackathonorganiser/service/CustomFieldService.java`, depends on T029 — makes T023 pass
- [ ] T032 [US2] Implement `SkillController` (list/new/create/edit/update/delete routes) per [contracts/catalog-management.md](contracts/catalog-management.md) in `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/web/SkillController.java`, depends on T030 — makes T020 pass
- [ ] T033 [US2] Implement `CustomFieldController` (list/new/create/edit/update/delete + options add/remove routes) per [contracts/catalog-management.md](contracts/catalog-management.md) in `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/web/CustomFieldController.java`, depends on T031 — makes T021 pass
- [ ] T034 [P] [US2] Create Thymeleaf templates `list.html` and `form.html` under `src/main/resources/templates/organiser/skills/`
- [ ] T035 [P] [US2] Create Thymeleaf templates `list.html` and `form.html` (with dynamic option rows for `MULTI_SELECT`) under `src/main/resources/templates/organiser/custom-fields/`

**Checkpoint**: User Stories 1 and 2 both work independently — `mvn verify -Dit.test=UserManagementIT,SkillManagementIT,CustomFieldManagementIT`.

---

## Phase 5: User Story 3 - Organiser Manages Participant Records (Priority: P2)

**Goal**: An Organiser registers a User as a Participant and manages that Participant's status, Skill selections, and Custom Field values.

**Independent Test**: Create a Participant record for a known User, set its status, assign Skills, and fill in Custom Field values; confirm all of it is retrievable and editable afterward.

**Depends on**: User Story 1 (Users to register, security gate) and User Story 2 (Skill/Custom Field catalogs).

### Tests for User Story 3 ⚠️ write first, confirm they fail

- [ ] T036 [P] [US3] Write failing `WebTestClient` integration tests for Participant registration (incl. rejecting a second Participant for the same User, FR-006a), status change, Skill assignment, Custom Field value set + type validation (FR-014), the incomplete-participant indicator on the list view (FR-027, SC-007), and a non-Organiser denied on every route in that contract (FR-022, SC-004) per [contracts/participant-management.md](contracts/participant-management.md) — in `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/web/ParticipantManagementIT.java`
- [ ] T037 [P] [US3] Write failing unit tests for `ParticipantService` (single Participant per User FR-006a, initial status `ACTIVE` FR-006b, status restricted to the three-value enum FR-007, Custom Field value validated against the field's configured type FR-014, incomplete computation FR-027) in `src/test/java/net/fabcelhaft/hackathonorganiser/service/ParticipantServiceTest.java`

### Implementation for User Story 3

- [ ] T038 [P] [US3] Create the `ParticipantStatus` enum (`ACTIVE`, `NOT_PARTICIPATED`, `REVOKED`) and `Participant` entity (`id`, `userId`, `status`, `createdAt`, `updatedAt`) in `src/main/java/net/fabcelhaft/hackathonorganiser/domain/ParticipantStatus.java` and `.../domain/Participant.java`
- [ ] T039 [P] [US3] Create the `CustomFieldValue` entity (`participantId`, `customFieldDefinitionId`, `freeTextValue`, `createdAt`, `updatedAt`) for the composite-key `custom_field_values` table in `src/main/java/net/fabcelhaft/hackathonorganiser/domain/CustomFieldValue.java`
- [ ] T040 [US3] Add `participants` (unique index on `user_id`), `participant_skills`, `custom_field_values` (composite PK), and `custom_field_value_options` (composite PK) table DDL to `src/main/resources/schema.sql`, depends on T038, T039 and the `users`/`skills`/`custom_field_definitions`/`custom_field_options` tables from T013/T027
- [ ] T041 [P] [US3] Create `ParticipantRepository extends ReactiveCrudRepository<Participant, UUID>` with a derived `Mono<Participant> findByUserId(UUID userId)` in `src/main/java/net/fabcelhaft/hackathonorganiser/repository/ParticipantRepository.java`
- [ ] T042 [US3] Implement `ParticipantService` (register FR-006b, change status FR-007, replace Skill selections via `R2dbcEntityTemplate` against `participant_skills` FR-009, set/validate Custom Field values via `R2dbcEntityTemplate` against `custom_field_values`/`custom_field_value_options` FR-013/FR-014, compute the incomplete flag FR-027) in `src/main/java/net/fabcelhaft/hackathonorganiser/service/ParticipantService.java`, depends on T041, T030 (Skill lookups), T031 (Custom Field definition/option lookups) — makes T037 pass
- [ ] T043 [US3] Implement `ParticipantController` (list/new/create/detail/status/skills/custom-field routes) per [contracts/participant-management.md](contracts/participant-management.md) in `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/web/ParticipantController.java`, depends on T042 — makes T036 pass
- [ ] T044 [P] [US3] Create Thymeleaf templates `list.html` (with incomplete indicator), `new.html`, and `detail.html` (status, Skill picker, Custom Field value form) under `src/main/resources/templates/organiser/participants/`

**Checkpoint**: User Stories 1–3 all work independently — `mvn verify -Dit.test=UserManagementIT,SkillManagementIT,CustomFieldManagementIT,ParticipantManagementIT`.

---

## Phase 6: User Story 4 - Topics with Skills and Creator (Priority: P2)

**Goal**: The system records Topics with a name, description, associated Skills, and creator; an Organiser can view and edit any Topic.

**Independent Test**: Create a Topic with a name, description, creator, and one or more Skills; confirm it is retrievable and editable afterward.

**Depends on**: User Story 1 (Users as creators) and User Story 2 (Skill catalog).

### Tests for User Story 4 ⚠️ write first, confirm they fail

- [ ] T045 [P] [US4] Write failing `WebTestClient` integration tests for Topic create/view/edit, Skill associations (FR-010), creator persistence (incl. retained after the creator's access is later revoked), and a non-Organiser denied on every route in that contract (FR-022, SC-004) per [contracts/topic-management.md](contracts/topic-management.md) — in `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/web/TopicManagementIT.java`
- [ ] T046 [P] [US4] Write failing unit tests for `TopicService` (name/description/creator required FR-015, Skill association replace) in `src/test/java/net/fabcelhaft/hackathonorganiser/service/TopicServiceTest.java`

### Implementation for User Story 4

- [ ] T047 [P] [US4] Create the `Topic` entity (`id`, `name`, `description`, `createdByUserId`, `createdAt`, `updatedAt`) in `src/main/java/net/fabcelhaft/hackathonorganiser/domain/Topic.java`
- [ ] T048 [US4] Add `topics` and `topic_skills` table DDL to `src/main/resources/schema.sql`, depends on T047 and the `users`/`skills` tables from T013/T027
- [ ] T049 [P] [US4] Create `TopicRepository extends ReactiveCrudRepository<Topic, UUID>` in `src/main/java/net/fabcelhaft/hackathonorganiser/repository/TopicRepository.java`
- [ ] T050 [US4] Implement `TopicService` (create/edit with required fields FR-015, Skill association replace via `R2dbcEntityTemplate` against `topic_skills` FR-010) in `src/main/java/net/fabcelhaft/hackathonorganiser/service/TopicService.java`, depends on T049 — makes T046 pass
- [ ] T051 [US4] Implement `TopicController` (list/new/create/detail/edit/update routes) per [contracts/topic-management.md](contracts/topic-management.md) in `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/web/TopicController.java`, depends on T050 — makes T045 pass
- [ ] T052 [P] [US4] Create Thymeleaf templates `list.html`, `form.html`, and `detail.html` under `src/main/resources/templates/organiser/topics/`

**Checkpoint**: User Stories 1–4 all work independently.

---

## Phase 7: User Story 5 - Groups Formed Around a Topic (Priority: P3)

**Goal**: A formed team is represented as a Group tied to exactly one Topic (at most one active Group per Topic) with Participant members (at most one active Group per Participant); an Organiser can view, edit, and disband Groups.

**Independent Test**: Create a Group linked to an existing Topic, add existing Participants as members; confirm the Group's Topic and membership are retrievable and editable afterward.

**Depends on**: User Story 3 (Participants as members) and User Story 4 (Topics to attach to).

### Tests for User Story 5 ⚠️ write first, confirm they fail

- [ ] T053 [P] [US5] Write failing `WebTestClient` integration tests for Group create (incl. rejecting a second active Group for a Topic that already has one, FR-016a), member add/remove (incl. rejecting a Participant already in a different active Group, FR-017), disband (memberships flip inactive, Topic becomes eligible again, disbanded Group and its former members remain viewable, FR-016b), and a non-Organiser denied on every route in that contract (FR-022, SC-004) per [contracts/group-management.md](contracts/group-management.md) — in `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/web/GroupManagementIT.java`
- [ ] T054 [P] [US5] Write failing unit tests for `GroupService` (create blocked when Topic already has an active Group, add-member blocked when Participant already has a different active Group, disband flips the Group `DISBANDED` and every membership `active=false`) in `src/test/java/net/fabcelhaft/hackathonorganiser/service/GroupServiceTest.java`

### Implementation for User Story 5

- [ ] T055 [P] [US5] Create the `GroupStatus` enum (`ACTIVE`, `DISBANDED`), `Group` entity (`id`, `topicId`, `status`, `disbandedAt`, `createdAt`, `updatedAt`), and `GroupMember` entity (`groupId`, `participantId`, `active`, `joinedAt`, composite key) in `src/main/java/net/fabcelhaft/hackathonorganiser/domain/GroupStatus.java`, `.../domain/Group.java`, `.../domain/GroupMember.java`
- [ ] T056 [US5] Add `groups` (partial unique index `UNIQUE (topic_id) WHERE status = 'ACTIVE'`) and `group_members` (partial unique index `UNIQUE (participant_id) WHERE active`) table DDL to `src/main/resources/schema.sql`, depends on T055 and the `topics`/`participants` tables from T048/T040
- [ ] T057 [P] [US5] Create `GroupRepository extends ReactiveCrudRepository<Group, UUID>` in `src/main/java/net/fabcelhaft/hackathonorganiser/repository/GroupRepository.java`
- [ ] T058 [US5] Implement `GroupService` (create with active-Topic guard FR-016a, add/remove member via `R2dbcEntityTemplate` against `group_members` with active-Participant guard FR-017, disband: flip `status`/`disbandedAt` and every membership's `active` FR-016b) in `src/main/java/net/fabcelhaft/hackathonorganiser/service/GroupService.java`, depends on T057 — makes T054 pass
- [ ] T059 [US5] Implement `GroupController` (list/new/create/detail/members add/members remove/disband routes) per [contracts/group-management.md](contracts/group-management.md) in `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/web/GroupController.java`, depends on T058 — makes T053 pass
- [ ] T060 [P] [US5] Create Thymeleaf templates `list.html`, `new.html` (Topic picker restricted to Topics with no active Group), and `detail.html` (current + historical members, disband action) under `src/main/resources/templates/organiser/groups/`

**Checkpoint**: All five user stories are independently functional — the full core domain model and organiser management feature is complete.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Tie the six organiser sections together and confirm the whole feature end-to-end.

- [ ] T061 [P] Add navigation links (Users, Participants, Skills, Custom Fields, Topics, Groups) to the shared layout fragment `src/main/resources/templates/organiser/fragments/layout.html`
- [ ] T062 Run `mvn verify` (all unit + `*IT` tests green) and walk [quickstart.md](quickstart.md), confirming SC-001 through SC-007 are all satisfied
- [ ] T063 Run the mandatory visual smoke-test (Constitution Development Workflow #3): `docker compose up db dex`, `mvn spring-boot:run` with the Dex-pointed OIDC env vars from quickstart.md, log in as the Dex static user, and visually confirm each organiser section's list/form/detail pages render correctly in a browser

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **Foundational (Phase 2)**: Depends on Setup — BLOCKS all user stories
- **User Story 1 (Phase 3)**: Depends on Foundational only
- **User Story 2 (Phase 4)**: Depends on Foundational only (independent of US1's User entity, though both are P1)
- **User Story 3 (Phase 5)**: Depends on US1 (Users) and US2 (Skill/Custom Field catalogs)
- **User Story 4 (Phase 6)**: Depends on US1 (Users as creators) and US2 (Skill catalog)
- **User Story 5 (Phase 7)**: Depends on US3 (Participants) and US4 (Topics)
- **Polish (Phase 8)**: Depends on all five user stories being complete

### User Story Dependencies

```text
Foundational
├── US1 (Identity & Role Recognition) ─────────┐
├── US2 (Skills & Custom Fields) ───────────────┤
│                                                ├─→ US3 (Participants) ─┐
│                                                │                       ├─→ US5 (Groups)
│                                                └─→ US4 (Topics) ───────┘
```

US1 and US2 can be built in parallel by different developers once Foundational is done. US3 and US4 can then
be built in parallel once both US1 and US2 are done. US5 requires both US3 and US4.

### Within Each User Story

- Tests MUST be written and confirmed failing before implementation (Constitution V)
- Entities before repositories before services before controllers before templates
- `schema.sql` additions depend on the entity classes they back, and on any earlier story's tables they
  foreign-key to

### Parallel Opportunities

- T002, T003, T006 (Setup) in parallel; T005 in parallel with T002–T004
- T007 (Foundational test) can be written in parallel with any Setup task
- Within US1: T009, T010 (tests) in parallel; T011, T012, T014 (entity/repo/wrapper) in parallel; T017 in
  parallel with the security chain (T014–T016); T019 (templates) in parallel with backend tasks once T018's
  route names are known
- Within US2: T020–T023 (tests) in parallel; T024–T026 (entities) in parallel; T028, T029 (repos) in
  parallel; T034, T035 (templates) in parallel
- Within US3: T036, T037 (tests) in parallel; T038, T039 (entities) in parallel
- Within US4: T045, T046 (tests) in parallel; T047 standalone
- Within US5: T053, T054 (tests) in parallel; T055 standalone
- US1 and US2 phases in parallel (different developers); US3 and US4 phases in parallel once both are done

---

## Parallel Example: User Story 1

```bash
# Tests together:
Task: "Write failing WebTestClient integration tests for User Story 1 in organiser/web/UserManagementIT.java"
Task: "Write failing unit tests for HackathonOidcUserService in security/HackathonOidcUserServiceTest.java"

# Entity/repo/wrapper together:
Task: "Create User entity in domain/User.java"
Task: "Create UserRepository in repository/UserRepository.java"
Task: "Create HackathonOidcUser wrapper in security/HackathonOidcUser.java"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1 (Setup) and Phase 2 (Foundational)
2. Complete Phase 3 (User Story 1)
3. **STOP and VALIDATE**: `mvn verify -Dit.test=UserManagementIT` — new logins are recognised, Organiser
   grant/revoke works and takes effect on the next access check
4. Demo if ready — this alone proves the OIDC + role-derivation foundation everything else builds on

### Incremental Delivery

1. Setup + Foundational → ready
2. US1 → validate independently → demo (identity/roles work)
3. US2 → validate independently → demo (organiser can now populate the Skill/Custom Field catalogs)
4. US3 → validate independently → demo (organiser can now run the participant roster)
5. US4 → validate independently → demo (organiser can now record Topics)
6. US5 → validate independently → demo (organiser can now form/disband Groups) — full feature complete
7. Polish → nav wiring + full-suite/quickstart confirmation

### Parallel Team Strategy

1. Team completes Setup + Foundational together
2. Developer A takes US1, Developer B takes US2 (both P1, both only depend on Foundational)
3. Once US1 and US2 are both done: Developer A takes US3, Developer B takes US4
4. Once US3 and US4 are both done: either developer takes US5
5. Polish once all five stories are merged

---

## Notes

- [P] tasks touch different files and have no incomplete-task dependency
- [Story] label maps each task to its user story for traceability back to spec.md
- Composite-key tables (`custom_field_values`, `custom_field_value_options`, `participant_skills`,
  `topic_skills`, `group_members`) are manipulated via `R2dbcEntityTemplate`/`DatabaseClient` inside the
  owning service class rather than through a dedicated `ReactiveCrudRepository`, since Spring Data R2DBC
  repositories require a single-column ID (data-model.md, research.md §4)
- Verify each story's tests fail before implementing it; commit after each task or logical group
- Stop at any checkpoint to validate a story independently before moving on
