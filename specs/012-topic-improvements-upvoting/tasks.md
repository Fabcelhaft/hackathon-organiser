---

description: "Task list for feature implementation"
---

# Tasks: Topic Improvements — Upvoting, Author Visibility & Reference IDs

**Input**: Design documents from `/specs/012-topic-improvements-upvoting/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: Constitution Principle V (Test-First Development) is NON-NEGOTIABLE for this project — every phase below writes its failing tests before the implementation that satisfies them, per the Red-Green-Refactor cycle.

**Reactive verification**: Per Constitution Development Workflow #4, any unit test asserting a `Mono`/`Flux` result whose chain composes more than one operator MUST use `StepVerifier`, not a blocking `.block()` — this applies to `TopicUpvoteServiceTest` (T001) and the extensions to `TopicDiscoveryServiceTest`/`TopicServiceTest`/`OrganiserSettingsServiceTest`. Integration tests (`*ManagementIT`/`*ControllerIT`) continue using `WebTestClient`, matching every existing suite in `topics/`, `home/`, and `organiser/settings/`.

**Organization**: Tasks are grouped by user story (P1–P3 from spec.md) to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: Which user story this task belongs to (US1–US3)
- File paths are relative to the repository root

## Path Conventions

Single Maven/Spring Boot project (see [plan.md](plan.md) Project Structure), extending the existing layout —
one new class (`topic/TopicUpvoteService.java`, a `DatabaseClient`-managed pure-association service with no
entity class, mirroring how `topic_skills` has none) plus one new controller
(`topics/TopicUpvoteController.java`), and behavioral/template extensions across `topic/`, `topics/`,
`organisersettings/`, `organiser/settings/`, `home/`, and their template counterparts.

**⚠️ Shared-file note**: `src/main/resources/templates/home/index.html` and `templates/topics/overview.html` are
each touched by both US1 (upvote control/count) and US2 (reference number badge); `templates/topics/detail.html`
is touched by all three — US1 (upvote control/count), US2 (reference number), and US3 (a new author row — see
below). US3 does **not** touch `templates/topics/overview.html` (that page already shows an author column, per
feature 005; the gap this story closes is that the single-Topic Details view shows none today). These are
additive edits to different regions of the same files — sequential story execution has no conflict; parallel
story execution must coordinate on those files (US1 first is recommended, since it is the MVP anyway).

---

## Phase 1: Setup

**Purpose**: None. This feature adds no new dependency — Spring Boot WebFlux, Data R2DBC, Thymeleaf, and the
existing test stack (JUnit 5, Mockito, `WebTestClient`, Testcontainers) already cover everything needed
(plan.md Technical Context). Proceed directly to Foundational.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: None. The three user stories touch disjoint production concepts — US1 adds a new table/service/
controller plus a Home Page sort tiebreak, US2 adds one column/sequence to the existing `topics` table and one
line inside `TopicService.approve`, US3 adds one new field/row to the Topic Details view only — so there is no cross-story
prerequisite beyond each story's own schema task.

**Checkpoint**: Any of US1 / US2 / US3 may begin immediately.

---

## Phase 3: User Story 1 - Upvote a Topic to Signal Interest (Priority: P1) 🎯 MVP

**Goal**: Any authenticated user can cast one upvote per Topic, withdraw it later, and see only the total count
(never who cast it) wherever Topics are listed; an Organiser can turn the whole feature on/off; while on, upvotes
also break ties in the Home Page's existing fullness-first ordering.

**Independent Test**: As User A, upvote a Topic on the Home Page and confirm the count increases by one and the
control now offers to withdraw; as User B, upvote the same Topic and confirm the count increases again, with
neither user's identity visible anywhere, including the Organiser Audit Trail; withdraw User A's upvote and
confirm the count decreases by one; disable **Topic upvoting enabled** in Organiser Settings and confirm every
upvote control and count disappears everywhere without deleting the underlying data, then re-enable and confirm
it all reappears unchanged.

### Tests for User Story 1 ⚠️ write first, confirm they fail

- [X] T001 [P] [US1] Create `src/test/java/net/fabcelhaft/hackathonorganiser/topic/TopicUpvoteServiceTest.java` with failing `StepVerifier`-based unit tests (mocked `DatabaseClient`, mirroring `TopicServiceTest`'s `verify(databaseClient).sql(contains(...))` style): `upvote(topicId, userId)` issues an `INSERT INTO topic_upvotes` when no row exists for that pair; issues no `INSERT` (pre-check finds one) when the pair already has an active upvote, resolving as success either way; a lost-race duplicate-insert failure (simulate the mocked execute chain throwing `DataIntegrityViolationException`) is caught and also resolves as success — never propagated as an error; `withdraw(topicId, userId)` issues a `DELETE FROM topic_upvotes ... WHERE topic_id = :t AND user_id = :u` and resolves as success even when zero rows are affected; `countsFor(Set<UUID>)` issues one grouped `COUNT`/`GROUP BY topic_id` query and returns a `Map<UUID, Integer>`; `viewerUpvotedTopicIds(Set<UUID>, UUID)` issues one query and returns the subset of ids the viewer has upvoted (data-model.md "New Entity: Topic Upvote", research.md §2–§3)
- [X] T002 [P] [US1] Extend `src/test/java/net/fabcelhaft/hackathonorganiser/topic/TopicDiscoveryServiceTest.java` with failing cases: `findOpenTopicsForHomePage` orders two not-full, equally-full Topics by upvote count descending when `OrganiserSettings.topicUpvotingEnabled` is true, and falls back to the pre-existing tiebreak (unaffected relative order) when it is false — additionally asserting `TopicUpvoteService` is never invoked in the disabled branch (verify no interaction); every `OpenTopicRow`/`OverviewRow`/`TopicDetailView` produced carries the correct `upvoteCount` and `viewerHasUpvoted` for the acting viewer, including the disabled-feature case where both are `0`/`false` regardless of underlying data (FR-005a, data-model.md "Read-Model Extensions", research.md §6)
- [X] T003 [P] [US1] Extend `src/test/java/net/fabcelhaft/hackathonorganiser/organisersettings/OrganiserSettingsServiceTest.java` with failing cases mirroring the existing `topicJoiningEnabled` tests: `update(...)` with a non-null `topicUpvotingEnabled` sets `OrganiserSettings.topicUpvotingEnabled`; a `null` value leaves the current stored value unchanged (data-model.md "Modified Entity: Organiser Settings")
- [X] T004 [US1] Create `src/test/java/net/fabcelhaft/hackathonorganiser/topics/TopicUpvoteManagementIT.java` with failing `WebTestClient` cases: `POST /topics/{id}/upvote` as an authenticated user returns 303 back to the referring page and the Topic's count increases by one; a second identical request from the same user leaves the count unchanged; `POST /topics/{id}/unupvote` decreases it by one and is a no-op if the user had no active upvote; a Topic's own author can upvote their own Topic (Clarifications); an unknown or invisible (Pending, non-author/non-organiser viewer) Topic id returns 404 for both routes; with **Topic upvoting enabled** turned off (via `OrganiserSettingsService`/direct settings update in test setup), both routes return 404 and no upvote control/count appears in any of `GET /`, `GET /topics/overview`, `GET /topics/{id}`; **then, still in the same test, turn the toggle back on and confirm every previously-recorded count and each user's own active/inactive upvote state reappears exactly as it was before disabling** (FR-008, Story 1 Acceptance Scenario 7's second clause — the restore-on-re-enable half, not just the hide-on-disable half); after several upvotes/withdrawals by two different test users, `GET /organiser/topics/{id}/audit` (as an Organiser) shows **no** new entries attributable to those actions — confirming the anonymity guarantee holds even for the one role with audit-log access (FR-001–FR-004, FR-006–FR-008, contracts/topic-upvote-action.md)
- [X] T005 [P] [US1] Extend `src/test/java/net/fabcelhaft/hackathonorganiser/home/HomeControllerIT.java` with failing cases: a Home Page row shows its Topic's upvote count and an Upvote/Withdraw control reflecting the viewer's own state; two equally-full not-full Topics render with the higher-upvote one first; with the toggle off, no upvote control or count renders for any row and the equally-full ordering reverts to pre-feature behavior (Story 1 Acceptance Scenario 6, SC-008)
- [X] T006 [P] [US1] Extend `src/test/java/net/fabcelhaft/hackathonorganiser/topics/TopicOverviewManagementIT.java` with failing cases: each row on `GET /topics/overview` shows its upvote count and the viewer's own control state; toggling the feature off removes both from every row
- [X] T007 [P] [US1] Extend `src/test/java/net/fabcelhaft/hackathonorganiser/topics/TopicDetailManagementIT.java` with failing cases: `GET /topics/{id}` shows the upvote count and the viewer's own control state; toggling the feature off removes both
- [X] T008 [P] [US1] Extend `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/settings/SettingsManagementIT.java` with a failing case: submitting the settings form with the new `topic_upvoting_enabled` checkbox unchecked persists `false`, and checked persists `true`, mirroring the existing `topic_joining_enabled` case

### Implementation for User Story 1

- [X] T009 [P] [US1] Append to `src/main/resources/schema.sql`: the `topic_upvotes` table exactly as specified in [data-model.md](data-model.md) "New Entity: Topic Upvote" (composite PK, no independent UUID), and `ALTER TABLE organiser_settings ADD COLUMN IF NOT EXISTS topic_upvoting_enabled boolean NOT NULL DEFAULT true`, each with the explanatory comment style already used throughout this file (research.md §1, §7)
- [X] T010 [P] [US1] Add a `topicUpvotingEnabled` boolean field with getter/setter to `src/main/java/net/fabcelhaft/hackathonorganiser/organisersettings/OrganiserSettings.java`, following the exact style of the adjacent `topicJoiningEnabled` field
- [X] T011 [US1] Add a `Boolean topicUpvotingEnabled` parameter to `update(...)` in `src/main/java/net/fabcelhaft/hackathonorganiser/organisersettings/OrganiserSettingsService.java`, applied with the same null-means-unchanged `if (topicUpvotingEnabled != null) { settings.setTopicUpvotingEnabled(...); }` pattern as `topicJoiningEnabled` (depends on T010)
- [X] T012 [US1] In `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/settings/OrganiserSettingsController.java`, pass `checkboxValue(form, "topic_upvoting_enabled")` as the new final argument to `organiserSettingsService.update(...)` (depends on T011)
- [X] T013 [P] [US1] In `src/main/resources/templates/organiser/settings/form.html`, add a `topic_upvoting_enabled` checkbox `<fieldset>` mirroring `topic_joining_enabled`'s markup exactly (hidden `false` input, checkbox `true`, `th:checked="${settings.topicUpvotingEnabled}"`, on/off explanatory `<p>`s) (depends on T010)
- [X] T014 [US1] Create `src/main/java/net/fabcelhaft/hackathonorganiser/topic/TopicUpvoteService.java`: `upvote(UUID topicId, UUID userId)`, `withdraw(UUID topicId, UUID userId)`, `countsFor(Set<UUID> topicIds)`, `viewerUpvotedTopicIds(Set<UUID> topicIds, UUID viewerUserId)` — `DatabaseClient`-backed exactly as specified in [data-model.md](data-model.md) "New Entity: Topic Upvote", with the pre-check-plus-constraint-backstop concurrency shape from research.md §3 (depends on T009)
- [X] T015 [US1] In `src/main/java/net/fabcelhaft/hackathonorganiser/topic/TopicDiscoveryService.java`: add `upvoteCount`/`viewerHasUpvoted` fields to the `OpenTopicRow`, `OverviewRow`, and `TopicDetailView` records; inject `TopicUpvoteService`; in `findOpenTopicsForHomePage`, `findTopicOverview`, and `findTopicDetail`, bulk-load counts/viewer-upvoted ids only when `settings.isTopicUpvotingEnabled()` (else populate `0`/`false` for every row, per data-model.md "Read-Model Extensions"); in `selectHomePageRows`, extend the fullness `Comparator` with `.thenComparing(upvoteCount, Comparator.reverseOrder())` gated by the same toggle check, exactly as specified in data-model.md "Home Page Ordering" and research.md §6 (depends on T014)
- [X] T016 [US1] Create `src/main/java/net/fabcelhaft/hackathonorganiser/topics/TopicUpvoteController.java` with `POST /topics/{id}/upvote` and `POST /topics/{id}/unupvote`, mirroring `TopicJoinController`'s `Mono<Rendering>`/redirect-to-referrer shape: gate on `OrganiserSettings.topicUpvotingEnabled` (404 if off) and Topic visibility (`TopicService.isVisibleTo`, 404 if invisible/unknown), then delegate to `TopicUpvoteService` (contracts/topic-upvote-action.md) (depends on T014)
- [X] T017 [US1] In `src/main/java/net/fabcelhaft/hackathonorganiser/home/HomeController.java`, add `.modelAttribute("upvotingEnabled", settings.isTopicUpvotingEnabled())` to the `home` route's `Rendering` (the local `settings` variable is already in scope) (depends on T010)
- [X] T018 [US1] In `src/main/java/net/fabcelhaft/hackathonorganiser/topics/TopicOverviewController.java`, add `.modelAttribute("upvotingEnabled", tuple.getT3().isTopicUpvotingEnabled())` to the `overview` route's `Rendering` (the existing `organiserSettingsService.current()` zip element) (depends on T010)
- [X] T019 [US1] In `src/main/java/net/fabcelhaft/hackathonorganiser/topics/TopicSelfServiceController.java`, add `.modelAttribute("upvotingEnabled", tuple.getT2().isTopicUpvotingEnabled())` to the `/{id}` detail route's `Rendering`, mirroring the existing `teamsLinksEnabled` attribute built from the same tuple element (depends on T010)
- [X] T020 [P] [US1] In `src/main/resources/templates/home/index.html`, add an upvote count + toggling Upvote/Withdraw `<form method="post">` control to each Home Page row, rendered only `th:if="${upvotingEnabled}"`, posting to `/topics/{id}/upvote` or `/topics/{id}/unupvote` depending on `row.viewerHasUpvoted()` (depends on T015, T016, T017)
- [X] T021 [P] [US1] Apply the equivalent upvote count + control to each row in `src/main/resources/templates/topics/overview.html`, gated the same way (depends on T015, T016, T018)
- [X] T022 [P] [US1] Apply the equivalent upvote count + control to `src/main/resources/templates/topics/detail.html`, gated the same way (depends on T015, T016, T019)

**Checkpoint**: At this point, User Story 1 should be fully functional and testable independently — this is the MVP.

---

## Phase 4: User Story 2 - Reference a Topic by a Stable Numeric ID (Priority: P2)

**Goal**: An Approved Topic carries a permanent, unique numeric reference ID visible everywhere it is shown; a
Pending Topic shows none; every Topic already Approved before this feature shipped gets one automatically on
the next startup.

**Independent Test**: Approve a Pending Topic and confirm a reference number now appears on the Home Page,
Topic Overview, and Topic Details; approve a second Topic and confirm it gets a different, higher number;
confirm a still-Pending Topic shows no number anywhere.

### Tests for User Story 2 ⚠️ write first, confirm they fail

- [X] T023 [P] [US2] Extend `src/test/java/net/fabcelhaft/hackathonorganiser/topic/TopicServiceTest.java` with failing cases: `approve(...)` on a Pending Topic sets a non-null `referenceNumber` (mock the `nextval('topics_reference_number_seq')` query via `DatabaseClient`, mirroring this test file's existing `databaseClient` mock setup); `approve(...)` on an already-Approved Topic is the existing no-op and does **not** re-assign or change `referenceNumber`; two sequential `approve(...)` calls on different Topics receive two different, increasing numbers (data-model.md "Modified Entity: Topic", contracts/topic-reference-number.md)
- [X] T024 [P] [US2] Extend `src/test/java/net/fabcelhaft/hackathonorganiser/topics/TopicOverviewManagementIT.java` with failing cases: a Pending Topic's row shows no reference number; an Approved Topic's row shows one; two different Approved Topics never show the same number
- [X] T025 [P] [US2] Extend `src/test/java/net/fabcelhaft/hackathonorganiser/topics/TopicDetailManagementIT.java` with a failing case: `GET /topics/{id}` renders the reference number for an Approved Topic and renders nothing for that field on a Pending one
- [X] T026 [P] [US2] Extend `src/test/java/net/fabcelhaft/hackathonorganiser/home/HomeControllerIT.java` with a failing case: an Approved Topic's Home Page row renders its reference number

### Implementation for User Story 2

- [X] T027 [P] [US2] Append to `src/main/resources/schema.sql`: `topics.reference_number` column, `topics_reference_number_key` partial unique index, `topics_reference_number_seq` sequence, and the one-time idempotent backfill statement (CTE + `UPDATE` + `setval`) exactly as specified in [research.md](research.md) §5 / [data-model.md](data-model.md) "Modified Entity: Topic", with the same explanatory-comment convention this file already uses for one-time backfills (e.g. the feature-008 `content_pages.context` migration comment)
- [X] T028 [US2] Add a `referenceNumber` (nullable `Integer`) field with getter/setter to `src/main/java/net/fabcelhaft/hackathonorganiser/topic/Topic.java` (depends on T027)
- [X] T029 [US2] In `approve(UUID topicId, AuditActor actor)` in `src/main/java/net/fabcelhaft/hackathonorganiser/topic/TopicService.java`, set `topic.setReferenceNumber(...)` from `SELECT nextval('topics_reference_number_seq')` (via the class's existing `databaseClient` field) in the same branch that flips `approvalStatus` to `APPROVED`, before the save — leaving the existing "already Approved → no-op" early return untouched (depends on T027, T028)
- [X] T030 [P] [US2] In `src/main/resources/templates/home/index.html`, render `topic.referenceNumber` (e.g. `#42`) when non-null, alongside the Topic's other details, rendering nothing when null (depends on T028)
- [X] T031 [P] [US2] Apply the equivalent reference-number rendering to `src/main/resources/templates/topics/overview.html` (depends on T028)
- [X] T032 [P] [US2] Apply the equivalent reference-number rendering to `src/main/resources/templates/topics/detail.html` (depends on T028)

