---

description: "Task list for feature implementation"
---

# Tasks: Topic Management, Group Formation & Compliance

**Input**: Design documents from `/specs/005-topic-management/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: Constitution Principle V (Test-First Development) is NON-NEGOTIABLE for this project — every task
list below includes failing tests written before their implementation, per the Red-Green-Refactor cycle.

**Reactive verification**: Per Constitution Development Workflow #4, unit tests exercising a service's reactive
chain (any test asserting a `Mono`/`Flux` result where the chain composes more than one operator) MUST use
`StepVerifier`, not a blocking `.block()` call. This applies to every `*ServiceTest` task below.

**Organization**: Tasks are grouped by user story (P1–P3 from spec.md) to enable independent implementation and
testing of each story. Within each story, code is grouped by business concept, matching plan.md's package
layout (extensions to `topic`, `group`, `organisersettings`, `customfield`; a new `compliance` domain package;
extensions to the self-service `topics` and `home` web packages; new/extended `organiser.compliance` and
`organiser.group`/`organiser.settings` web packages).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: Which user story this task belongs to (US1–US8)
- File paths are relative to the repository root

## Path Conventions

Single Maven/Spring Boot project (see [plan.md](plan.md) Project Structure), extending 002–004's layout:
- Main code: `src/main/java/net/fabcelhaft/hackathonorganiser/` — extended domain packages `topic/`, `group/`,
  `organisersettings/`, `customfield/`; new domain package `compliance/`; extended self-service web packages
  `topics/`, `home/`; extended/new organiser web packages `organiser/settings/`, `organiser/group/`,
  `organiser/compliance/`
- Templates: extended `home/index.html`, `topics/form.html`; new `topics/overview.html`,
  `organiser/compliance/form.html`; extended `organiser/settings/form.html`, `organiser/groups/detail.html`,
  `fragments/layout.html`
- Tests: `src/test/java/net/fabcelhaft/hackathonorganiser/`, mirroring the same packages, plus new
  `compliance/`, `topic/TopicDiscoveryServiceTest.java`, `topic/TopicJoinServiceTest.java`,
  `topics/TopicJoinManagementIT.java`, `topics/TopicOverviewManagementIT.java`,
  `organiser/compliance/ComplianceManagementIT.java`, and two new `a11y/*IT` classes

No new dependency is added by this feature (Constitution Check, plan.md) — the Join race is closed with the
same `TransactionalOperator` bean (already exposed by 004's `TransactionalOperatorConfig`) plus a native
Postgres advisory lock, keyed differently than 004's but the identical mechanism (research.md §2) — so there is
no Setup phase; work begins directly at Foundational.

---

## Phase 1: Foundational (Blocking Prerequisites)

**Purpose**: Schema and entity extensions genuinely shared by every later user story: the Compliance Ruleset's
core fields on `organiser_settings` (read by the Join capacity check, compliance evaluation, and both discovery
tables), the Group override flag, the new diversity-requirement collection and its evaluator, and the extended
Custom Field delete-guard.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### Tests (write first, confirm they fail)

- [ ] T001 [P] Write failing unit tests for `OrganiserSettingsService.update(...)`'s four new parameters
      (`maxGroupMembers`: rejects `null`→no-op-preserving vs. `< 1` raising `OrganiserSettingsConflictException`
      with no field changed, FR-011b; `minGroupMembers`: `null` clears it, a value `> maxGroupMembers` is
      rejected, FR-011a; `topicJoiningEnabled`, `skillDisplayMode` each settable independently and left
      unchanged when `null`; verified via `StepVerifier`) in
      `src/test/java/net/fabcelhaft/hackathonorganiser/organisersettings/OrganiserSettingsServiceTest.java`
      (extends the existing file)
- [ ] T002 [P] Write failing unit tests for `GroupService.activeMemberCount(UUID groupId)` (counts only
      `active = true` `group_members` rows for the given Group, `0` for an unknown/empty Group) and
      `GroupService.activeMemberParticipantIds(UUID groupId)` (the corresponding participant id list) in
      `src/test/java/net/fabcelhaft/hackathonorganiser/group/GroupServiceTest.java` (extends the existing file)
- [ ] T003 [P] Write failing unit tests for `ComplianceService.evaluate(Group group, List<UUID>
      memberParticipantIds)` (FR-012, FR-012a, FR-014, research.md §1/§5): `COMPLIANT_OVERRIDE` when
      `group.complianceOverride` is set, short-circuiting every other check; otherwise `COMPLIANT` iff
      `memberParticipantIds.size() <= maxGroupMembers` (inclusive — research.md §1) AND, when
      `minGroupMembers` is set, `size() >= minGroupMembers`, AND every configured
      `ComplianceDiversityRequirement` has at least its `minimumDistinctValues` distinct non-blank recorded
      values for its Custom Field across the given members; a Group with at least one member and no optional
      rules configured is `COMPLIANT` when at or below the Maximum; a diversity requirement with too many blank
      member values evaluates as not satisfied — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/compliance/ComplianceServiceTest.java` (new file)
- [ ] T004 [P] Write failing unit test additions for `CustomFieldService.deleteDefinition` (blocked by
      `ComplianceConflictException`-shaped-consistent message when a `compliance_diversity_requirements` row
      still references the id, mirroring the existing Participant-value-reference block; treats a missing
      `compliance_diversity_requirements` table defensively as zero references via the existing
      `BadSqlGrammarException` handling, research.md §8) in
      `src/test/java/net/fabcelhaft/hackathonorganiser/customfield/CustomFieldServiceTest.java` (extends the
      existing file)

### Implementation

- [ ] T005 Add `max_group_members integer NOT NULL DEFAULT 5`, `min_group_members integer`,
      `topic_joining_enabled boolean NOT NULL DEFAULT true`, `skill_display_mode text NOT NULL DEFAULT
      'STILL_NEEDED_ONLY'` columns to `organiser_settings` via `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`, plus
      the two idempotent `DROP CONSTRAINT IF EXISTS` + `ADD CONSTRAINT` `CHECK` pairs
      (`organiser_settings_max_group_members_check: max_group_members >= 1`;
      `organiser_settings_min_le_max_group_members_check: min_group_members IS NULL OR min_group_members <=
      max_group_members`) in `src/main/resources/schema.sql` (data-model.md "Schema additions", research.md §3
      — FR-011c's default-seeding is achieved by the column `DEFAULT` alone, no seed `INSERT` needed)
- [ ] T006 Add `compliance_override boolean NOT NULL DEFAULT false` column to `groups` via
      `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` in `src/main/resources/schema.sql` (data-model.md "Group",
      research.md §4), depends on T005 (same file)
- [ ] T007 Add the `compliance_diversity_requirements` table (`id uuid PRIMARY KEY DEFAULT uuidv7()`,
      `custom_field_definition_id uuid NOT NULL REFERENCES custom_field_definitions (id)`,
      `minimum_distinct_values integer NOT NULL`, `created_at`/`updated_at`), its `CHECK
      (minimum_distinct_values >= 2)` constraint (idempotent `DROP CONSTRAINT IF EXISTS` + `ADD CONSTRAINT`,
      FR-011d), and the unique index `compliance_diversity_requirements_field_key ON
      compliance_diversity_requirements (custom_field_definition_id)` (research.md §3 — at most one
      requirement per field) in `src/main/resources/schema.sql`, depends on T006 (same file)
- [ ] T008 [P] Create the `SkillDisplayMode` enum (`STILL_NEEDED_ONLY`, `ALL_ASSOCIATED`) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/organisersettings/SkillDisplayMode.java`
- [ ] T009 [P] Add `maxGroupMembers` (`int`), `minGroupMembers` (`Integer`, nullable),
      `topicJoiningEnabled` (`boolean`), `skillDisplayMode` (`SkillDisplayMode`) fields and accessors to
      `OrganiserSettings` in
      `src/main/java/net/fabcelhaft/hackathonorganiser/organisersettings/OrganiserSettings.java`, depends on
      T008
- [ ] T010 Extend `OrganiserSettingsService.update(...)` with four new `null`-means-"leave unchanged"
      parameters (`Integer maxGroupMembers`, `Integer minGroupMembers`, `Boolean topicJoiningEnabled`,
      `SkillDisplayMode skillDisplayMode`); validate `maxGroupMembers` (`null` leaves unchanged, otherwise
      `>= 1`, FR-011b) and the min/max relationship (`minGroupMembers == null || minGroupMembers <=
      (effective) maxGroupMembers`, FR-011a) before touching any field, raising
      `OrganiserSettingsConflictException` and applying **no** change on an invalid value, exactly like the
      existing `maxRegistrations` pre-check in
      `src/main/java/net/fabcelhaft/hackathonorganiser/organisersettings/OrganiserSettingsService.java`,
      depends on T009, T005 — makes T001 pass
- [ ] T011 [P] Add a `complianceOverride` (`boolean`) field and accessors to `Group` in
      `src/main/java/net/fabcelhaft/hackathonorganiser/group/Group.java`
- [ ] T012 Add `GroupService.activeMemberCount(UUID groupId)` and
      `GroupService.activeMemberParticipantIds(UUID groupId)` (both plain `DatabaseClient` reads against
      `group_members WHERE group_id = :gid AND active`, the same query shape as the existing private
      `loadMembers`/`isActiveMember` helpers) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/group/GroupService.java`, depends on T006 — makes T002
      pass
- [ ] T013 [P] Create the `ComplianceDiversityRequirement` entity (`id`, `customFieldDefinitionId`,
      `minimumDistinctValues`, `createdAt`, `updatedAt`, `@Table("compliance_diversity_requirements")`) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/compliance/ComplianceDiversityRequirement.java`
- [ ] T014 [P] Create `ComplianceDiversityRequirementRepository extends
      ReactiveCrudRepository<ComplianceDiversityRequirement, UUID>` in
      `src/main/java/net/fabcelhaft/hackathonorganiser/compliance/ComplianceDiversityRequirementRepository.java`
- [ ] T015 [P] Create the `ComplianceStatus` enum (`COMPLIANT`, `NOT_COMPLIANT`, `COMPLIANT_OVERRIDE` — "No
      Group Yet" is a caller-side branch, not a value of this enum, research.md §5) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/compliance/ComplianceStatus.java`
- [ ] T016 [P] Create `ComplianceConflictException` (mirrors `CustomFieldConflictException`'s existing shape)
      in `src/main/java/net/fabcelhaft/hackathonorganiser/compliance/ComplianceConflictException.java`
- [ ] T017 Implement `ComplianceService.evaluate(Group group, List<UUID> memberParticipantIds)`
      (data-model.md "Compliance Status"): reads `OrganiserSettingsService.current()` for
      `maxGroupMembers`/`minGroupMembers` and `ComplianceDiversityRequirementRepository.findAll()` for the
      requirement list; for each requirement, queries `custom_field_values.free_text_value` and
      `custom_field_value_options` (whichever the requirement's Custom Field's `fieldType` uses, mirroring
      `CustomFieldService`'s own type-aware value reading) for the given `memberParticipantIds`, counting
      distinct non-blank values; combines every rule with AND logic per research.md §1's inclusive Maximum
      reading, in
      `src/main/java/net/fabcelhaft/hackathonorganiser/compliance/ComplianceService.java`, depends on T009,
      T013, T014, T015 — makes T003 pass
- [ ] T018 Extend `CustomFieldService.deleteDefinition`'s reference-count guard with a third
      `countReferencing("compliance_diversity_requirements", "custom_field_definition_id", id)` term in the
      same `concatWith(...).reduce(0L, Long::sum)` chain (research.md §8) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/customfield/CustomFieldService.java`, depends on T007 —
      makes T004 pass

**Checkpoint**: Foundation ready — user story implementation can now begin in priority order.

---

## Phase 2: User Story 1 - Propose a Topic with Skills (Priority: P1) 🎯 MVP

**Goal**: Any registered Participant proposes a Topic with a name, description, and zero or more Skills from
the organiser-defined catalog; the Topic's author can later edit its name, description, and Skill selections.

**Independent Test**: Propose a Topic with two Skills, confirm they're saved (via the edit form's pre-fill),
then edit the selection and confirm the change persists.

**Depends on**: Foundational only — extends 002's already-existing `topic_skills` machinery
(`TopicService.create`/`update`, `replaceTopicSkills`, `allSkillIdsExist`) onto the self-service path
(research.md §6); no schema change.

### Tests for User Story 1 ⚠️ write first, confirm they fail

- [ ] T019 [P] [US1] Write failing `WebTestClient` integration test additions: `POST /topics` with a
      `skillIds` field creates the Topic with exactly those Skills attached (FR-001, verified by re-fetching
      `GET /topics/{id}/edit` and confirming the Skill checkboxes are pre-checked); submitting zero
      `skillIds` succeeds with an empty Skill list (FR-001 Acceptance Scenario 3); an unknown skill id is
      rejected (200, re-rendered form, submitted Skill checkboxes preserved); `POST /topics/{id}` replaces the
      Skill set (add and remove both exercised, FR-002) — per
      [contracts/topic-proposal-and-skills.md](contracts/topic-proposal-and-skills.md) — extending
      `src/test/java/net/fabcelhaft/hackathonorganiser/topics/TopicSelfServiceManagementIT.java`
- [ ] T020 [P] [US1] Write failing unit tests for `TopicService.propose(UUID authorUserId, String name, String
      description, List<UUID> skillIds)` and `TopicService.updateAsAuthor(UUID id, UUID requesterUserId,
      String name, String description, List<UUID> skillIds)` (both persist the Skill set via the existing
      `replaceTopicSkills`, reject an unknown skill id with `TopicConflictException`, accept an empty list;
      `updateAsAuthor` retains its existing non-author rejection unchanged) — verified via `StepVerifier` — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/topic/TopicServiceTest.java` (extends the existing file)

### Implementation for User Story 1

- [ ] T021 [US1] Extend `TopicService.propose(...)` to accept and persist a `List<UUID> skillIds` parameter,
      reusing the existing `allSkillIdsExist`/`replaceTopicSkills` private helpers unchanged, in
      `src/main/java/net/fabcelhaft/hackathonorganiser/topic/TopicService.java` — makes half of T020 pass
- [ ] T022 [US1] Extend `TopicService.updateAsAuthor(...)` to accept and persist a `List<UUID> skillIds`
      parameter the same way, in the same file, depends on T021 — makes the rest of T020 pass
- [ ] T023 [US1] Extend `TopicSelfServiceController.create(...)`/`update(...)` to read a repeated `skillIds`
      form field and pass it through to `propose`/`updateAsAuthor`, preserving the submitted selection on a
      re-rendered error form, in
      `src/main/java/net/fabcelhaft/hackathonorganiser/topics/TopicSelfServiceController.java`, depends on
      T021, T022 — makes T019 pass
- [ ] T024 [US1] Extend `TopicSelfServiceController.newForm(...)`/`editForm(...)` to add `allSkills`
      (`TopicService.allSkills()`) and, for edit, the currently-selected `skillIds`
      (`TopicService.findDetail(id).skillIds()`) to the model, in the same file, depends on T023
- [ ] T025 [P] [US1] Extend `src/main/resources/templates/topics/form.html` with a Skill multi-select control
      (mirroring `organiser/topics/form.html`'s already-proven markup: a checkbox per catalog Skill, each with
      a programmatically associated label, FR-023), pre-checked from the model's `skillIds` when editing,
      depends on T024

**Checkpoint**: User Story 1 is fully functional and testable independently —
`mvn verify -Dit.test=TopicSelfServiceManagementIT`.

---

## Phase 3: User Story 2 - Discover Open Topics on the Home Page (Priority: P1)

**Goal**: The Home Page shows up to 10 not-full Topics, fullest first, each row showing Name, participant
count, and the subset of needed Skills the viewer offers.

**Independent Test**: Create several Topics with varying member counts (via 002's existing Organiser-facing
Group creation, or manually via `group_members`), visit the Home Page, confirm exactly the not-full ones
appear, capped at 10, fullest-first.

**Depends on**: Foundational (`activeMemberCount`, `OrganiserSettings.maxGroupMembers`/`skillDisplayMode`).
Not on User Story 3 — member counts are read via 002's existing `GroupService` read paths regardless of how a
Group came to have members.

### Tests for User Story 2 ⚠️ write first, confirm they fail

- [ ] T026 [P] [US2] Write failing unit tests for `TopicDiscoveryService.findOpenTopicsForHomePage(UUID
      viewerParticipantIdOrNull, int limit)` (FR-003–FR-004): at most `limit` rows; excludes Pending Topics and
      any Topic whose active Group's member count is `>= maxGroupMembers` (FR-003b); a Topic with no Group
      counts as `0` (FR-003a); ordered by member count descending; each row's Skills are the display-mode-
      filtered needed-Skill set intersected with the viewer's own `participant_skills` (empty for a viewer with
      no Participant record, or none in common — never an error, FR-004 Acceptance Scenario 5) — verified via
      `StepVerifier` — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/topic/TopicDiscoveryServiceTest.java` (new file)
- [ ] T027 [P] [US2] Write failing `WebTestClient` integration test additions: `GET /` renders the new
      3-column table (Name / participant count / Skills-offered-by-viewer) with correct cap/order/filtering per
      [contracts/home-and-topic-overview.md](contracts/home-and-topic-overview.md) — extending
      `src/test/java/net/fabcelhaft/hackathonorganiser/home/HomeControllerIT.java`

### Implementation for User Story 2

- [ ] T028 [US2] Create `TopicDiscoveryService` with `findOpenTopicsForHomePage(UUID
      viewerParticipantIdOrNull, int limit)`, returning `List<OpenTopicRow(Topic topic, int memberCount,
      List<Skill> viewerOfferedSkills)>` (data-model.md), composing `TopicRepository`, `GroupService`
      (`findActiveGroupForTopic`, `activeMemberCount`), Skill loading against `topic_skills`/
      `participant_skills`, and `OrganiserSettingsService` for `skillDisplayMode`/`maxGroupMembers`, in
      `src/main/java/net/fabcelhaft/hackathonorganiser/topic/TopicDiscoveryService.java`, depends on T012,
      T009 — makes T026 pass
- [ ] T029 [US2] Replace `HomeController`'s call to `TopicService.findVisibleTopicsFor` with
      `TopicDiscoveryService.findOpenTopicsForHomePage(...)` (resolving the viewer's Participant id via the
      existing `ParticipantService.findByUserId` lookup, `null` if none), in
      `src/main/java/net/fabcelhaft/hackathonorganiser/home/HomeController.java`, depends on T028 — makes T027
      pass
- [ ] T030 [P] [US2] Replace the Topics section's `<ul>` markup in
      `src/main/resources/templates/home/index.html` with a `<table>` (Name / Participant count / Skills you
      offer columns; empty Skills cell renders visibly, not omitted, FR-004 Acceptance Scenario 5); the
      "Propose Topic" link is retained, depends on T029

**Checkpoint**: User Story 2 is fully functional and testable independently —
`mvn verify -Dit.test=HomeControllerIT`.

---

## Phase 4: User Story 3 - Join a Topic to Form or Grow a Group (Priority: P1)

**Goal**: A Participant with no active Group joins an open Topic; a Group is created on first join and grown
on later joins, race-safe under the configured Maximum and any Organiser override.

**Independent Test**: A Participant joins a Topic with no Group — a Group is created with them as sole member;
a second Participant joins the same Topic — the Group's member count increases.

**Depends on**: Foundational (`maxGroupMembers`, `Group.complianceOverride`). Builds the Home Page's "Join"
action on top of User Story 2's table.

### Tests for User Story 3 ⚠️ write first, confirm they fail

- [ ] T031 [P] [US3] Write failing unit tests for `GroupService.join(UUID topicId, UUID participantId)`
      (research.md §2, FR-007–FR-013a): no active Group yet → creates one with the Participant as sole member;
      an existing Group under `maxGroupMembers` → adds the Participant, count `+1`; at `maxGroupMembers` with
      no override → rejects with `GroupConflictException`("This Topic is full") and does not write; at
      `maxGroupMembers` **with** `complianceOverride = true` → succeeds; a Participant already in a different
      active Group → rejects reusing `addMember`'s existing guard; an unknown `topicId` → completes empty; two
      concurrent `join(...)` calls for the Topic's last open slot, driven via two subscriptions racing against
      a real Testcontainers Postgres connection (not mocked, since the race is enforced at the database/lock
      level) → exactly one succeeds (Edge Cases) — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/group/GroupServiceTest.java` (extends the existing file;
      the concurrency assertion needs a real database — move it into T033's `WebTestClient` IT, already backed
      by Testcontainers, if it cannot run under the plain Mockito-based unit test setup)
- [ ] T032 [P] [US3] Write failing unit tests for `TopicJoinService.join(UUID topicId, UUID requesterUserId)`
      (data-model.md): rejects with a distinct `TopicJoinConflictException` message when
      `topicJoiningEnabled` is `false` (FR-020b), when the requester has no Participant record or a non-`ACTIVE`
      status (FR-007b), and completes empty (→ 404) for an unknown or non-`APPROVED` Topic; otherwise delegates
      to `GroupService.join` — verified via `StepVerifier` — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/topic/TopicJoinServiceTest.java` (new file)
- [ ] T033 [P] [US3] Write failing `WebTestClient` integration tests: `POST /topics/{id}/join` succeeds
      immediately with no confirmation step and a success flash (FR-007a); creates a Group on first join,
      grows it on a second; rejects with a "full" message once at Maximum; rejects a requester already in a
      different Group; rejects a Not-Participated/Revoked/no-record requester; 404 for an unknown or Pending
      Topic id; the Topic's own author can join their own Topic like anyone else (Edge Cases) — per
      [contracts/join-action.md](contracts/join-action.md) — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/topics/TopicJoinManagementIT.java` (new file)

### Implementation for User Story 3

- [ ] T034 [US3] Implement `GroupService.join(UUID topicId, UUID participantId)`: inside one
      `TransactionalOperator`-wrapped transaction, execute `SELECT pg_advisory_xact_lock(hashtext('topic-join:'
      || topicId))`, re-read the Topic's active Group (if any) and its `activeMemberCount`, re-read
      `OrganiserSettings.maxGroupMembers` and the Group's `complianceOverride`, reject with
      `GroupConflictException` if the join would reach/exceed the Maximum and no override is set, then either
      create a new Group (reusing `create`'s existing single-active-Group-per-Topic path) or `addMember` onto
      the existing one (reusing its existing one-active-Group-per-Participant guard) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/group/GroupService.java`, depends on T012, T011, T009 —
      makes T031 pass
- [ ] T035 [P] [US3] Create `TopicJoinConflictException` (mirrors `TopicConflictException`'s existing shape) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/topic/TopicJoinConflictException.java`
- [ ] T036 [US3] Implement `TopicJoinService.join(UUID topicId, UUID requesterUserId)`: re-reads
      `OrganiserSettingsService.current().isTopicJoiningEnabled()` (FR-020c, never cached), the requester's
      Participant record/status via `ParticipantService.findByUserId`, and the Topic's `approvalStatus` via
      `TopicService.findById`, in that order, each rejection a distinct `TopicJoinConflictException` message,
      before delegating to `GroupService.join` in
      `src/main/java/net/fabcelhaft/hackathonorganiser/topic/TopicJoinService.java`, depends on T034, T035 —
      makes T032 pass
- [ ] T037 [US3] Implement `TopicJoinController` (`POST /topics/{id}/join`) per
      [contracts/join-action.md](contracts/join-action.md): redirects home with a success flash, or catches
      `TopicJoinConflictException`/an unknown-Topic empty result and redirects home with an explanatory flash
      (200/303 per contract) — in
      `src/main/java/net/fabcelhaft/hackathonorganiser/topics/TopicJoinController.java`, depends on T036 —
      makes T033 pass
- [ ] T038 [US3] Extend `HomeController`/`home/index.html` (from T029/T030): render a "Join" action per row
      when the viewer is an Active Participant with no active Group, Topic joining is enabled, and the row's
      Topic is not full; the flash confirmation from T037 surfaces through the existing `#flash-message` live
      region (FR-007a, FR-024) — in
      `src/main/java/net/fabcelhaft/hackathonorganiser/home/HomeController.java` and
      `src/main/resources/templates/home/index.html`, depends on T029, T037

**Checkpoint**: User Story 3 is fully functional and testable independently —
`mvn verify -Dit.test=TopicJoinManagementIT`.

---

## Phase 5: User Story 4 - Organiser Controls Topic-Joining Availability (Priority: P2)

**Goal**: An Organiser independently enables/disables the self-service Join action instance-wide; only Active
Participants are ever eligible regardless of this setting.

**Independent Test**: Disable Topic joining, confirm no Participant sees/can use "Join" on any Topic; re-enable
and confirm it reappears. Separately, confirm a Revoked/Not-Participated Participant never sees "Join"
regardless of the setting.

**Depends on**: User Story 3 (the Join action this gates) — `TopicJoinService`/`HomeController` already read
`topicJoiningEnabled` (T036, T038); this story adds the Organiser-facing control.

### Tests for User Story 4 ⚠️ write first, confirm they fail

- [ ] T039 [P] [US4] Write failing `WebTestClient` integration test additions: `organiser/settings/form.html`
      shows/saves a "Topic joining enabled" checkbox, defaulting to on (FR-020d); non-Organiser access denied
      — extending `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/settings/SettingsManagementIT.java`
- [ ] T040 [P] [US4] Write failing `WebTestClient` integration test additions: with the setting disabled, no
      "Join" action renders on `GET /` for any Participant (FR-020b); a direct `POST /topics/{id}/join` is
      still rejected server-side even if issued as if the page had rendered the button before the setting
      changed (FR-020c, Edge Cases); re-enabling restores the action on the very next view (SC-009); a
      Revoked/Not-Participated Participant sees no "Join" action regardless of this setting's value (Story 4
      Acceptance Scenario 3) — extending
      `src/test/java/net/fabcelhaft/hackathonorganiser/topics/TopicJoinManagementIT.java`

### Implementation for User Story 4

- [ ] T041 [US4] Extend `OrganiserSettingsController` and `src/main/resources/templates/organiser/settings/
      form.html` with a "Topic joining enabled" checkbox wired to `OrganiserSettingsService.update(...)`'s
      `topicJoiningEnabled` parameter (T010), in
      `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/settings/OrganiserSettingsController.java` —
      makes T039 pass; T040 exercises the already-implemented T036/T038 enforcement end-to-end

**Checkpoint**: User Story 4 is fully functional and testable independently —
`mvn verify -Dit.test=SettingsManagementIT,TopicJoinManagementIT`.

---

## Phase 6: User Story 5 - Browse the Full Topic Overview (Priority: P2)

**Goal**: A "Topic Overview" nav item shows every visible Topic with Name, Author, participant count, needed
Skills, and Compliance status.

**Independent Test**: Create several Topics (including a full one), confirm all appear in the Topic Overview
with correct author, count, Skills, and compliance status.

**Depends on**: User Stories 1–3 for realistic data; Foundational's `ComplianceService.evaluate` (T017) for the
Compliance column.

### Tests for User Story 5 ⚠️ write first, confirm they fail

- [ ] T042 [P] [US5] Write failing unit tests for `TopicDiscoveryService.findTopicOverview(UUID viewerUserId,
      boolean viewerIsOrganiser)` (FR-005–FR-006, FR-014): every Topic visible to the viewer (reusing
      `TopicService`'s existing Pending-visibility rule — a Pending Topic included only for its author or an
      Organiser); each row's author display name, member count (`0` if no Group), needed Skills after
      `skillDisplayMode` (**not** intersected with the viewer, unlike the Home Page); an empty `Optional`
      Compliance status for a Topic with no Group ("No Group Yet"); otherwise `ComplianceService.evaluate(...)`
      result — verified via `StepVerifier` — extending
      `src/test/java/net/fabcelhaft/hackathonorganiser/topic/TopicDiscoveryServiceTest.java`
- [ ] T043 [P] [US5] Write failing `WebTestClient` integration tests: `GET /topics/overview` lists every
      visible Topic with correct columns; a Pending Topic appears only for its author/an Organiser; each
      Compliance status renders as one of `Compliant`/`Not Compliant`/`Compliant (Organiser Override)`/`No
      Group Yet`, conveyed by text/icon not color alone (FR-025); the nav item is present for every
      authenticated user unconditionally — per
      [contracts/home-and-topic-overview.md](contracts/home-and-topic-overview.md) — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/topics/TopicOverviewManagementIT.java` (new file)

### Implementation for User Story 5

- [ ] T044 [US5] Implement `TopicDiscoveryService.findTopicOverview(UUID viewerUserId, boolean
      viewerIsOrganiser)`, returning `List<OverviewRow(Topic topic, String authorDisplayName, int memberCount,
      List<Skill> neededSkills, Optional<ComplianceStatus> complianceStatus)>` (data-model.md), reusing
      `TopicService`'s Pending-visibility filter, `GroupService.activeMemberParticipantIds` +
      `ComplianceService.evaluate` for the Compliance column, in
      `src/main/java/net/fabcelhaft/hackathonorganiser/topic/TopicDiscoveryService.java`, depends on T028,
      T017 — makes T042 pass
- [ ] T045 [US5] Implement `TopicOverviewController` (`GET /topics/overview`) per
      [contracts/home-and-topic-overview.md](contracts/home-and-topic-overview.md) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/topics/TopicOverviewController.java`, depends on T044 —
      makes T043 pass
- [ ] T046 [P] [US5] Create `src/main/resources/templates/topics/overview.html` (table: Name / Author /
      Participant count / Needed Skills / Compliance status badge — text + icon, never color alone, FR-025),
      depends on T045
- [ ] T047 [P] [US5] Add a static, unconditional "Topic Overview" nav item to
      `src/main/resources/templates/fragments/layout.html` (every authenticated user — no access-policy class
      needed, unlike the configurable Participants directory)

**Checkpoint**: User Story 5 is fully functional and testable independently —
`mvn verify -Dit.test=TopicOverviewManagementIT`.

---

## Phase 7: User Story 6 - Organiser Configures Compliance Rules (Priority: P2)

**Goal**: An Organiser sets the Maximum/Minimum Group Members and Custom Field diversity requirements that
define Compliance instance-wide, combined with AND logic.

**Independent Test**: Set minimum 2 / maximum 5, add a "Country" diversity requirement (minimum 2 distinct
values), save; confirm a 3-member single-country Group evaluates Not Compliant and a two-country one evaluates
Compliant.

**Depends on**: Foundational (`ComplianceService.evaluate`, T017, already implements the read side; this story
adds the write/CRUD surface for `maxGroupMembers`/`minGroupMembers`/diversity requirements).

### Tests for User Story 6 ⚠️ write first, confirm they fail

- [ ] T048 [P] [US6] Write failing unit tests for `ComplianceService.addRequirement(UUID
      customFieldDefinitionId, int minimumDistinctValues)` (rejects `< 2` with `ComplianceConflictException`,
      FR-011d; rejects an unknown Custom Field id; rejects a Custom Field id already configured, research.md
      §3) and `ComplianceService.removeRequirement(UUID id)` (always succeeds for an existing row) — verified
      via `StepVerifier` — extending
      `src/test/java/net/fabcelhaft/hackathonorganiser/compliance/ComplianceServiceTest.java`
- [ ] T049 [P] [US6] Write failing `WebTestClient` integration tests: `GET /organiser/compliance` shows the
      seeded default Maximum (`5`), no Minimum, empty requirement list on a fresh instance (FR-011c); `POST
      /organiser/compliance` accepts a valid max/min pair and rejects a blank Maximum (FR-011b) and a Minimum
      exceeding Maximum (FR-011a), each with a field-associated error; `POST
      /organiser/compliance/diversity-requirements` adds a valid requirement and rejects a minimum below 2
      (FR-011d); `POST /organiser/compliance/diversity-requirements/{id}/delete` removes one; every route
      denies a non-Organiser (SC-007) — per
      [contracts/compliance-settings-and-override.md](contracts/compliance-settings-and-override.md) — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/compliance/ComplianceManagementIT.java` (new
      file)
- [ ] T050 [P] [US6] Write failing `WebTestClient` integration test addition: an Organiser cannot delete a
      Custom Field Definition currently referenced by a diversity requirement (FR-020, Edge Cases; proves T018
      end-to-end) — extending
      `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/customfield/CustomFieldManagementIT.java`

### Implementation for User Story 6

- [ ] T051 [US6] Implement `ComplianceService.addRequirement(UUID customFieldDefinitionId, int
      minimumDistinctValues)` and `removeRequirement(UUID id)` in
      `src/main/java/net/fabcelhaft/hackathonorganiser/compliance/ComplianceService.java`, depends on T014,
      T016 — makes T048 pass
- [ ] T052 [US6] Implement `ComplianceController` (`GET`/`POST /organiser/compliance`, `POST
      /organiser/compliance/diversity-requirements`, `POST
      /organiser/compliance/diversity-requirements/{id}/delete`) per
      [contracts/compliance-settings-and-override.md](contracts/compliance-settings-and-override.md), reusing
      `OrganiserSettingsService.update(...)` (T010) for max/min and `ComplianceService` (T051) for the
      requirement list, in
      `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/compliance/ComplianceController.java`,
      depends on T010, T051 — makes T049 pass
- [ ] T053 [P] [US6] Create `src/main/resources/templates/organiser/compliance/form.html` (Maximum/Minimum
      fields; the current diversity-requirement list with a remove action per row; an add-requirement form —
      Custom Field dropdown restricted to fields not already configured, minimum-distinct-value input
      defaulting to 2; every control has a programmatically associated label, FR-023), depends on T052
- [ ] T054 [US6] Add a "Compliance" nav link to the Organiser area in
      `src/main/resources/templates/organiser/fragments/layout.html`, depends on T052
- [ ] T055 [US6] Extend `organiser/custom-fields/list.html`'s existing delete-rejection message rendering (no
      route change) to display the compliance-requirement-aware wording T018 now produces — in
      `src/main/resources/templates/organiser/custom-fields/list.html`, depends on T018 — makes T050 pass

**Checkpoint**: User Story 6 is fully functional and testable independently —
`mvn verify -Dit.test=ComplianceManagementIT`.

---

## Phase 8: User Story 7 - Organiser Overrides a Group's Compliance (Priority: P3)

**Goal**: An Organiser marks a specific Group as compliant regardless of automatic evaluation, and this also
lets it keep accepting joins beyond the configured Maximum.

**Independent Test**: Mark a below-Minimum Group as compliant, confirm its status changes; have a Participant
join a Group at Maximum after it's been marked, confirm the join succeeds.

**Depends on**: User Story 3 (`GroupService.join` already reads `complianceOverride`, T034) and User Story 6
(a configured ruleset for the override to meaningfully suspend).

### Tests for User Story 7 ⚠️ write first, confirm they fail

- [ ] T056 [P] [US7] Write failing unit tests for `GroupService.setComplianceOverride(UUID groupId, boolean
      override)` (sets/clears the flag; completes empty/404-mappable for an unknown `groupId`) — extending
      `src/test/java/net/fabcelhaft/hackathonorganiser/group/GroupServiceTest.java`
- [ ] T057 [P] [US7] Write failing `WebTestClient` integration tests: `POST
      /organiser/groups/{id}/compliance-override` sets the override, the Group detail view immediately shows
      `Compliant (Organiser Override)` (FR-015), and a subsequent join beyond Maximum succeeds (SC-006);
      removing the override reverts both the badge and Maximum enforcement on the very next read (FR-016,
      Acceptance Scenario 3); 404 for an unknown Group id; non-Organiser denied — per
      [contracts/compliance-settings-and-override.md](contracts/compliance-settings-and-override.md) — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/group/GroupManagementIT.java` (extends the
      existing file)

### Implementation for User Story 7

- [ ] T058 [US7] Implement `GroupService.setComplianceOverride(UUID groupId, boolean override)` in
      `src/main/java/net/fabcelhaft/hackathonorganiser/group/GroupService.java`, depends on T011 — makes T056
      pass
- [ ] T059 [US7] Extend `GroupController` with `POST /organiser/groups/{id}/compliance-override` per
      [contracts/compliance-settings-and-override.md](contracts/compliance-settings-and-override.md), computing
      the badge value via `ComplianceService.evaluate` + `GroupService.activeMemberParticipantIds` for the
      detail view's model, in
      `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/group/GroupController.java`, depends on T058,
      T017 — makes T057 pass
- [ ] T060 [P] [US7] Extend `src/main/resources/templates/organiser/groups/detail.html` with a Compliance
      status badge (text + icon, FR-025) and a set/remove-override control, depends on T059

**Checkpoint**: User Story 7 is fully functional and testable independently —
`mvn verify -Dit.test=GroupManagementIT`.

---

## Phase 9: User Story 8 - Organiser Controls the Skill-Matching Display Mode (Priority: P3)

**Goal**: A single instance-wide setting controls whether the Skills columns (Home Page, Topic Overview) show
only still-needed Skills or every associated Skill.

**Independent Test**: Create a Topic needing two Skills, one already covered by an existing Group member;
toggle the setting; confirm the displayed Skill list changes accordingly.

**Depends on**: User Stories 2 and 5 (the two Skills columns this setting affects) — `TopicDiscoveryService`
(T028, T044) already reads `skillDisplayMode`; this story adds the Organiser-facing control.

### Tests for User Story 8 ⚠️ write first, confirm they fail

- [ ] T061 [P] [US8] Write failing unit test additions for `TopicDiscoveryService`: with `skillDisplayMode =
      STILL_NEEDED_ONLY`, a Skill already held by a current Group member is excluded from both
      `findOpenTopicsForHomePage` and `findTopicOverview`'s Skills column; with `ALL_ASSOCIATED`, every needed
      Skill is shown regardless of coverage; a Topic with no Group yet treats every needed Skill as still
      needed (Edge Cases) — extending
      `src/test/java/net/fabcelhaft/hackathonorganiser/topic/TopicDiscoveryServiceTest.java`
- [ ] T062 [P] [US8] Write failing `WebTestClient` integration test additions: `organiser/settings/form.html`
      shows/saves a "Skill Display Mode" radio group; changing it changes the Skills column on both `GET /` and
      `GET /topics/overview` on the very next view, no deployment required (FR-018) — extending
      `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/settings/SettingsManagementIT.java`,
      `src/test/java/net/fabcelhaft/hackathonorganiser/home/HomeControllerIT.java`, and
      `src/test/java/net/fabcelhaft/hackathonorganiser/topics/TopicOverviewManagementIT.java`

### Implementation for User Story 8

- [ ] T063 [US8] Verify/close any gap in `TopicDiscoveryService`'s existing `skillDisplayMode` handling (T028,
      T044) so both read models correctly compute the "covered by any current active member" set from
      `participant_skills` before applying the mode — makes T061 pass
- [ ] T064 [US8] Extend `OrganiserSettingsController` and `organiser/settings/form.html` with a "Skill Display
      Mode" two-option radio group wired to `OrganiserSettingsService.update(...)`'s `skillDisplayMode`
      parameter (T010), in
      `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/settings/OrganiserSettingsController.java` —
      makes T062 pass

**Checkpoint**: All eight user stories are independently functional.

---

## Phase 10: Polish & Cross-Cutting Concerns

**Purpose**: The automated accessibility gate (SC-008), which only makes sense once every screen it scans
exists, plus a final full-suite/manual verification pass.

- [ ] T065 [P] Extend `a11y.HomepageAccessibilityIT` to scan the Home Page's new 3-column topic table
      including a rendered "Join" action, and the Topic proposal/edit form's Skill picker (`GET /topics/new`),
      reusing 003's already-justified Playwright + axe-core tooling (research.md §9); asserts zero
      `critical`/`serious` WCAG 2.1 AA violations on each (SC-008) — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/a11y/HomepageAccessibilityIT.java`
- [ ] T066 [P] Write `a11y.TopicOverviewAccessibilityIT` (`GET /topics/overview`, including its Compliance
      status badges): asserts zero `critical`/`serious` WCAG 2.1 AA violations (SC-008) — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/a11y/TopicOverviewAccessibilityIT.java` (new file)
- [ ] T067 [P] Write `a11y.ComplianceSettingsAccessibilityIT`: scans `GET /organiser/compliance` (including
      the diversity-requirement add/remove controls) and the Group detail view's compliance-override control;
      asserts zero `critical`/`serious` WCAG 2.1 AA violations (SC-008) — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/a11y/ComplianceSettingsAccessibilityIT.java` (new file)
- [ ] T068 Review T065–T067's results and adjust `src/main/resources/static/css/app.css` as needed so the
      Compliance status badge's text/icon treatment and the diversity-requirement row layout meet WCAG 2.1 AA
      contrast minimums (4.5:1 normal text, 3:1 large text/UI component boundaries) in both light and dark
      presentation (FR-027)
- [ ] T069 Run `mvn verify` (full unit + `*ManagementIT`/`*IT` + `a11y.*IT` suite, including the join-race
      concurrent-request test) and perform the [quickstart.md](quickstart.md) manual visual smoke test
      end-to-end across all eight user stories (Constitution Development Workflow #3)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Foundational (Phase 1)**: No dependencies — start immediately. BLOCKS all user stories (every story reads
  the `OrganiserSettings`/`Group` extensions or `ComplianceService` introduced here).
- **User Stories (Phase 2–9)**: All depend on Foundational. Priority order per spec.md is US1 → US2 → US3 →
  US4 → US5 → US6 → US7 → US8; US2 needs only US1's data shape (not its code); US3 extends US2's Home Page
  table with a Join action; US4 gates US3's already-implemented `topicJoiningEnabled` check with an Organiser
  UI; US5 reuses US1–3's data and Foundational's `ComplianceService`; US6 adds the CRUD surface on top of
  Foundational's already-implemented evaluation logic; US7 exposes the override Foundational/US3 already read;
  US8 adds the Organiser UI on top of US2/US5's already-implemented display-mode reads.
- **Polish (Phase 10)**: Depends on all eight user stories being complete (T065–T067 need every in-scope
  screen to exist).

### Within Each User Story

- Tests written and confirmed failing before implementation (Constitution Principle V, NON-NEGOTIABLE).
- Entities/enums/exceptions before services; services before controllers; controllers before templates that
  call them.
- Each story's Checkpoint is reachable via its own `mvn verify -Dit.test=...` run before starting the next.

### Parallel Opportunities

- Foundational's T001–T004 (tests) and T008–T009, T011, T013–T016 (different files) can each run in parallel
  within their own group.
- Once Foundational is done, US1 should go first (US2's Skills column and US5/US6's data both read what it
  produces), but US2's `TopicDiscoveryService` scaffolding has no code dependency on US1's controller/template
  changes and could be staffed in parallel once Foundational lands, if team capacity allows.
- Within any story, tasks marked `[P]` touch different files and have no incomplete-task dependency.

---

## Parallel Example: User Story 3

```bash
# Tests together:
Task: "Write failing unit tests for GroupService.join(...) in group/GroupServiceTest.java"
Task: "Write failing unit tests for TopicJoinService.join(...) in topic/TopicJoinServiceTest.java"
Task: "Write failing WebTestClient integration tests in topics/TopicJoinManagementIT.java"

# Once GroupService.join exists, the exception type and the orchestrating service are independent files:
Task: "Create TopicJoinConflictException in topic/TopicJoinConflictException.java"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1 (Foundational).
2. Complete Phase 2 (User Story 1).
3. **STOP and VALIDATE**: `mvn verify -Dit.test=TopicSelfServiceManagementIT` — a Participant can propose a
   Topic with Skills and later edit that selection.
4. Demo if ready — this alone delivers SC-001's core "propose with Skills" promise, the foundation everything
   else in this feature reads.

### Incremental Delivery

1. Foundational → ready.
2. US1 → validate independently → demo (Topics carry Skills).
3. US2 → validate independently → demo (Home Page's fullness-sorted table).
4. US3 → validate independently → demo (self-service Join, race-safe capacity).
5. US4 → validate independently → demo (Organiser gates joining instance-wide).
6. US5 → validate independently → demo (full Topic Overview with Compliance column).
7. US6 → validate independently → demo (Organiser configures the Compliance Ruleset).
8. US7 → validate independently → demo (Organiser override).
9. US8 → validate independently → demo (Skill Display Mode) — full feature complete.
10. Polish → automated accessibility gate, full-suite + quickstart confirmation.

### Parallel Team Strategy

1. Team completes Foundational together.
2. Developer A takes US1 → US2 → US3 in sequence (each reads the previous one's data shape); Developer B
   starts US6's `ComplianceService` CRUD once Foundational lands (only a soft dependency on US1–3 via its own
   Independent Test's scenario), then takes US5 → US7 → US8 in sequence; Developer C takes US4 once US3 lands.
3. Polish once all eight stories are merged.

---

## Notes

- `[P]` tasks touch different files and have no incomplete-task dependency.
- `[Story]` label maps each task to its user story for traceability back to spec.md.
- One new table is introduced (`compliance_diversity_requirements`, data-model.md) — every other extension
  lands on `organiser_settings` or `groups`; `topics`/`topic_skills`/`group_members` are unchanged (research.md
  §3, §6).
- The Join race (T031, T034) is closed with a Postgres advisory lock keyed on the Topic id inside a
  `TransactionalOperator` transaction, not a new dependency (research.md §2) — verify this with a real
  concurrent-request test against Testcontainers Postgres, not two mocked calls, since the race only exists at
  the database level, exactly like 004's registration-capacity race test.
- `ComplianceService.evaluate` (T017) is the single source of truth for Compliance status, called by both the
  Topic Overview (US5) and the Group detail view (US7) so the two can never disagree (research.md §5).
- Verify each story's tests fail before implementing it; commit after each task or logical group.
- Stop at any checkpoint to validate a story independently before moving on.
