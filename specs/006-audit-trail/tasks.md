---

description: "Task list for Audit Trail for Topics and Participants"
---

# Tasks: Audit Trail for Topics and Participants

**Input**: Design documents from `/specs/006-audit-trail/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/audit-retrieval.md, quickstart.md

**Tests**: Included. The project constitution (`.specify/memory/constitution.md`, Principle V) makes
Test-First Development non-negotiable — every task below that changes behavior has a corresponding test task
sequenced before it. Unit tests use JUnit 5 + Mockito (`*ServiceTest.java`, matching `GroupServiceTest.java`'s
existing pattern); integration tests use `WebTestClient` + Testcontainers + `mockOidcLogin()`
(`*ManagementIT.java`/`*IT.java`, matching `GroupManagementIT.java`'s existing pattern) — no new test
infrastructure is introduced.

**Organization**: Tasks are grouped by user story (spec.md) to enable independent implementation and testing.
Real method/file names below were confirmed by reading the current codebase (`TopicService`, `GroupService`,
`ParticipantService`, and their controllers) — they supersede the illustrative names used in `data-model.md`
(e.g., `ParticipantService.changeStatus`/`replaceSkills`/`setCustomFieldValue`, not
`setStatus`/`updateSkills`/`updateCustomFields`; `TopicController.create`/`update`/`reassignAuthor` on top of
`TopicSelfServiceController.propose`/`updateAsAuthor`).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

Single Spring Boot module (existing layout, no new top-level directories):
`src/main/java/net/fabcelhaft/hackathonorganiser/...`, `src/main/resources/...`,
`src/test/java/net/fabcelhaft/hackathonorganiser/...`.

---

## Phase 1: Setup

**Purpose**: Establish a clean baseline before touching any code.

- [ ] T001 Run `mvn test` from the repository root and confirm the existing 002–005 test suite passes with zero
      failures, so any later failure is attributable to this feature's changes

**Checkpoint**: Clean baseline confirmed.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The shared `audit_entries` table, its entity/repository, and `AuditService` — every user story
depends on these existing first.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [ ] T002 [P] Append the `audit_entries` table DDL and its `(subject_type, subject_id, occurred_at DESC)`
      composite index to `src/main/resources/schema.sql`, per data-model.md's schema: `id uuid PRIMARY KEY
      DEFAULT uuidv7()`, `event_type text NOT NULL`, `actor_user_id uuid NOT NULL REFERENCES users (id)`,
      `organiser boolean NOT NULL`, `occurred_at timestamptz NOT NULL DEFAULT now()`, `subject_type text NOT
      NULL`, `subject_id uuid NOT NULL` (no foreign key), `subject_label text NOT NULL`, `old_value text`,
      `new_value text`, `action_id uuid`
- [ ] T003 [P] Create `AuditEventType` enum (`CREATED`, `EDITED`, `STATUS_CHANGED`, `JOINED`, `LEFT`,
      `DISBANDED`, `DELETED`) in `src/main/java/net/fabcelhaft/hackathonorganiser/audit/AuditEventType.java`
- [ ] T004 [P] Create `AuditSubjectType` enum (`TOPIC`, `PARTICIPANT` — no `GROUP` value) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/audit/AuditSubjectType.java`
- [ ] T005 [P] Create `AuditActor` record (`UUID userId, boolean organiser`) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/audit/AuditActor.java`
- [ ] T006 Create `AuditEntryView` read-model record (`Instant occurredAt, AuditEventType eventType, String
      actorDisplayName, boolean organiser, String subjectLabel, String oldValue, String newValue, UUID
      actionId`) in `src/main/java/net/fabcelhaft/hackathonorganiser/audit/AuditEntryView.java` (depends on:
      T003)
- [ ] T007 Create the `AuditEntry` R2DBC entity (`@Table("audit_entries")`, all columns from T002, `id`/
      `occurredAt` defaulted like every other entity in this codebase, e.g. `Group.java`) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/audit/AuditEntry.java` (depends on: T003, T004)
- [ ] T008 Create `AuditEntryRepository extends ReactiveCrudRepository<AuditEntry, UUID>` in
      `src/main/java/net/fabcelhaft/hackathonorganiser/audit/AuditEntryRepository.java` — used only for
      `save`/`findBy...` in this feature; no update/delete method is ever added (FR-010) (depends on: T007)
