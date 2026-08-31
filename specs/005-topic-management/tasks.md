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

- [X] T001 [P] Write failing unit tests for `OrganiserSettingsService.update(...)`'s four new parameters
      (`maxGroupMembers`: rejects `null`→no-op-preserving vs. `< 1` raising `OrganiserSettingsConflictException`
      with no field changed, FR-011b; `minGroupMembers`: `null` clears it, a value `> maxGroupMembers` is
      rejected, FR-011a; `topicJoiningEnabled`, `skillDisplayMode` each settable independently and left
      unchanged when `null`; verified via `StepVerifier`) in
      `src/test/java/net/fabcelhaft/hackathonorganiser/organisersettings/OrganiserSettingsServiceTest.java`
      (extends the existing file)
- [X] T002 [P] Write failing unit tests for `GroupService.activeMemberCount(UUID groupId)` (counts only
      `active = true` `group_members` rows for the given Group, `0` for an unknown/empty Group) and
      `GroupService.activeMemberParticipantIds(UUID groupId)` (the corresponding participant id list) in
      `src/test/java/net/fabcelhaft/hackathonorganiser/group/GroupServiceTest.java` (extends the existing file)
- [X] T003 [P] Write failing unit tests for `ComplianceService.evaluate(Group group, List<UUID>
      memberParticipantIds)` (FR-012, FR-012a, FR-014, research.md §1/§5): `COMPLIANT_OVERRIDE` when
      `group.complianceOverride` is set, short-circuiting every other check; otherwise `COMPLIANT` iff
      `memberParticipantIds.size() <= maxGroupMembers` (inclusive — research.md §1) AND, when
      `minGroupMembers` is set, `size() >= minGroupMembers`, AND every configured
      `ComplianceDiversityRequirement` has at least its `minimumDistinctValues` distinct non-blank recorded
      values for its Custom Field across the given members; a Group with at least one member and no optional
      rules configured is `COMPLIANT` when at or below the Maximum; a diversity requirement with too many blank
      member values evaluates as not satisfied — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/compliance/ComplianceServiceTest.java` (new file)
- [X] T004 [P] Write failing unit test additions for `CustomFieldService.deleteDefinition` (blocked by
      `ComplianceConflictException`-shaped-consistent message when a `compliance_diversity_requirements` row
      still references the id, mirroring the existing Participant-value-reference block; treats a missing
      `compliance_diversity_requirements` table defensively as zero references via the existing
      `BadSqlGrammarException` handling, research.md §8) in
      `src/test/java/net/fabcelhaft/hackathonorganiser/customfield/CustomFieldServiceTest.java` (extends the
      existing file)

### Implementation

- [X] T005 Add `max_group_members integer NOT NULL DEFAULT 5`, `min_group_members integer`,
      `topic_joining_enabled boolean NOT NULL DEFAULT true`, `skill_display_mode text NOT NULL DEFAULT
      'STILL_NEEDED_ONLY'` columns to `organiser_settings` via `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`, plus
      the two idempotent `DROP CONSTRAINT IF EXISTS` + `ADD CONSTRAINT` `CHECK` pairs
      (`organiser_settings_max_group_members_check: max_group_members >= 1`;
      `organiser_settings_min_le_max_group_members_check: min_group_members IS NULL OR min_group_members <=
      max_group_members`) in `src/main/resources/schema.sql` (data-model.md "Schema additions", research.md §3
      — FR-011c's default-seeding is achieved by the column `DEFAULT` alone, no seed `INSERT` needed)
- [X] T006 Add `compliance_override boolean NOT NULL DEFAULT false` column to `groups` via
      `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` in `src/main/resources/schema.sql` (data-model.md "Group",
      research.md §4), depends on T005 (same file)
- [X] T007 Add the `compliance_diversity_requirements` table (`id uuid PRIMARY KEY DEFAULT uuidv7()`,
      `custom_field_definition_id uuid NOT NULL REFERENCES custom_field_definitions (id)`,
      `minimum_distinct_values integer NOT NULL`, `created_at`/`updated_at`), its `CHECK
      (minimum_distinct_values >= 2)` constraint (idempotent `DROP CONSTRAINT IF EXISTS` + `ADD CONSTRAINT`,
      FR-011d), and the unique index `compliance_diversity_requirements_field_key ON
      compliance_diversity_requirements (custom_field_definition_id)` (research.md §3 — at most one
      requirement per field) in `src/main/resources/schema.sql`, depends on T006 (same file)
- [X] T008 [P] Create the `SkillDisplayMode` enum (`STILL_NEEDED_ONLY`, `ALL_ASSOCIATED`) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/organisersettings/SkillDisplayMode.java`
- [X] T009 [P] Add `maxGroupMembers` (`int`), `minGroupMembers` (`Integer`, nullable),
      `topicJoiningEnabled` (`boolean`), `skillDisplayMode` (`SkillDisplayMode`) fields and accessors to
      `OrganiserSettings` in
      `src/main/java/net/fabcelhaft/hackathonorganiser/organisersettings/OrganiserSettings.java`, depends on
      T008
- [X] T010 Extend `OrganiserSettingsService.update(...)` with four new `null`-means-"leave unchanged"
      parameters (`Integer maxGroupMembers`, `Integer minGroupMembers`, `Boolean topicJoiningEnabled`,
      `SkillDisplayMode skillDisplayMode`); validate `maxGroupMembers` (`null` leaves unchanged, otherwise
      `>= 1`, FR-011b) and the min/max relationship (`minGroupMembers == null || minGroupMembers <=
      (effective) maxGroupMembers`, FR-011a) before touching any field, raising
      `OrganiserSettingsConflictException` and applying **no** change on an invalid value, exactly like the
      existing `maxRegistrations` pre-check in
      `src/main/java/net/fabcelhaft/hackathonorganiser/organisersettings/OrganiserSettingsService.java`,
      depends on T009, T005 — makes T001 pass
- [X] T011 [P] Add a `complianceOverride` (`boolean`) field and accessors to `Group` in
      `src/main/java/net/fabcelhaft/hackathonorganiser/group/Group.java`