**Checkpoint**: User Stories 1 AND 2 should both work independently.

---

## Phase 5: User Story 3 - See Who Authored a Topic on Its Details View (Priority: P3)

**Goal**: A Topic's own Details view (`/topics/{id}`, the single-Topic page) shows who authored it, as one of
the first pieces of information on the page — a field that view does not show today. The Topic Overview
(all-Topics list) already shows an author column (feature 005) and is **not** touched by this story.

**Independent Test**: Open any Topic's own Details view and confirm its author appears near the top of the
Topic Info table, without needing to consult the Topic Overview.

### Tests for User Story 3 ⚠️ write first, confirm it fails

- [X] T033 [US3] Extend `src/test/java/net/fabcelhaft/hackathonorganiser/topics/TopicDetailManagementIT.java` with a failing case: `GET /topics/{id}` renders the Topic's author display name as the **first** row of the "Topic Info" table (before "Needed Skills"), for both the Topic's own author viewing it and any other viewer; assert this is a genuinely new assertion (the current response contains no author name anywhere) rather than a reordering of an existing row (FR-014, SC-007)

### Implementation for User Story 3

- [X] T034 [US3] In `src/main/java/net/fabcelhaft/hackathonorganiser/topic/TopicDiscoveryService.java`, add an `authorDisplayName` field to the `TopicDetailView` record and populate it in `findTopicDetail` using the existing `authorDisplayName(UUID)` helper (already used by `buildOverviewRow` for `OverviewRow`) — no new query. Then, in `src/main/resources/templates/topics/detail.html`, add a new first `<tr>` to the "Topic Info" `<table>`'s `<tbody>` (immediately before the existing "Needed Skills" row) showing `detail.authorDisplayName`, mirroring the placement "Creator" already has as the first `<dl>` row on the Organiser-facing `organiser/topics/detail.html` (data-model.md "Topic Details Author Display")