- [ ] T009 Write `AuditServiceTest` (unit, JUnit 5 + Mockito, `@ExtendWith(MockitoExtension.class)`, mocking
      `AuditEntryRepository`, matching `GroupServiceTest.java`'s style) in
      `src/test/java/net/fabcelhaft/hackathonorganiser/audit/AuditServiceTest.java`: `record(...)` saves an
      `AuditEntry` populated from its arguments and returns it; `findForTopic`/`findForParticipant` each query
      by the matching `subject_type` and return entries ordered most-recent-first; confirm this test fails to
      compile/run until T010 exists (depends on: T005, T006, T007, T008)
- [ ] T010 Implement `AuditService` (`record(AuditEventType type, AuditActor actor, AuditSubjectType
      subjectType, UUID subjectId, String subjectLabel, String oldValue, String newValue, UUID actionId)`,
      `findForTopic(UUID topicId)`, `findForParticipant(UUID participantId)`) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/audit/AuditService.java` to make T009 pass (depends on:
      T009)

**Checkpoint**: Foundation ready — `AuditService` exists and is independently tested; user story implementation
can now begin.

---

## Phase 3: User Story 1 - Every Change to a Topic or Participant Is Recorded (Priority: P1) 🎯 MVP

**Goal**: Every Topic, Participant, and Group-non-membership mutation (create, edit, status/approval change,
disband, compliance override, deletion) writes a correctly-attributed `AuditEntry` — capturing actor, capacity,
event type, subject, and (for the FR-002a high-stakes fields) old/new values — regardless of whether a
Participant or an Organiser performed it. Group membership pairing (join/leave/add-member/remove-member) is
deliberately out of scope here — see User Story 3.

**Independent Test**: Edit a Topic's description as its author, change a Participant's status as an Organiser,
and disband a Topic's Group as an Organiser; confirm a correctly-attributed `AuditEntry` exists for each via
`AuditEntryRepository`, independent of any viewing UI (User Story 2 is not required for this check).

### Tests for User Story 1 ⚠️

> Write these tests FIRST; confirm they fail before starting the implementation tasks below.

- [ ] T011 [P] [US1] Extend `src/test/java/net/fabcelhaft/hackathonorganiser/topic/TopicServiceTest.java`:
      inject a mocked `AuditService`; assert `create`, `update`, `propose`, `updateAsAuthor`, `approve`, and
      `reassignAuthor` each call `AuditService.record(...)` once with the correct `AuditEventType`
      (`CREATED`/`EDITED`/`STATUS_CHANGED`), `AuditSubjectType.TOPIC`, the Topic's id, and the passed-in
      `AuditActor` — `approve` additionally asserts `old="PENDING"`/`new="APPROVED"`; the others assert
      `old=null`/`new=null` (FR-002a: Topic edits are not high-stakes)
- [ ] T012 [P] [US1] Extend
      `src/test/java/net/fabcelhaft/hackathonorganiser/participant/ParticipantServiceTest.java`: inject a
      mocked `AuditService`; assert `register`, `submitRegistration`, `submitSelfEdit`, `changeStatus`,
      `replaceSkills`, `setCustomFieldValue`, `selfRevoke`, and `delete` each call `AuditService.record(...)`
      once with the correct event type and `AuditSubjectType.PARTICIPANT` — `changeStatus`/`selfRevoke` assert
      real `old`/`new` status values (FR-002a high-stakes); the others assert `old=null`/`new=null`; `delete`
      asserts the `DELETED` entry is recorded before the repository delete call
- [ ] T013 [P] [US1] Extend `src/test/java/net/fabcelhaft/hackathonorganiser/group/GroupServiceTest.java`:
      inject a mocked `AuditService`; assert `create`, `disband`, and `setComplianceOverride` each call
      `AuditService.record(...)` once with `AuditSubjectType.TOPIC` and the Group's `topicId` (never a Group
      reference) — `setComplianceOverride` asserts real `old`/`new` boolean values
- [ ] T014 [US1] Create `AuditRecordingIT` (integration, `@SpringBootTest` + `@Testcontainers` + `WebTestClient`
      + `mockOidcLogin()`, matching `GroupManagementIT.java`'s existing setup) in
      `src/test/java/net/fabcelhaft/hackathonorganiser/audit/AuditRecordingIT.java`: as a Participant, edit an
      owned Topic via `POST /topics/{id}/edit`; as an Organiser, change a Participant's status via `POST
      /organiser/participants/{id}/status` and disband a Group via `POST /organiser/groups/{id}/disband`; after
      each, autowire `AuditEntryRepository` and assert a matching row exists with the correct `subject_type`,
      `subject_id`, `event_type`, and `organiser` flag (depends on: T002–T010 (Foundational complete))

### Implementation for User Story 1

- [ ] T015 [US1] Modify `src/main/java/net/fabcelhaft/hackathonorganiser/topic/TopicService.java`: add an
      `AuditActor actor` parameter to `create`, `update`, `propose`, `updateAsAuthor`, `approve`, and
      `reassignAuthor`; call `auditService.record(...)` on success of each per T011's expectations (depends on:
      T011, T010)
- [ ] T016 [US1] Modify
      `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/topic/TopicController.java`: at the
      `create`/`update`/`approve`/`reassignAuthor` call sites, construct `new AuditActor(currentUserId, true)`
      (resolved via `@AuthenticationPrincipal HackathonOidcUser`, matching `TopicJoinController`'s existing
      pattern) and pass it through (depends on: T015)
- [ ] T017 [P] [US1] Modify
      `src/main/java/net/fabcelhaft/hackathonorganiser/topics/TopicSelfServiceController.java`: at the
      `propose`/`updateAsAuthor` call sites, construct `new AuditActor(userId, false)` and pass it through
      (depends on: T015)
- [ ] T018 [US1] Modify
      `src/main/java/net/fabcelhaft/hackathonorganiser/participant/ParticipantService.java`: add an
      `AuditActor actor` parameter to `register`, `submitRegistration`, `submitSelfEdit`, `changeStatus`,
      `replaceSkills`, `setCustomFieldValue`, `selfRevoke`, and `delete`; call `auditService.record(...)` on
      success of each per T012's expectations — `delete` records **before** issuing the repository delete, in
      the same transaction (depends on: T012, T010)
- [ ] T019 [US1] Modify
      `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/participant/ParticipantController.java`: at
      the `register`(`create`)/`changeStatus`/`replaceSkills`/`setCustomFieldValue`/`delete` call sites,
      construct `new AuditActor(currentUserId, true)` and pass it through (depends on: T018)
- [ ] T020 [P] [US1] Modify
      `src/main/java/net/fabcelhaft/hackathonorganiser/participants/RegistrationController.java`: at the
      `submitRegistration` call site, construct `new AuditActor(userId, false)` and pass it through (depends
      on: T018)
- [ ] T021 [P] [US1] Modify
      `src/main/java/net/fabcelhaft/hackathonorganiser/participants/ProfileController.java`: at the
      `submitSelfEdit` call site, construct `new AuditActor(participant's userId, false)` and pass it through
      (depends on: T018)
- [ ] T022 [P] [US1] Modify `src/main/java/net/fabcelhaft/hackathonorganiser/home/HomeController.java`: at the
      `selfRevoke` call site, construct `new AuditActor(userId, false)` and pass it through (depends on: T018)
- [ ] T023 [US1] Modify `src/main/java/net/fabcelhaft/hackathonorganiser/group/GroupService.java`: add an
      `AuditActor actor` parameter to `create`, `disband`, and `setComplianceOverride` only (`addMember`,
      `removeMember`, `join`, `leave` are User Story 3's responsibility); call `auditService.record(...)` on
      success of each per T013's expectations (depends on: T013, T010)
- [ ] T024 [US1] Modify
      `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/group/GroupController.java`: at the
      `create`/`disband`/`setComplianceOverride` call sites, construct `new AuditActor(currentUserId, true)`
      and pass it through (depends on: T023)

**Checkpoint**: User Story 1 is complete and independently verifiable via T014 — every single-entity mutation
across Topic, Participant, and Group (excluding membership pairing) is now audited.

---

## Phase 4: User Story 2 - Organiser Reviews an Entity's Audit Trail On Demand (Priority: P1)

**Goal**: An Organiser can open a Topic's or Participant's "Audit" page from its detail view to see its
complete, most-recent-first history on demand; a Group's detail view's "Audit" link opens its Topic's page
instead of a page of its own. No audit data loads until this action is used, and no non-Organiser can reach it
by any path.

**Independent Test**: As an Organiser, open a Topic's detail page (no audit data present), click **Audit**, and
confirm its full history renders; confirm a Group's detail page's **Audit** link resolves to its Topic's audit
page; confirm a non-Organiser sees no **Audit** action anywhere and a direct `GET` to the route is denied.

### Tests for User Story 2 ⚠️

> Write these tests FIRST; confirm they fail before starting the implementation tasks below.

- [ ] T025 [P] [US2] Extend
      `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/topic/TopicManagementIT.java`: assert `GET
      /organiser/topics/{id}/audit` returns `200` for an Organiser (via `mockOidcLogin()`) and lists previously
      recorded entries most-recent-first; assert it renders an empty, clearly-labeled history for a Topic with
      no entries yet (not an error); assert an unauthenticated or non-Organiser request is denied before any
      audit content renders; assert an unknown Topic id returns `404`
- [ ] T026 [P] [US2] Extend
      `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/participant/ParticipantManagementIT.java`:
      the same four assertions as T025, scoped to `GET /organiser/participants/{id}/audit`
- [ ] T027 [P] [US2] Extend
      `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/group/GroupManagementIT.java`: assert the
      Group detail page's rendered "Audit" link's `href` equals `/organiser/topics/{topicId}/audit` for that
      Group's own Topic; assert `GET /organiser/groups/{id}/audit` does not exist (`404`)

### Implementation for User Story 2

- [ ] T028 [P] [US2] Create the shared audit-entries table fragment in
      `src/main/resources/templates/organiser/audit/list.html` (Pico CSS `<table>`, columns: timestamp, event
      type, actor display name, capacity (Organiser/Standard User), old → new when present; most-recent-first;
      an empty-state message when there are no entries — FR-011, FR-011a)
- [ ] T029 [US2] Add a `GET /organiser/topics/{id}/audit` handler to
      `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/topic/TopicController.java`, loading via
      `AuditService.findForTopic(id)` and rendering a new `organiser/topics/audit.html` view that includes the
      T028 fragment; unknown `id` → `404` (depends on: T028, T010)
- [ ] T030 [US2] Add a `GET /organiser/participants/{id}/audit` handler to
      `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/participant/ParticipantController.java`,
      loading via `AuditService.findForParticipant(id)` and rendering a new
      `organiser/participants/audit.html` view that includes the T028 fragment; unknown `id` → `404` (depends
      on: T028, T010)
- [ ] T031 [P] [US2] Add an "Audit" link to `src/main/resources/templates/organiser/topics/detail.html`
      pointing at the T029 route (depends on: T029)
- [ ] T032 [P] [US2] Add an "Audit" link to `src/main/resources/templates/organiser/participants/detail.html`
      pointing at the T030 route (depends on: T030)
- [ ] T033 [P] [US2] Add an "Audit" link to `src/main/resources/templates/organiser/groups/detail.html`
      pointing at `/organiser/topics/{topicId}/audit`, using the Group's own `topicId` already present in
      `GroupDetail`'s model (`detail.group().getTopicId()`) — no `GroupController` route change (depends on:
      T029)

**Checkpoint**: User Story 2 is complete — Organisers can browse any Topic's or Participant's audit trail on
demand (Group's reusing the Topic's), and no other user can reach it via UI or direct request.

---

## Phase 5: User Story 3 - Topic Membership Changes Are Recorded on Both Sides, However They Happen (Priority: P2)

**Goal**: Joining/leaving a Topic (self-service) and an Organiser directly adding/removing a Group member
produce the *identical* two-linked-entry shape (Topic side + Participant side, sharing one `action_id`) — only
the recorded actor and capacity differ. This also closes a latent concurrency gap: `addMember`/`removeMember`
gain their own Participant-scoped advisory lock, since only the Topic was locked before.

**Independent Test**: Join a Topic as a Participant and confirm the paired `JOINED` entries via both the
Topic's and the Participant's audit trails; have an Organiser directly add a different Participant to that same
Topic's Group and confirm the identical paired shape results, differing only in actor/capacity.

### Tests for User Story 3 ⚠️

> Write these tests FIRST; confirm they fail before starting the implementation tasks below.

- [ ] T034 [P] [US3] Extend `src/test/java/net/fabcelhaft/hackathonorganiser/group/GroupServiceTest.java`:
      assert `addMember` calls `AuditService.record(...)` exactly twice — once with `AuditSubjectType.TOPIC`/
      the Group's `topicId`/event `JOINED`, once with `AuditSubjectType.PARTICIPANT`/the Participant's id/event
      `JOINED` — both sharing one generated `actionId`, regardless of the `AuditActor` passed in; assert
      `removeMember` does the identical thing with event `LEFT`
- [ ] T035 [US3] Create `AuditMembershipPairingIT` (integration, matching `TopicJoinManagementIT.java`'s
      existing setup) in `src/test/java/net/fabcelhaft/hackathonorganiser/audit/AuditMembershipPairingIT.java`:
      (a) a Participant joins a Topic via `POST /topics/{id}/join` — assert, via `AuditEntryRepository`, exactly
      two entries share one `action_id`, event `JOINED`, correct subjects, `organiser=false`; (b) the same
      Participant leaves via `POST /topics/{id}/leave` — assert the equivalent `LEFT` pair; (c) an Organiser
      adds a different Participant via `POST /organiser/groups/{id}/members` — assert the identical paired
      shape with `organiser=true`; (d) an Organiser removes that Participant via the organiser view — assert
      the equivalent `LEFT` pair with `organiser=true`; (e) fire two concurrent `POST /topics/{id}/join`
      requests for the same Participant against two *different* open Topics and assert exactly one succeeds
      while the other receives a friendly rejection (not a raw `500`/constraint-violation error) — proves the
      research.md §5 concurrency fix (depends on: T002–T024 (Foundational and User Story 1 complete))

### Implementation for User Story 3

- [ ] T036 [US3] Modify `src/main/java/net/fabcelhaft/hackathonorganiser/group/GroupService.java`:
      in `addMember`, acquire a `pg_advisory_xact_lock(hashtext('participant-join:' || participantId))` lock
      (mirroring the existing `acquireTopicJoinLock` helper) before touching `group_members`, inside the shared
      `TransactionalOperator`; on success, generate one fresh `actionId` and call
      `auditService.record(JOINED, actor, AuditSubjectType.TOPIC, topicId, <participant display name>, ...)`
      and `auditService.record(JOINED, actor, AuditSubjectType.PARTICIPANT, participantId, <topic name>, ...)`
      sharing that `actionId`; apply the identical treatment to `removeMember` with event `LEFT` (depends on:
      T034, T023 (same file, sequential))
- [ ] T037 [US3] Modify `join`/`leave` in the same `GroupService.java` to accept and forward the `AuditActor`
      parameter into `addMember`/`removeMember` unchanged — `join`/`leave` make no `AuditService` call of their
      own (depends on: T036)
- [ ] T038 [US3] Modify `src/main/java/net/fabcelhaft/hackathonorganiser/topic/TopicJoinService.java`: add an
      `AuditActor actor` parameter to `join` and `leave`, forwarding it unchanged into
      `GroupService.join`/`leave` (depends on: T037)
- [ ] T039 [US3] Modify
      `src/main/java/net/fabcelhaft/hackathonorganiser/topics/TopicJoinController.java`: at the `join`/`leave`
      call sites, construct `new AuditActor(userId, false)` and pass it through (depends on: T038)
- [ ] T040 [US3] Modify
      `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/group/GroupController.java`'s
      `addMember`/`removeMember` call sites (`POST /organiser/groups/{id}/members`,
      `POST /organiser/groups/{id}/members/{participantId}/remove`) to construct
      `new AuditActor(currentUserId, true)` and pass it through (depends on: T036; same file as T024, sequential)

**Checkpoint**: All three user stories are complete and independently functional — self-service and
organiser-driven Topic membership changes are now audit-indistinguishable except for actor and capacity, and
the underlying cross-topic race is closed.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Final validation across all three stories.

- [ ] T041 [P] Walk through all 7 scenarios in `specs/006-audit-trail/quickstart.md` against a running local
      instance (`docker-compose up -d`, `mvn spring-boot:run`) with one Organiser and one Participant session
- [ ] T042 [P] Review every new/modified file's class- and method-level Javadoc for consistency with this
      codebase's existing documentation density (e.g., `GroupService.java`'s class-level Javadoc referencing
      FR numbers and `research.md` sections)
- [ ] T043 Run `mvn test` once more for the full suite (002–005 plus this feature's new tests) and confirm zero
      failures and zero regressions

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **Foundational (Phase 2)**: Depends on Setup completion — BLOCKS all user stories
- **User Story 1 (Phase 3)**: Depends on Foundational completion only
- **User Story 2 (Phase 4)**: Depends on Foundational completion only — does not require User Story 1's
  service-layer wiring (it only reads via `AuditService.findForTopic`/`findForParticipant`, which Foundational
  already provides), though testing it meaningfully benefits from Story 1's entries existing
- **User Story 3 (Phase 5)**: Depends on Foundational **and** User Story 1 (T023 modifies the same
  `GroupService.java` methods T036 extends)
- **Polish (Phase 6)**: Depends on all three user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: No dependency on Story 2 or 3
- **User Story 2 (P1)**: No dependency on Story 1 or 3 for its own implementation, but its independent test is
  most meaningful once Story 1 (or 3) has produced entries to display
- **User Story 3 (P2)**: Builds directly on Story 1's `GroupService.java` changes (same methods,
  `create`/`disband`/`setComplianceOverride` vs. `addMember`/`removeMember`/`join`/`leave`) — implement Story 1
  first

### Within Each User Story

- Tests are written and confirmed failing before implementation tasks
- Service-layer changes (gain `AuditActor`, call `AuditService`) precede their controllers' call-site updates
- Controller call-site updates across different controllers/files are parallelizable once their shared service
  task is done

### Parallel Opportunities

- All Foundational tasks marked [P] (T002–T005) can run in parallel; T006–T010 are sequential (each builds on
  the last)
- Within User Story 1: T011/T012/T013 (three different test files) run in parallel; after T015/T018/T023 land,
  T017/T020/T021/T022 (four different self-service controllers) run in parallel, as do T016/T019/T024 relative
  to each other (different controllers) once their respective service task lands
- Within User Story 2: T025/T026/T027 (tests) run in parallel; T031/T032/T033 (template edits) run in parallel
  once their respective route task lands
- Within User Story 3: only T034 is parallel-safe with itself against other stories; T036–T040 are a strictly
  sequential chain (same files as Story 1, plus a real call-order dependency: controller → service → GroupService)

---

## Parallel Example: User Story 1

```bash
# Launch the three service-layer test extensions together:
Task: "Extend TopicServiceTest.java for audit recording on create/update/propose/updateAsAuthor/approve/reassignAuthor"
Task: "Extend ParticipantServiceTest.java for audit recording on register/submitRegistration/submitSelfEdit/changeStatus/replaceSkills/setCustomFieldValue/selfRevoke/delete"
Task: "Extend GroupServiceTest.java for audit recording on create/disband/setComplianceOverride"

# Once TopicService.java (T015) lands, launch its two controller call-site updates together:
Task: "Wire AuditActor(userId, true) into TopicController's create/update/approve/reassignAuthor call sites"
Task: "Wire AuditActor(userId, false) into TopicSelfServiceController's propose/updateAsAuthor call sites"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (CRITICAL — blocks all stories)
3. Complete Phase 3: User Story 1
4. **STOP and VALIDATE**: Run `AuditRecordingIT` and confirm every Topic/Participant/Group-disband/-override
   mutation produces a correctly-attributed entry
5. This alone delivers the core auditability value even before there's any UI to view it

### Incremental Delivery

1. Setup + Foundational → `AuditService` exists and is tested in isolation
2. Add User Story 1 → every mutation is recorded → validate via T014
3. Add User Story 2 → Organisers can actually see what Story 1 recorded → validate via T025–T027
4. Add User Story 3 → self-service and organiser-driven membership changes become audit-identical, and the
   cross-topic race closes → validate via T035
5. Each story adds value without breaking the previous ones

### Parallel Team Strategy

With multiple developers, once Foundational (Phase 2) is done:

- Developer A: User Story 1 (touches `TopicService`, `ParticipantService`, `GroupService`'s non-membership
  methods, and their controllers)
- Developer B: User Story 2 (templates + two new `GET` routes) — can start in parallel with A, since it only
  depends on Foundational's `AuditService`
- User Story 3 must wait for Developer A's `GroupService.java` changes (T023) to land before starting T036

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- `data-model.md`'s illustrative method names (`setStatus`, `updateSkills`, `updateCustomFields`,
  `findForGroup`) do not exist in the codebase or this design — this file uses the real names throughout
  (`changeStatus`, `replaceSkills`, `setCustomFieldValue`; there is no `findForGroup`, per the resolved
  Clarification that Groups have no audit trail of their own)
- `ParticipantService.selfRegister` and any other method with zero callers in `src/main/java` are out of scope
  — nothing reaches them, so there is nothing to audit
- Commit after each task or logical group; verify tests fail before implementing; stop at any checkpoint to
  validate a story independently