- [X] T012 Add `GroupService.activeMemberCount(UUID groupId)` and
      `GroupService.activeMemberParticipantIds(UUID groupId)` (both plain `DatabaseClient` reads against
      `group_members WHERE group_id = :gid AND active`, the same query shape as the existing private
      `loadMembers`/`isActiveMember` helpers) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/group/GroupService.java`, depends on T006 — makes T002
      pass
- [X] T013 [P] Create the `ComplianceDiversityRequirement` entity (`id`, `customFieldDefinitionId`,
      `minimumDistinctValues`, `createdAt`, `updatedAt`, `@Table("compliance_diversity_requirements")`) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/compliance/ComplianceDiversityRequirement.java`
- [X] T014 [P] Create `ComplianceDiversityRequirementRepository extends
      ReactiveCrudRepository<ComplianceDiversityRequirement, UUID>` in
      `src/main/java/net/fabcelhaft/hackathonorganiser/compliance/ComplianceDiversityRequirementRepository.java`
- [X] T015 [P] Create the `ComplianceStatus` enum (`COMPLIANT`, `NOT_COMPLIANT`, `COMPLIANT_OVERRIDE` — "No
      Group Yet" is a caller-side branch, not a value of this enum, research.md §5) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/compliance/ComplianceStatus.java`
- [X] T016 [P] Create `ComplianceConflictException` (mirrors `CustomFieldConflictException`'s existing shape)
      in `src/main/java/net/fabcelhaft/hackathonorganiser/compliance/ComplianceConflictException.java`
- [X] T017 Implement `ComplianceService.evaluate(Group group, List<UUID> memberParticipantIds)`
      (data-model.md "Compliance Status"): reads `OrganiserSettingsService.current()` for
      `maxGroupMembers`/`minGroupMembers` and `ComplianceDiversityRequirementRepository.findAll()` for the
      requirement list; for each requirement, queries `custom_field_values.free_text_value` and
      `custom_field_value_options` (whichever the requirement's Custom Field's `fieldType` uses, mirroring
      `CustomFieldService`'s own type-aware value reading) for the given `memberParticipantIds`, counting
      distinct non-blank values; combines every rule with AND logic per research.md §1's inclusive Maximum
      reading, in
      `src/main/java/net/fabcelhaft/hackathonorganiser/compliance/ComplianceService.java`, depends on T009,
      T013, T014, T015 — makes T003 pass
- [X] T018 Extend `CustomFieldService.deleteDefinition`'s reference-count guard with a third
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

- [X] T019 [P] [US1] Write failing `WebTestClient` integration test additions: `POST /topics` with a
      `skillIds` field creates the Topic with exactly those Skills attached (FR-001, verified by re-fetching
      `GET /topics/{id}/edit` and confirming the Skill checkboxes are pre-checked); submitting zero
      `skillIds` succeeds with an empty Skill list (FR-001 Acceptance Scenario 3); an unknown skill id is
      rejected (200, re-rendered form, submitted Skill checkboxes preserved); `POST /topics/{id}` replaces the
      Skill set (add and remove both exercised, FR-002) — per
      [contracts/topic-proposal-and-skills.md](contracts/topic-proposal-and-skills.md) — extending
      `src/test/java/net/fabcelhaft/hackathonorganiser/topics/TopicSelfServiceManagementIT.java`
- [X] T020 [P] [US1] Write failing unit tests for `TopicService.propose(UUID authorUserId, String name, String
      description, List<UUID> skillIds)` and `TopicService.updateAsAuthor(UUID id, UUID requesterUserId,
      String name, String description, List<UUID> skillIds)` (both persist the Skill set via the existing
      `replaceTopicSkills`, reject an unknown skill id with `TopicConflictException`, accept an empty list;
      `updateAsAuthor` retains its existing non-author rejection unchanged) — verified via `StepVerifier` — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/topic/TopicServiceTest.java` (extends the existing file)

### Implementation for User Story 1

- [X] T021 [US1] Extend `TopicService.propose(...)` to accept and persist a `List<UUID> skillIds` parameter,
      reusing the existing `allSkillIdsExist`/`replaceTopicSkills` private helpers unchanged, in
      `src/main/java/net/fabcelhaft/hackathonorganiser/topic/TopicService.java` — makes half of T020 pass
- [X] T022 [US1] Extend `TopicService.updateAsAuthor(...)` to accept and persist a `List<UUID> skillIds`
      parameter the same way, in the same file, depends on T021 — makes the rest of T020 pass
- [X] T023 [US1] Extend `TopicSelfServiceController.create(...)`/`update(...)` to read a repeated `skillIds`
      form field and pass it through to `propose`/`updateAsAuthor`, preserving the submitted selection on a
      re-rendered error form, in
      `src/main/java/net/fabcelhaft/hackathonorganiser/topics/TopicSelfServiceController.java`, depends on
      T021, T022 — makes T019 pass
- [X] T024 [US1] Extend `TopicSelfServiceController.newForm(...)`/`editForm(...)` to add `allSkills`
      (`TopicService.allSkills()`) and, for edit, the currently-selected `skillIds`
      (`TopicService.findDetail(id).skillIds()`) to the model, in the same file, depends on T023
- [X] T025 [P] [US1] Extend `src/main/resources/templates/topics/form.html` with a Skill multi-select control
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

- [X] T026 [P] [US2] Write failing unit tests for `TopicDiscoveryService.findOpenTopicsForHomePage(UUID
      viewerParticipantIdOrNull, int limit)` (FR-003–FR-004): at most `limit` rows; excludes Pending Topics and
      any Topic whose active Group's member count is `>= maxGroupMembers` (FR-003b); a Topic with no Group
      counts as `0` (FR-003a); ordered by member count descending; each row's Skills are the display-mode-
      filtered needed-Skill set intersected with the viewer's own `participant_skills` (empty for a viewer with
      no Participant record, or none in common — never an error, FR-004 Acceptance Scenario 5) — verified via
      `StepVerifier` — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/topic/TopicDiscoveryServiceTest.java` (new file)
- [X] T027 [P] [US2] Write failing `WebTestClient` integration test additions: `GET /` renders the new
      3-column table (Name / participant count / Skills-offered-by-viewer) with correct cap/order/filtering per
      [contracts/home-and-topic-overview.md](contracts/home-and-topic-overview.md) — extending
      `src/test/java/net/fabcelhaft/hackathonorganiser/home/HomeControllerIT.java`

### Implementation for User Story 2

- [X] T028 [US2] Create `TopicDiscoveryService` with `findOpenTopicsForHomePage(UUID
      viewerParticipantIdOrNull, int limit)`, returning `List<OpenTopicRow(Topic topic, int memberCount,
      List<Skill> viewerOfferedSkills)>` (data-model.md), composing `TopicRepository`, `GroupService`
      (`findActiveGroupForTopic`, `activeMemberCount`), Skill loading against `topic_skills`/
      `participant_skills`, and `OrganiserSettingsService` for `skillDisplayMode`/`maxGroupMembers`, in
      `src/main/java/net/fabcelhaft/hackathonorganiser/topic/TopicDiscoveryService.java`, depends on T012,
      T009 — makes T026 pass
- [X] T029 [US2] Replace `HomeController`'s call to `TopicService.findVisibleTopicsFor` with
      `TopicDiscoveryService.findOpenTopicsForHomePage(...)` (resolving the viewer's Participant id via the
      existing `ParticipantService.findByUserId` lookup, `null` if none), in
      `src/main/java/net/fabcelhaft/hackathonorganiser/home/HomeController.java`, depends on T028 — makes T027
      pass
- [X] T030 [P] [US2] Replace the Topics section's `<ul>` markup in
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

- [X] T031 [P] [US3] Write failing unit tests for `GroupService.join(UUID topicId, UUID participantId)`
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
- [X] T032 [P] [US3] Write failing unit tests for `TopicJoinService.join(UUID topicId, UUID requesterUserId)`
      (data-model.md): rejects with a distinct `TopicJoinConflictException` message when
      `topicJoiningEnabled` is `false` (FR-020b), when the requester has no Participant record or a non-`ACTIVE`
      status (FR-007b), and completes empty (→ 404) for an unknown or non-`APPROVED` Topic; otherwise delegates
      to `GroupService.join` — verified via `StepVerifier` — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/topic/TopicJoinServiceTest.java` (new file)
- [X] T033 [P] [US3] Write failing `WebTestClient` integration tests: `POST /topics/{id}/join` succeeds
      immediately with no confirmation step and a success flash (FR-007a); creates a Group on first join,
      grows it on a second; rejects with a "full" message once at Maximum; rejects a requester already in a
      different Group; rejects a Not-Participated/Revoked/no-record requester; 404 for an unknown or Pending
      Topic id; the Topic's own author can join their own Topic like anyone else (Edge Cases) — per
      [contracts/join-action.md](contracts/join-action.md) — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/topics/TopicJoinManagementIT.java` (new file)

### Implementation for User Story 3

- [X] T034 [US3] Implement `GroupService.join(UUID topicId, UUID participantId)`: inside one
      `TransactionalOperator`-wrapped transaction, execute `SELECT pg_advisory_xact_lock(hashtext('topic-join:'
      || topicId))`, re-read the Topic's active Group (if any) and its `activeMemberCount`, re-read
      `OrganiserSettings.maxGroupMembers` and the Group's `complianceOverride`, reject with
      `GroupConflictException` if the join would reach/exceed the Maximum and no override is set, then either
      create a new Group (reusing `create`'s existing single-active-Group-per-Topic path) or `addMember` onto
      the existing one (reusing its existing one-active-Group-per-Participant guard) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/group/GroupService.java`, depends on T012, T011, T009 —
      makes T031 pass
- [X] T035 [P] [US3] Create `TopicJoinConflictException` (mirrors `TopicConflictException`'s existing shape) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/topic/TopicJoinConflictException.java`
- [X] T036 [US3] Implement `TopicJoinService.join(UUID topicId, UUID requesterUserId)`: re-reads
      `OrganiserSettingsService.current().isTopicJoiningEnabled()` (FR-020c, never cached), the requester's
      Participant record/status via `ParticipantService.findByUserId`, and the Topic's `approvalStatus` via
      `TopicService.findById`, in that order, each rejection a distinct `TopicJoinConflictException` message,
      before delegating to `GroupService.join` in
      `src/main/java/net/fabcelhaft/hackathonorganiser/topic/TopicJoinService.java`, depends on T034, T035 —
      makes T032 pass
- [X] T037 [US3] Implement `TopicJoinController` (`POST /topics/{id}/join`) per
      [contracts/join-action.md](contracts/join-action.md): redirects home with a success flash, or catches
      `TopicJoinConflictException`/an unknown-Topic empty result and redirects home with an explanatory flash
      (200/303 per contract) — in
      `src/main/java/net/fabcelhaft/hackathonorganiser/topics/TopicJoinController.java`, depends on T036 —
      makes T033 pass
- [X] T038 [US3] Extend `HomeController`/`home/index.html` (from T029/T030): render a "Join" action per row
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

- [X] T039 [P] [US4] Write failing `WebTestClient` integration test additions: `organiser/settings/form.html`
      shows/saves a "Topic joining enabled" checkbox, defaulting to on (FR-020d); non-Organiser access denied
      — extending `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/settings/SettingsManagementIT.java`
- [X] T040 [P] [US4] Write failing `WebTestClient` integration test additions: with the setting disabled, no
      "Join" action renders on `GET /` for any Participant (FR-020b); a direct `POST /topics/{id}/join` is
      still rejected server-side even if issued as if the page had rendered the button before the setting
      changed (FR-020c, Edge Cases); re-enabling restores the action on the very next view (SC-009); a
      Revoked/Not-Participated Participant sees no "Join" action regardless of this setting's value (Story 4
      Acceptance Scenario 3) — extending
      `src/test/java/net/fabcelhaft/hackathonorganiser/topics/TopicJoinManagementIT.java`

### Implementation for User Story 4

- [X] T041 [US4] Extend `OrganiserSettingsController` and `src/main/resources/templates/organiser/settings/
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

- [X] T042 [P] [US5] Write failing unit tests for `TopicDiscoveryService.findTopicOverview(UUID viewerUserId,
      boolean viewerIsOrganiser)` (FR-005–FR-006, FR-014): every Topic visible to the viewer (reusing
      `TopicService`'s existing Pending-visibility rule — a Pending Topic included only for its author or an
      Organiser); each row's author display name, member count (`0` if no Group), needed Skills after
      `skillDisplayMode` (**not** intersected with the viewer, unlike the Home Page); an empty `Optional`
      Compliance status for a Topic with no Group ("No Group Yet"); otherwise `ComplianceService.evaluate(...)`
      result — verified via `StepVerifier` — extending
      `src/test/java/net/fabcelhaft/hackathonorganiser/topic/TopicDiscoveryServiceTest.java`
- [X] T043 [P] [US5] Write failing `WebTestClient` integration tests: `GET /topics/overview` lists every
      visible Topic with correct columns; a Pending Topic appears only for its author/an Organiser; each
      Compliance status renders as one of `Compliant`/`Not Compliant`/`Compliant (Organiser Override)`/`No
      Group Yet`, conveyed by text/icon not color alone (FR-025); the nav item is present for every
      authenticated user unconditionally — per
      [contracts/home-and-topic-overview.md](contracts/home-and-topic-overview.md) — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/topics/TopicOverviewManagementIT.java` (new file)

### Implementation for User Story 5

- [X] T044 [US5] Implement `TopicDiscoveryService.findTopicOverview(UUID viewerUserId, boolean
      viewerIsOrganiser)`, returning `List<OverviewRow(Topic topic, String authorDisplayName, int memberCount,
      List<Skill> neededSkills, Optional<ComplianceStatus> complianceStatus)>` (data-model.md), reusing
      `TopicService`'s Pending-visibility filter, `GroupService.activeMemberParticipantIds` +
      `ComplianceService.evaluate` for the Compliance column, in
      `src/main/java/net/fabcelhaft/hackathonorganiser/topic/TopicDiscoveryService.java`, depends on T028,
      T017 — makes T042 pass
- [X] T045 [US5] Implement `TopicOverviewController` (`GET /topics/overview`) per
      [contracts/home-and-topic-overview.md](contracts/home-and-topic-overview.md) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/topics/TopicOverviewController.java`, depends on T044 —
      makes T043 pass
- [X] T046 [P] [US5] Create `src/main/resources/templates/topics/overview.html` (table: Name / Author /
      Participant count / Needed Skills / Compliance status badge — text + icon, never color alone, FR-025),
      depends on T045
- [X] T047 [P] [US5] Add a static, unconditional "Topic Overview" nav item to
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

- [X] T048 [P] [US6] Write failing unit tests for `ComplianceService.addRequirement(UUID
      customFieldDefinitionId, int minimumDistinctValues)` (rejects `< 2` with `ComplianceConflictException`,
      FR-011d; rejects an unknown Custom Field id; rejects a Custom Field id already configured, research.md
      §3) and `ComplianceService.removeRequirement(UUID id)` (always succeeds for an existing row) — verified
      via `StepVerifier` — extending
      `src/test/java/net/fabcelhaft/hackathonorganiser/compliance/ComplianceServiceTest.java`
- [X] T049 [P] [US6] Write failing `WebTestClient` integration tests: `GET /organiser/compliance` shows the
      seeded default Maximum (`5`), no Minimum, empty requirement list on a fresh instance (FR-011c); `POST
      /organiser/compliance` accepts a valid max/min pair and rejects a blank Maximum (FR-011b) and a Minimum
      exceeding Maximum (FR-011a), each with a field-associated error; `POST
      /organiser/compliance/diversity-requirements` adds a valid requirement and rejects a minimum below 2
      (FR-011d); `POST /organiser/compliance/diversity-requirements/{id}/delete` removes one; every route
      denies a non-Organiser (SC-007) — per
      [contracts/compliance-settings-and-override.md](contracts/compliance-settings-and-override.md) — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/compliance/ComplianceManagementIT.java` (new
      file)
- [X] T050 [P] [US6] Write failing `WebTestClient` integration test addition: an Organiser cannot delete a
      Custom Field Definition currently referenced by a diversity requirement (FR-020, Edge Cases; proves T018
      end-to-end) — extending
      `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/customfield/CustomFieldManagementIT.java`

### Implementation for User Story 6

- [X] T051 [US6] Implement `ComplianceService.addRequirement(UUID customFieldDefinitionId, int
      minimumDistinctValues)` and `removeRequirement(UUID id)` in
      `src/main/java/net/fabcelhaft/hackathonorganiser/compliance/ComplianceService.java`, depends on T014,
      T016 — makes T048 pass
- [X] T052 [US6] Implement `ComplianceController` (`GET`/`POST /organiser/compliance`, `POST
      /organiser/compliance/diversity-requirements`, `POST
      /organiser/compliance/diversity-requirements/{id}/delete`) per
      [contracts/compliance-settings-and-override.md](contracts/compliance-settings-and-override.md), reusing
      `OrganiserSettingsService.update(...)` (T010) for max/min and `ComplianceService` (T051) for the
      requirement list, in
      `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/compliance/ComplianceController.java`,
      depends on T010, T051 — makes T049 pass
- [X] T053 [P] [US6] Create `src/main/resources/templates/organiser/compliance/form.html` (Maximum/Minimum
      fields; the current diversity-requirement list with a remove action per row; an add-requirement form —
      Custom Field dropdown restricted to fields not already configured, minimum-distinct-value input
      defaulting to 2; every control has a programmatically associated label, FR-023), depends on T052
- [X] T054 [US6] Add a "Compliance" nav link to the Organiser area in
      `src/main/resources/templates/organiser/fragments/layout.html`, depends on T052
- [X] T055 [US6] Extend `organiser/custom-fields/list.html`'s existing delete-rejection message rendering (no
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

- [X] T056 [P] [US7] Write failing unit tests for `GroupService.setComplianceOverride(UUID groupId, boolean
      override)` (sets/clears the flag; completes empty/404-mappable for an unknown `groupId`) — extending
      `src/test/java/net/fabcelhaft/hackathonorganiser/group/GroupServiceTest.java`
- [X] T057 [P] [US7] Write failing `WebTestClient` integration tests: `POST
      /organiser/groups/{id}/compliance-override` sets the override, the Group detail view immediately shows
      `Compliant (Organiser Override)` (FR-015), and a subsequent join beyond Maximum succeeds (SC-006);
      removing the override reverts both the badge and Maximum enforcement on the very next read (FR-016,
      Acceptance Scenario 3); 404 for an unknown Group id; non-Organiser denied — per
      [contracts/compliance-settings-and-override.md](contracts/compliance-settings-and-override.md) — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/group/GroupManagementIT.java` (extends the
      existing file)

### Implementation for User Story 7

- [X] T058 [US7] Implement `GroupService.setComplianceOverride(UUID groupId, boolean override)` in
      `src/main/java/net/fabcelhaft/hackathonorganiser/group/GroupService.java`, depends on T011 — makes T056
      pass
- [X] T059 [US7] Extend `GroupController` with `POST /organiser/groups/{id}/compliance-override` per
      [contracts/compliance-settings-and-override.md](contracts/compliance-settings-and-override.md), computing
      the badge value via `ComplianceService.evaluate` + `GroupService.activeMemberParticipantIds` for the
      detail view's model, in
      `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/group/GroupController.java`, depends on T058,
      T017 — makes T057 pass
- [X] T060 [P] [US7] Extend `src/main/resources/templates/organiser/groups/detail.html` with a Compliance
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

- [X] T061 [P] [US8] Write failing unit test additions for `TopicDiscoveryService`: with `skillDisplayMode =
      STILL_NEEDED_ONLY`, a Skill already held by a current Group member is excluded from both
      `findOpenTopicsForHomePage` and `findTopicOverview`'s Skills column; with `ALL_ASSOCIATED`, every needed
      Skill is shown regardless of coverage; a Topic with no Group yet treats every needed Skill as still
      needed (Edge Cases) — extending
      `src/test/java/net/fabcelhaft/hackathonorganiser/topic/TopicDiscoveryServiceTest.java`
- [X] T062 [P] [US8] Write failing `WebTestClient` integration test additions: `organiser/settings/form.html`
      shows/saves a "Skill Display Mode" radio group; changing it changes the Skills column on both `GET /` and
      `GET /topics/overview` on the very next view, no deployment required (FR-018) — extending
      `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/settings/SettingsManagementIT.java`,
      `src/test/java/net/fabcelhaft/hackathonorganiser/home/HomeControllerIT.java`, and
      `src/test/java/net/fabcelhaft/hackathonorganiser/topics/TopicOverviewManagementIT.java`

### Implementation for User Story 8

- [X] T063 [US8] Verify/close any gap in `TopicDiscoveryService`'s existing `skillDisplayMode` handling (T028,
      T044) so both read models correctly compute the "covered by any current active member" set from
      `participant_skills` before applying the mode — makes T061 pass
- [X] T064 [US8] Extend `OrganiserSettingsController` and `organiser/settings/form.html` with a "Skill Display
      Mode" two-option radio group wired to `OrganiserSettingsService.update(...)`'s `skillDisplayMode`
      parameter (T010), in
      `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/settings/OrganiserSettingsController.java` —
      makes T062 pass

**Checkpoint**: All eight user stories are independently functional.

---

## Phase 10: Polish & Cross-Cutting Concerns

**Purpose**: The automated accessibility gate (SC-008), which only makes sense once every screen it scans
exists, plus a final full-suite/manual verification pass.

- [X] T065 [P] Extend `a11y.HomepageAccessibilityIT` to scan the Home Page's new 3-column topic table
      including a rendered "Join" action, and the Topic proposal/edit form's Skill picker (`GET /topics/new`),
      reusing 003's already-justified Playwright + axe-core tooling (research.md §9); asserts zero
      `critical`/`serious` WCAG 2.1 AA violations on each (SC-008) — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/a11y/HomepageAccessibilityIT.java`
- [X] T066 [P] Write `a11y.TopicOverviewAccessibilityIT` (`GET /topics/overview`, including its Compliance
      status badges): asserts zero `critical`/`serious` WCAG 2.1 AA violations (SC-008) — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/a11y/TopicOverviewAccessibilityIT.java` (new file)
- [X] T067 [P] Write `a11y.ComplianceSettingsAccessibilityIT`: scans `GET /organiser/compliance` (including
      the diversity-requirement add/remove controls) and the Group detail view's compliance-override control;
      asserts zero `critical`/`serious` WCAG 2.1 AA violations (SC-008) — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/a11y/ComplianceSettingsAccessibilityIT.java` (new file)
- [X] T068 Review T065–T067's results and adjust `src/main/resources/static/css/app.css` as needed so the
      Compliance status badge's text/icon treatment and the diversity-requirement row layout meet WCAG 2.1 AA
      contrast minimums (4.5:1 normal text, 3:1 large text/UI component boundaries) in both light and dark
      presentation (FR-027)
- [X] T069 Run `mvn verify` (full unit + `*ManagementIT`/`*IT` + `a11y.*IT` suite, including the join-race
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

---

## Phase 11: Convergence

**Purpose**: Close the gap between spec.md's 2026-08-30 update (Stories 9–10, FR-004a, FR-006a, FR-006b,
FR-014/FR-014a, FR-030–FR-036, SC-011–SC-013) and the current codebase, which still reflects the pre-update
spec. `/speckit-plan` already refreshed research.md/data-model.md/contracts/quickstart.md for this delta; the
tasks below implement it. Ordered tests-before-implementation per finding, HIGH findings first.

### Tests (write first, confirm they fail)

- [X] T070 Write failing unit test additions in
      `src/test/java/net/fabcelhaft/hackathonorganiser/topic/TopicDiscoveryServiceTest.java` for the new
      `findOpenTopicsForHomePage(UUID viewerUserId, UUID viewerParticipantIdOrNull, int limit)` signature: the
      viewer's own Topics (any approval status, any fullness) are returned pinned (`pinned = true`) above the
      fullness-sorted rows, sorted fullest-first among themselves, never truncated away even when they alone
      exceed `limit`, with the non-pinned `others` list truncated first to keep the total at `limit`; a pinned
      Topic already present in the fullness-sorted list is not duplicated; each row's new `joinable` boolean is
      `true` only for an Approved Topic below Maximum (or carrying a compliance override) — `false` for a
      pinned Pending or full Topic (FR-035) — verified via `StepVerifier` per FR-033, FR-035 (gap: contradicts)
- [X] T071 Write failing unit test additions in the same file for `findTopicOverview(UUID viewerUserId, boolean
      viewerIsOrganiser)`: the viewer's own Topics appear pinned (`pinned = true`) above all other rows with no
      truncation (the method's signature is unchanged); each row's new `joinable` boolean follows the same rule
      as T070 — per FR-034, FR-035 (gap: missing)
- [X] T072 Write failing unit test additions in the same file for the new `findTopicDetail(UUID topicId, UUID
      viewerUserId, boolean viewerIsOrganiser)` method: completes empty (→ 404) for an unknown or
      Pending-and-invisible Topic id, reusing `TopicService.isVisibleTo`; otherwise returns the Topic's Name,
      Description, needed Skills after `skillDisplayMode`, current participant count, `Optional<ComplianceStatus>`
      (empty when no Group yet), one `ParticipantService.ParticipantViewerDetail` per currently joined member
      (obtained by calling the existing `ParticipantService.findDetailForViewer` once per
      `GroupService.activeMemberParticipantIds` entry — no new visibility logic), and `isAuthor` — verified via
      `StepVerifier` — per FR-030, FR-031, FR-032, FR-014a, research.md §10/§13 (gap: missing)
- [X] T073 Write failing `WebTestClient` integration test additions in
      `src/test/java/net/fabcelhaft/hackathonorganiser/home/HomeControllerIT.java`: a viewer's own Pending Topic
      and own full Topic both appear pinned above the fullness-sorted rows on `GET /` (and are not duplicated if
      they would also naturally appear there); a pinned Pending or full Topic never shows a "Join" action even
      when the viewer is otherwise eligible (FR-035); every row, pinned or not, shows a "View Details" link to
      `GET /topics/{id}`; the rendered page no longer contains the word "Group" anywhere reachable by a
      non-Organiser (the `#status-section` label and the revoke-confirmation dialog's copy) — per FR-033,
      FR-035, FR-004a, FR-036 (gap: contradicts/missing)
- [X] T074 Write failing `WebTestClient` integration test additions in
      `src/test/java/net/fabcelhaft/hackathonorganiser/topics/TopicOverviewManagementIT.java`: every joinable
      row shows the same self-service "Join" action as the Home Page, gated by the same eligibility
      (Active Participant, no active Group, Topic joining enabled, plus the row's own `joinable` flag); every
      row shows a "View Details" link; the viewer's own Topics appear pinned above all other rows with nothing
      hidden (no cap); a Topic with no Group yet renders a blank Compliance cell — no "No Group Yet" text or
      icon — per FR-006a, FR-006b, FR-034, FR-014a (gap: missing/contradicts)
- [X] T075 Write a new failing `WebTestClient` integration test file
      `src/test/java/net/fabcelhaft/hackathonorganiser/topics/TopicDetailManagementIT.java` per
      [contracts/topic-details.md](contracts/topic-details.md): `GET /topics/{id}` returns 200 with Name,
      Description, Skills (display-mode filtered), participant count, and Compliance (blank if no Group) for any
      visible Topic; the joined-Participants list shows only `public`-marked Custom Field values and
      Skills-only-if-`skillVisibilityEnabled` for a non-member/non-organiser viewer, and full detail for the
      Topic's author, an Organiser, or a member viewing their own row; 404 for an unknown id or a Pending Topic
      the caller may not see; an "edit" link appears only for the Topic's author; a viewer outside the
      configured Participants-Directory audience can still see the joined-Participants list (FR-032); the
      response body never contains the word "Group" — per FR-030, FR-031, FR-032, FR-036 (gap: missing)
- [X] T076 Write a new `a11y.TopicDetailAccessibilityIT` in
      `src/test/java/net/fabcelhaft/hackathonorganiser/a11y/TopicDetailAccessibilityIT.java`: scans
      `GET /topics/{id}` for a Topic with at least one joined Participant, asserting zero critical/serious
      WCAG 2.1 AA violations, reusing 003's Playwright + axe-core tooling (research.md §9) — per FR-021, SC-008
      (gap: missing)
- [X] T077 Extend `a11y.HomepageAccessibilityIT` and `a11y.TopicOverviewAccessibilityIT` to additionally scan a
      rendered "View Details" link (and, on the Overview, a rendered "Join" action) and the pinned-rows grouping,
      asserting zero critical/serious WCAG 2.1 AA violations on each — per FR-021, FR-022, SC-008 (gap: missing)

### Implementation

- [X] T078 In `src/main/java/net/fabcelhaft/hackathonorganiser/topic/TopicDiscoveryService.java`, add a leading
      `UUID viewerUserId` parameter to `findOpenTopicsForHomePage(...)` and implement own-Topic pinning:
      partition `topicRepository.findAll()` into the viewer's own Topics (`topic.getCreatedByUserId().equals
      (viewerUserId)`, any approval status, any fullness) and others; assemble every pinned row via the same
      per-row `withActiveGroupAndCount`/Skill-computation the existing fullness-sorted path already uses, sort
      pinned fullest-first; compute the existing fullness-sorted/not-full/Approved list, excluding any Topic id
      already pinned, and truncate only that list so the combined total stays at `limit`; add `boolean pinned`
      and `boolean joinable` (Approved && (memberCount < maxGroupMembers || group.complianceOverride)) fields to
      `OpenTopicRow` — depends on data-model.md's "Modified read-model methods" table — per FR-033, FR-035,
      research.md §11 — makes T070 pass
- [X] T079 In the same file, apply the identical own/others pinning split inside `findTopicOverview(...)`
      (signature unchanged — `viewerUserId` is already a parameter), with no truncation on either part; add the
      same `boolean pinned` and `boolean joinable` fields to `OverviewRow` — per FR-034, FR-035, research.md §11
      — makes T071 pass
- [X] T080 In the same file, implement `findTopicDetail(UUID topicId, UUID viewerUserId, boolean
      viewerIsOrganiser)` returning `Mono<TopicDetailView>` (new record: `Topic topic, List<Skill> neededSkills,
      int memberCount, Optional<ComplianceStatus> complianceStatus, List<ParticipantService.ParticipantViewerDetail>
      members, boolean isAuthor`), reusing the existing package-private static `TopicService.isVisibleTo(...)`
      check, the same Group/member-count/Skill-Display-Mode/Compliance assembly `buildOverviewRow` already
      performs for one Topic, and calling `ParticipantService.findDetailForViewer` once per
      `GroupService.activeMemberParticipantIds(groupId)` entry; add `ParticipantService` as a new constructor
      dependency on `TopicDiscoveryService` — depends on T078, T079 (shared helpers) — per FR-030, FR-031,
      FR-032, research.md §10/§13 — makes T072 pass
- [X] T081 Add `@GetMapping("/{id}")` to
      `src/main/java/net/fabcelhaft/hackathonorganiser/topics/TopicSelfServiceController.java` rendering
      `topics/detail.html` from `TopicDiscoveryService.findTopicDetail(id, userId, oidcUser.getUser().isOrganiser())`,
      404 (`ResponseStatusException(HttpStatus.NOT_FOUND)`) on an empty result; add `TopicDiscoveryService` as a
      new constructor dependency on this controller — per FR-030, FR-032, research.md §13 — makes T075 pass
- [X] T082 [P] Create `src/main/resources/templates/topics/detail.html`: Name, Description, needed Skills,
      participant count, Compliance (text/icon per non-empty status per FR-025, a blank cell when
      `complianceStatus` is empty per FR-014a), a joined-Participants table (one row per `member`: display name,
      each visible `ViewerFieldValue`, and Skills only when `skillsVisibleToViewer`), an "Edit this Topic" link
      shown only when `isAuthor`; the template MUST NOT contain the word "Group" anywhere — depends on T081 —
      per FR-030, FR-031, FR-036 — makes T075, T076 pass
- [X] T083 In `src/main/java/net/fabcelhaft/hackathonorganiser/home/HomeController.java`, pass the already-in-scope
      `userId` as the new leading argument to `TopicDiscoveryService.findOpenTopicsForHomePage(...)`; in the same
      file and `src/main/resources/templates/home/index.html`, reword the `#status-section` "Group topic: …"
      label (e.g. to "Your Topic: …") and the revoke-confirmation dialog's "removes you from your current Group"
      copy (e.g. "removes you from your current Topic's team") so neither says "Group" — per FR-033, FR-036 —
      makes T073 pass
- [X] T084 [P] In `src/main/resources/templates/home/index.html`: add a "View Details" link
      (`th:href="@{/topics/{id}(id=${row.topic().id})}"`) to every row; change the Join column's per-row
      condition from the page-level `canJoinTopics` alone to `canJoinTopics && row.joinable()` (FR-035); group
      `pinned` rows visually above the rest (e.g. a "Your Topics" sub-heading, or splitting the `th:each` into two
      passes) — depends on T083 — per FR-004a, FR-033, FR-035 — makes T073 pass
- [X] T085 In `src/main/java/net/fabcelhaft/hackathonorganiser/topics/TopicOverviewController.java`, compute the
      same viewer-side join eligibility `HomeController` already computes (Active Participant, no active Group,
      `topicJoiningEnabled`) and add it to the model (e.g. `canJoinTopics`), since this controller currently
      passes only `rows` — per FR-006a — makes T074 pass
- [X] T086 In `src/main/resources/templates/topics/overview.html`: add a "Join" action column reusing the same
      `POST /topics/{id}/join` form markup as the Home Page, rendered per row when `canJoinTopics &&
      row.joinable()` (T085, FR-035); add a "View Details" link column; remove the `th:if="${row.complianceStatus
      ().isEmpty()}"` branch's "No Group Yet" text/icon content so an empty `complianceStatus` renders a blank
      cell (FR-014a); group `pinned` rows visually above the rest — depends on T085 — per FR-006a, FR-006b,
      FR-014a, FR-034 — makes T074 pass
- [X] T087 Run `mvn verify` (full unit + `*ManagementIT`/`*IT` + `a11y.*IT` suite, including the new
      `TopicDetailManagementIT` and `TopicDetailAccessibilityIT`) and perform a manual visual smoke test of
      Stories 9 and 10 end-to-end per [quickstart.md](quickstart.md) §8 (Constitution Development Workflow #3)

**Checkpoint**: Stories 9 and 10 are fully functional and testable independently —
`mvn verify -Dit.test=TopicDetailManagementIT,TopicOverviewManagementIT,HomeControllerIT`.

---

## Phase 12: Convergence

**Purpose**: Close the gap between the spec/plan's latest update (the Topic Details view's two-table layout,
FR-030/FR-031, and the new self-service Leave action, Story 11/FR-037–FR-037e/SC-014) and the current codebase,
which still renders `topics/detail.html` via `<dl>`/`<ul>` and has no `leave` capability anywhere
(`GroupService`, `TopicJoinService`, `TopicJoinController` all confirmed to have zero `leave`-related code).
`/speckit-plan` already refreshed research.md §14/data-model.md/contracts/quickstart.md for this delta; the
tasks below implement it. Tests before implementation per finding, per Constitution Principle V.

### Tests (write first, confirm they fail)

- [X] T088 [P] Write failing unit tests for `GroupService.leave(UUID topicId, UUID participantId)`
      (research.md §14): no active Group for the Topic → `GroupConflictException`; an active Group but the
      requester is not currently an active member → `GroupConflictException` (reusing `removeMember`'s existing
      empty-completion signal); an active Group with survivors → the requester's membership flips inactive, the
      Group stays `ACTIVE`, `activeMemberCount` drops by one; the requester was the last active member → the
      Group transitions to `DISBANDED` via the existing `disband` (status, `disbandedAt`, every membership
      inactive); two concurrent `leave(...)` calls against a Testcontainers-backed Postgres connection where one
      call is the last remaining member → the Group is disbanded exactly once, never zero or twice (Edge Cases)
      — in `src/test/java/net/fabcelhaft/hackathonorganiser/group/GroupServiceTest.java` (extends the existing
      file) — per FR-037c, research.md §14 (gap: missing)
- [X] T089 [P] Write failing unit tests for `TopicJoinService.leave(UUID topicId, UUID requesterUserId)`: rejects
      with a `TopicJoinConflictException` when the requester has no Participant record, no currently-active
      Group, or an active Group for a *different* Topic (FR-037b); does **not** reject when
      `OrganiserSettings.topicJoiningEnabled` is `false` or the Participant's status is not `ACTIVE` (FR-037e —
      a non-`ACTIVE` Participant already has no active Group to leave by construction); otherwise delegates to
      `GroupService.leave` — verified via `StepVerifier` — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/topic/TopicJoinServiceTest.java` (extends the existing
      file) — per FR-037b, FR-037e (gap: missing)
- [X] T090 [P] Write failing `WebTestClient` integration tests per
      [contracts/topic-details.md](contracts/topic-details.md): `POST /topics/{id}/leave` succeeds immediately
      with no confirmation step, redirecting `303 → /topics/{id}` with a success flash containing no reference
      to "Group" (FR-037a, FR-036); the requester no longer appears in the Joined Participants table on the next
      render; when the requester was the Group's last member, the Topic Details view subsequently shows an
      empty Joined Participants table, a blank Compliance value, and the Topic is joinable again via `POST
      /topics/{id}/join` (FR-037c); a requester who does not currently belong to this Topic's Group (including
      no Participant record) is rejected server-side (FR-037b); the requester can immediately join a *different*
      Topic afterward (FR-037d); two simultaneous `POST /topics/{id}/leave` submissions where the requester is
      the Group's last remaining member result in disbandment applied exactly once (Edge Cases, mirroring the
      existing join-race test's Testcontainers-backed approach, research.md §14) — extending
      `src/test/java/net/fabcelhaft/hackathonorganiser/topics/TopicDetailManagementIT.java` — per FR-037,
      FR-037a–FR-037d (gap: missing)
- [X] T091 [P] Write failing `WebTestClient` integration test additions asserting `GET /topics/{id}`'s response
      body renders the Topic's key values (Description, needed Skills, participant count, Compliance) as a
      `<table>` with one row each — not the current `<dl>`/`<dd>` markup — and the joined-Participants list as a
      separate `<table>` with one row per member and columns for display name, each visible Custom Field value,
      and Skills when visible — not the current `<ul>`/`<li>` markup — preserving the existing blank-cell-when-
      no-Group (FR-014a) and text/icon-not-color-alone Compliance rendering (FR-025) — extending
      `src/test/java/net/fabcelhaft/hackathonorganiser/topics/TopicDetailManagementIT.java` — per FR-030, FR-031
      (gap: partial)

### Implementation

- [X] T092 Implement `GroupService.leave(UUID topicId, UUID participantId)` (research.md §14): inside the same
      `TransactionalOperator`-wrapped transaction and `pg_advisory_xact_lock(hashtext('topic-join:' ||
      topicId))` `join` (T034) already acquires, re-read the Topic's active Group (empty → `GroupConflictException`,
      "You are not currently a member of this Topic"), call the **already-existing** `removeMember(groupId,
      participantId)` (empty completion → the same exception), re-read `activeMemberCount(groupId)`, and call the
      **already-existing** `disband(groupId)` only when it is now `0` — no new SQL — in
      `src/main/java/net/fabcelhaft/hackathonorganiser/group/GroupService.java`, depends on T012, T034 — makes
      T088 pass
- [X] T093 Implement `TopicJoinService.leave(UUID topicId, UUID requesterUserId)`: resolve the requester's
      Participant record via the existing `ParticipantService.findByUserId` (no record →
      `TopicJoinConflictException`), resolve their current active Group via the existing
      `GroupService.findActiveGroupForParticipant`, reject if empty or its `topicId` does not match the one
      being left, otherwise delegate to `GroupService.leave` (T092) — in
      `src/main/java/net/fabcelhaft/hackathonorganiser/topic/TopicJoinService.java`, depends on T092 — makes
      T089 pass
- [X] T094 Implement `POST /topics/{id}/leave` on `TopicJoinController` per
      [contracts/topic-details.md](contracts/topic-details.md): redirects `303 → /topics/{id}?flash=You+left+
      <Topic+name>.` on success (deliberately back to the Topic Details page, not Home like `join`'s redirect),
      or catches `TopicJoinConflictException`/`GroupConflictException` and redirects the same way with an
      explanatory flash — in
      `src/main/java/net/fabcelhaft/hackathonorganiser/topics/TopicJoinController.java`, depends on T093 —
      makes T090 pass
- [X] T095 Add `boolean isMember` to `TopicDiscoveryService.TopicDetailView` and compute it inside
      `findTopicDetail(...)` from the viewer's Participant record (if any) and the existing
      `GroupService.findActiveGroupForParticipant`, `true` only when that Group's `topicId` matches the Topic
      being viewed (`false` for a viewer with no Participant record, no active Group, or an active Group for a
      different Topic) — in
      `src/main/java/net/fabcelhaft/hackathonorganiser/topic/TopicDiscoveryService.java`, depends on T092 — per
      FR-037, data-model.md — makes T090 pass (drives the Leave form's conditional rendering, T098)
- [X] T096 [P] Rework `templates/topics/detail.html`'s key-values section from its current `<dl>` markup to a
      "Topic Info" `<table>` (one `<tr>` per row: Description, needed Skills, participant count, Compliance),
      preserving the existing blank-cell-when-no-Group (FR-014a) and text/icon-not-color-alone Compliance
      rendering (FR-025) unchanged — depends on T095 — per FR-030 — makes T091 pass
- [X] T097 [P] Rework the same template's joined-members section from its current `<ul>` markup to a "Joined
      Participants" `<table>` (one `<tr>` per member, columns for display name, each visible Custom Field value,
      and Skills only when visible), preserving the existing "no one has joined yet" empty-state message —
      depends on T095 — per FR-031 — makes T091 pass
- [X] T098 Add a "Leave" form/button to the same template, rendered only when `detail.isMember()` (T095),
      posting to `POST /topics/{id}/leave` (T094) with a programmatically associated label/accessible name
      (FR-022, FR-023) and no confirmation step (FR-037a) — depends on T094, T095 — per FR-037, FR-037a — makes
      T090 pass
- [X] T099 Run `mvn verify` (full unit + `*ManagementIT`/`*IT` + `a11y.*IT` suite, including the new leave-race
      concurrent-request test, T088/T090) and perform a manual visual smoke test of the two-table Topic Details
      layout and the Leave action end-to-end per [quickstart.md](quickstart.md) §8–§9 (Constitution Development
      Workflow #3); confirm the existing `a11y.TopicDetailAccessibilityIT` (which already logs in as a joined
      member for its second scan) still reports zero critical/serious WCAG 2.1 AA violations now that a Leave
      control renders on that same page

**Checkpoint**: The two-table Topic Details layout and Story 11's Leave action are fully functional and
testable independently — `mvn verify -Dit.test=TopicDetailManagementIT,GroupServiceTest,TopicJoinServiceTest`.