**Checkpoint**: All user stories should now be independently functional.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T035 [P] Update `src/test/java/net/fabcelhaft/hackathonorganiser/a11y/HomepageAccessibilityIT.java` for the new upvote control (an accessible name distinguishing "Upvote" from "Withdraw upvote" per Topic, not a bare icon) and the reference-number text, confirming the axe scan stays clean
- [X] T036 [P] Update `src/test/java/net/fabcelhaft/hackathonorganiser/a11y/TopicOverviewAccessibilityIT.java` for the upvote control/reference-number additions (US1/US2 only — this page's author column is unchanged, US3), confirming the axe scan stays clean
- [X] T037 [P] Update `src/test/java/net/fabcelhaft/hackathonorganiser/a11y/TopicDetailAccessibilityIT.java` for the upvote control/reference-number additions (US1/US2) and the new author row (US3), confirming the axe scan stays clean
- [X] T038 Run the full suite with `mvn verify` from the repository root and confirm every pre-existing test still passes, paying particular attention to `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/audit/*IT.java` (the audit list must show no new entries for upvote actions) and any test asserting the Topic Details view's previous "Topic Info" row order (must now expect the new leading author row)
- [X] T039 Walk the manual validation steps in [quickstart.md](quickstart.md) against the running application, including section 5 ("Pre-existing Approved Topics get a reference number on upgrade") — this backfill has no automated test (consistent with this codebase's existing precedent for one-time `schema.sql` migrations, e.g. feature 008's `content_pages.context` change, which was likewise validated manually) — required by Constitution Development Workflow #3 before this feature is considered complete

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: Empty — no dependencies.
- **Foundational (Phase 2)**: Empty by design (see that phase). Blocks nothing.
- **User Stories (Phases 3–5)**: None depend on Phase 1/2 content beyond "the project builds." All three may run
  in parallel, subject to the shared-template note at the top of this file.
- **Polish (Phase 6)**: T035–T037 depend on all three stories' template changes; T038/T039 depend on every story
  intended for the release.

### User Story Dependencies

- **User Story 1 (P1)**: Fully independent. The MVP — ships upvoting end-to-end on its own.
- **User Story 2 (P2)**: Fully independent of US1 and US3 (different column, different service method).
- **User Story 3 (P3)**: Fully independent of US1 and US2, though it edits the same file
  (`topics/detail.html`) US1/US2 also touch — see the shared-file note.

### Within Each User Story

- Tests are written and confirmed failing before their implementation tasks.
- US1: schema (T009) → entity/settings fields (T010) → service/controller plumbing (T011–T012, T014, T016) →
  controller model attributes (T017–T019) → templates (T020–T022). `TopicDiscoveryService` (T015) depends on
  `TopicUpvoteService` (T014) existing first.
- US2: schema (T027) → `Topic` field (T028) → `TopicService.approve` (T029) → templates (T030–T032).
- US3: read-model field + template (T034, one task spanning both since it's a small, single-field change).

### Parallel Opportunities

- T001–T003 (US1 unit tests) touch three different files and can be written in parallel; T004–T008 (US1
  integration tests) touch five different files, likewise parallel.
- T009, T010 are different files and can proceed in parallel once T001–T008 are red.
- T013 (settings template) can proceed in parallel with T011/T012/T014 once T010 has settled the field name.
- T020–T022 (US1 templates) are three different files, all parallel once T015/T016 have settled the model
  attribute names and route paths.
- T023–T026 (US2 tests) are four different files, all parallel.
- T030–T032 (US2 templates) are three different files, all parallel once T028 exists.
- T035–T037 are three different test files.

---

## Parallel Example: User Story 1 tests

```bash
# Launch all US1 test-writing tasks together (before any US1 implementation):
Task: "Create failing TopicUpvoteServiceTest.java"
Task: "Add failing Home Page tiebreak/row cases to TopicDiscoveryServiceTest.java"
Task: "Add failing topicUpvotingEnabled cases to OrganiserSettingsServiceTest.java"
Task: "Create failing TopicUpvoteManagementIT.java"
Task: "Add failing upvote cases to HomeControllerIT.java"
Task: "Add failing upvote cases to TopicOverviewManagementIT.java"
Task: "Add failing upvote cases to TopicDetailManagementIT.java"
Task: "Add failing topic_upvoting_enabled case to SettingsManagementIT.java"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 3 (T001–T022).
2. **STOP and VALIDATE**: upvoting works end-to-end on Home Page, Topic Overview, and Topic Details; the admin
   toggle hides/restores it instance-wide; no upvoter identity is ever exposed, including in the Audit Trail;
   `mvn verify` is green.
3. Deploy/demo — this alone delivers the largest and most user-facing piece of the original request.

### Incremental Delivery

1. US1 (upvoting) → demo (MVP).
2. Add US2 (reference numbers) → demo. Independent value; no interaction with US1.
3. Add US3 (author on Topic Details) → demo. Smallest, lowest-risk slice.
4. Finish with Phase 6 polish before merging.

### Parallel Team Strategy

1. One developer takes US1 (T001–T022) — the largest slice and the MVP.
2. In parallel, a second developer takes US2 (T023–T032) — touches `Topic`/`TopicService`, disjoint from US1's
   new `TopicUpvoteService`/`TopicUpvoteController`.
3. US3 (T033–T034) is a half-day slice anyone can pick up once US1's `topics/detail.html` edits have landed
   (or coordinate directly if done in parallel — see the shared-file note).

---

## Notes

- [P] tasks = different files, no dependency on an incomplete task.
- [Story] label maps each task to its user story for traceability.
- Verify tests fail before implementing — Constitution Principle V is non-negotiable here.
- Commit after each task or logical group.
- No `AuditEntry` is ever recorded for an upvote/withdraw action (research.md §1) — T004 exists specifically to
  assert that absence, not merely the presence of the expected count change.
- `TopicUpvoteService` is the only place in this feature that touches `topic_upvotes`; no repository or entity
  class is introduced for it, matching the existing `topic_skills`/`participant_skills` convention (no such
  class exists for those tables either).
- The Phase 4 (US2) backfill (T027) is deliberately untested by an automated test — see T039's note — but is
  still exercised implicitly by every test in T023–T026, since a freshly-started test database has zero
  pre-existing Approved Topics for the backfill to act on (it only ever touches rows created before this
  feature's schema existed).
