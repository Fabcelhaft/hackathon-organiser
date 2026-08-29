---

description: "Task list for feature implementation"
---

# Tasks: Homepage Overview, Self-Service Registration & Topics

**Input**: Design documents from `/specs/003-homepage-overview/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: Constitution Principle V (Test-First Development) is NON-NEGOTIABLE for this project — every task list below includes failing tests written before their implementation, per the Red-Green-Refactor cycle.

**Reactive verification**: Per Constitution Development Workflow #4, unit tests exercising a service's reactive
chain (any test asserting a `Mono`/`Flux` result where the chain composes more than one operator) MUST use
`StepVerifier`, not a blocking `.block()` call. This applies to T002, T003, T011, T012, T022, T051 below.
`MarkdownRenderer` (T035) is a plain synchronous `String → String` function with no reactive chain — its test
uses ordinary JUnit assertions, not `StepVerifier`.

**Organization**: Tasks are grouped by user story (P1–P3 from spec.md) to enable independent implementation and
testing of each story. Within each story, code is grouped by business concept, matching plan.md's package
layout (audience-scoped web packages — `home`, `info`, `topics`, `organiser.*` — plus domain packages
`organisersettings`, `content`, and extensions to 002's `topic`/`participant`/`group`).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: Which user story this task belongs to (US1–US7)
- File paths are relative to the repository root

## Path Conventions

Single Maven/Spring Boot project (see [plan.md](plan.md) Project Structure), extending 002's layout:
- Main code: `src/main/java/net/fabcelhaft/hackathonorganiser/` — new domain packages `organisersettings/`,
  `content/`; extended domain packages `topic/`, `participant/`, `group/`; new audience-scoped web packages
  `home/`, `info/`, `topics/`, `web/` (cross-cutting: `CurrentUserModelAdvice`, `ContentImageStreamController`);
  new organiser web packages `organiser/settings/`, `organiser/content/`; extended `organiser/topic/`
- Templates: `src/main/resources/templates/fragments/` (new shared layout), `home/`, `info/`, `topics/`,
  `organiser/settings/`, `organiser/content-pages/`, `organiser/content-images/`; extended
  `organiser/topics/`, `organiser/fragments/`
- Static assets: `src/main/resources/static/css/app.css` (new — Pico CSS overrides only, Constitution IV)
- Tests: `src/test/java/net/fabcelhaft/hackathonorganiser/`, mirroring the same packages, plus a new
  `a11y/` package for the Playwright + axe-core suite (research.md §9)

Every new UUIDv7 primary key uses PostgreSQL 18's native `uuidv7()` column `DEFAULT` (002's established
convention, data-model.md) — no application-side ID generation anywhere in this task list.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Add the new dependencies this feature's design decisions rely on.

**⚠️ CRITICAL**: No foundational or user-story work can begin until this phase is complete.

- [X] T001 Add `org.commonmark:commonmark:0.24.0` and
      `com.googlecode.owasp-java-html-sanitizer:owasp-java-html-sanitizer:20240325.1` (main scope), and
      `com.microsoft.playwright:playwright:1.52.0` + `com.deque.html.axe-core:playwright:4.10.1` (test scope)
      to `pom.xml` (research.md §1, §9); confirm via `mvn dependency:tree` that none pulls in `spring-webmvc`
      transitively (Constitution I gate)

**Checkpoint**: Setup complete — Foundational phase can now begin.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Infrastructure genuinely shared by every later user story: the Organiser Settings singleton (read
by self-registration, self-revocation, and topic-approval gating across US1–US4) and the role-aware shared
navigation (used by every template this feature adds, across every story).

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### Tests (write first, confirm they fail)

- [X] T002 [P] Write failing unit tests for `OrganiserSettingsService` (reads the seeded singleton row via
      `findBySingletonTrue()`; updates any combination of the three toggles; verified via `StepVerifier`) in
      `src/test/java/net/fabcelhaft/hackathonorganiser/organisersettings/OrganiserSettingsServiceTest.java`
- [X] T003 [P] Write failing unit tests for `CurrentUserModelAdvice` (resolves `currentUser`/`isOrganiser` from
      an authenticated `HackathonOidcUser` in the reactive `SecurityContext`; `isOrganiser` is `false` for a
      non-Organiser; verified via `StepVerifier`) in
      `src/test/java/net/fabcelhaft/hackathonorganiser/web/CurrentUserModelAdviceTest.java`

### Implementation

- [X] T004 Add `organiser_settings` table DDL (`id uuid PRIMARY KEY DEFAULT uuidv7()`, `singleton boolean NOT
      NULL DEFAULT true` with a unique index, the three toggle columns, `updated_at`) plus the
      `INSERT ... ON CONFLICT (singleton) DO NOTHING` seed statement to `src/main/resources/schema.sql`
      (data-model.md "Schema additions", research.md §4)
- [X] T005 [P] Create the `OrganiserSettings` entity (`id`, `singleton`, `selfRegistrationEnabled`,
      `selfRevocationEnabled`, `topicApprovalRequired`, `updatedAt`) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/organisersettings/OrganiserSettings.java`
- [X] T006 [P] Create `OrganiserSettingsRepository extends ReactiveCrudRepository<OrganiserSettings, UUID>`
      with a derived `Mono<OrganiserSettings> findBySingletonTrue()` in
      `src/main/java/net/fabcelhaft/hackathonorganiser/organisersettings/OrganiserSettingsRepository.java`
- [X] T007 Implement `OrganiserSettingsService` (`current()` read; `update(...)` accepting any combination of
      the three toggles) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/organisersettings/OrganiserSettingsService.java`,
      depends on T005, T006 — makes T002 pass
- [X] T008 [P] Implement `CurrentUserModelAdvice` (`@ControllerAdvice` with a reactive `@ModelAttribute`
      resolving `currentUser`/`isOrganiser` from `HackathonOidcUser` in the exchange's `SecurityContext`,
      research.md §7) in `src/main/java/net/fabcelhaft/hackathonorganiser/web/CurrentUserModelAdvice.java` —
      makes T003 pass
- [X] T009 Create the shared layout fragment at `src/main/resources/templates/fragments/layout.html` (brand;
      an Organiser nav link rendered only when `isOrganiser` is true, with an icon plus accessible text label
      per FR-008a — never color alone; an Info nav link, initially pointing at `/info` before that route exists
      in US5; a main content insertion point; a `role="status" aria-live="polite"` flash-message region for
      FR-033), depends on T008

**Checkpoint**: Foundation ready — user story implementation can now begin in priority order.

---

## Phase 3: User Story 1 - Homepage Shows Registration Status & Self-Service Actions (Priority: P1) 🎯 MVP

**Goal**: An authenticated user sees their Participant status, assigned Group/Topic if any, and a working
Register-or-Revoke action, gated by the current Organiser Settings.

**Independent Test**: Log in as a user with no Participant record, confirm "Register" is shown, click it,
confirm the homepage now shows Active status and "Revoke Registration" instead.

### Tests for User Story 1 ⚠️ write first, confirm they fail

- [X] T010 [P] [US1] Write failing `WebTestClient` integration tests covering: `GET /` with no Participant
      record shows Register and no Revoke; with an Active record shows status + assigned Group/Topic + Revoke;
      `POST /register` creates an Active record immediately with no form (FR-003) and is idempotent on a
      double-submit (Edge Cases); `POST /register` for a caller whose *existing* Participant record is
      `REVOKED` reactivates that same record to `ACTIVE` (not an error, not a new record) and the homepage
      reflects Active status immediately (FR-007); `POST /revoke` sets Revoked, shows Register again, and
      removes current Group membership while preserving history (FR-007a); registering again after that
      revoke reactivates the same record and does **not** restore the removed Group membership (FR-007a is a
      one-way effect); both actions rejected when their setting is disabled regardless of what the page showed
      at load — per [contracts/registration-and-status.md](contracts/registration-and-status.md) — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/home/HomeControllerIT.java`
- [X] T011 [P] [US1] Write failing unit tests for `ParticipantService.selfRegister`/`selfRevoke` (rejects when
      the relevant `OrganiserSettings` toggle is disabled; no existing record → creates one with `ACTIVE`;
      existing record already `ACTIVE` → no-op, record unchanged, not an error; existing record `REVOKED` (or
      any non-`ACTIVE` status) → that same record's `status` is updated to `ACTIVE`, asserting no second row is
      inserted (FR-007) — this is the case 002's `register()` gets wrong by rejecting it outright, so assert
      `selfRegister` does NOT delegate to `register()` unmodified; revoke sets `REVOKED` and, if a current Group
      exists, removes that membership via `GroupService`; no-op-with-no-Group-change when the Participant has
      no current Group; verified via `StepVerifier`) in
      `src/test/java/net/fabcelhaft/hackathonorganiser/participant/ParticipantServiceTest.java` (extends the
      existing file)
- [X] T012 [P] [US1] Write failing unit test for `GroupService.findActiveGroupForParticipant` (returns the
      Participant's current active Group; empty if none; verified via `StepVerifier`) in
      `src/test/java/net/fabcelhaft/hackathonorganiser/group/GroupServiceTest.java` (extends the existing file)

### Implementation for User Story 1

- [X] T013 [US1] Add a public `findActiveGroupForParticipant(UUID participantId)` to `GroupService`
      (research.md §10) in `src/main/java/net/fabcelhaft/hackathonorganiser/group/GroupService.java` — makes
      T012 pass
- [X] T014 [US1] Implement `ParticipantService.selfRegister(UUID userId)` and `selfRevoke(UUID participantId)`
      (re-reading `OrganiserSettingsService.current()` on every call, FR-006). `selfRegister` is a new method,
      not a thin wrapper around 002's `register(UUID userId)` (that method rejects any pre-existing record
      outright — correct for its own organiser-driven caller, wrong here): branch on
      `participantRepository.findByUserId(userId)` — empty → save a new `ACTIVE` record; found with `status ==
      ACTIVE` → return it unchanged (no-op); found with any other status → set that same record's `status` to
      `ACTIVE` and save (update, not insert) — data-model.md "Participant", FR-007. `selfRevoke` composes
      `GroupService.findActiveGroupForParticipant` + `removeMember`, FR-007a. In
      `src/main/java/net/fabcelhaft/hackathonorganiser/participant/ParticipantService.java`, depends on T007,
      T013 — makes T011 pass
- [X] T015 [US1] Implement `HomeController` (`GET /`, `POST /register`, `POST /revoke`) per
      [contracts/registration-and-status.md](contracts/registration-and-status.md) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/home/HomeController.java`, depends on T014, T009 — makes
      T010 pass; the right-column content area renders a simple static placeholder for now, replaced by real
      Content Page rendering in US5
- [X] T016 [P] [US1] Create the Thymeleaf template `src/main/resources/templates/home/index.html` extending
      `fragments/layout.html`: two-column layout that stacks status → topics → content on narrow viewports
      (FR-001a), each area clearly labeled (FR-001a); status + assigned Group/Topic display with the
      explanatory line for Register (FR-003a); the Register/Revoke actions and every control in the `<dialog>`
      given a programmatically associated label (FR-032); a native `<dialog>` Revoke confirmation stating the
      Group-removal consequence, opened/closed via a few lines of vanilla JS that also returns focus to the
      triggering button (FR-004a, FR-035, research.md §8); the `aria-live` flash-message region focused on load
      after a redirect
- [X] T017 [P] [US1] Create `src/main/resources/static/css/app.css` (the two-column responsive grid; a
      `.visually-hidden` utility class; a reusable status-badge style conveying state via text/icon, never
      color alone, FR-034) and link it from `fragments/layout.html`, depends on T009

**Checkpoint**: User Story 1 is fully functional and testable independently —
`mvn verify -Dit.test=HomeControllerIT`.

---

## Phase 4: User Story 2 - Organiser Controls Registration & Revocation Availability (Priority: P2)

**Goal**: An Organiser independently enables/disables self-registration and self-revocation from a dedicated
settings area, taking effect immediately for all users.

**Independent Test**: Disable self-registration, confirm an unregistered user's homepage no longer shows
"Register"; re-enable and confirm it reappears. Same for revocation.

**Depends on**: User Story 1 (its `ParticipantService` gating logic is what these toggles actually control).

### Tests for User Story 2 ⚠️ write first, confirm they fail

- [X] T018 [P] [US2] Write failing `WebTestClient` integration tests: an Organiser disabling/enabling
      self-registration and self-revocation via `/organiser/settings` changes what a subsequent `GET /`/
      `POST /register`/`POST /revoke` allows; each toggle displays its current on/off state plus a
      plain-language effect sentence (FR-005a); each toggle has a programmatically associated label (FR-032); a
      non-Organiser is denied `/organiser/settings`; the Organiser nav link from T009 is visible only to
      `ROLE_ORGANISER` (FR-008) — per
      [contracts/organiser-settings.md](contracts/organiser-settings.md) — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/settings/SettingsManagementIT.java`

### Implementation for User Story 2

- [X] T019 [US2] Implement `OrganiserSettingsController` (`GET`/`POST /organiser/settings` for the
      self-registration and self-revocation toggles, each with a plain-language effect sentence, FR-005a) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/settings/OrganiserSettingsController.java`,
      depends on T007 — makes T018 pass (the topic-approval toggle is added in US4, T031)
- [X] T020 [P] [US2] Create `src/main/resources/templates/organiser/settings/form.html` (toggle controls each
      with a programmatically associated label — FR-032 — and its plain-language effect text, plus a save
      confirmation announced via the flash region) extending `fragments/layout.html`

**Checkpoint**: User Stories 1 and 2 both work independently —
`mvn verify -Dit.test=HomeControllerIT,SettingsManagementIT`.

---

## Phase 5: User Story 3 - Browse and Propose Topics (Priority: P2)

**Goal**: Any registered Participant browses topics (with author contact info), proposes new ones, and edits
their own.

**Independent Test**: Propose a Topic with a name/description, confirm it appears with author contact info,
edit it, confirm the change is saved and visible.

**Depends on**: User Story 1 (Participant status gates who may propose) and Foundational (topic-approval
setting read at proposal time).

### Tests for User Story 3 ⚠️ write first, confirm they fail

- [X] T021 [P] [US3] Write failing `WebTestClient` integration tests: the topic list shows name, description,
      and the author's display name with OIDC subject in brackets (FR-009); `POST /topics` creates a Topic
      authored by the current Participant, starting `PENDING` or `APPROVED` per the current setting (FR-013); a
      Standard (non-Participant) user is denied `GET/POST /topics/new` (Edge Cases); the author can edit their
      own Topic, a non-author cannot (FR-011); the author's own `PENDING` Topic sorts to the top of their view
      labeled "Pending approval" (FR-009a, FR-012b); a `PENDING` Topic is invisible to any other non-Organiser
      viewer (FR-012a); `POST /topics` with a blank `name` or `description` re-renders the propose form (200)
      with the error presented as text tied to the field/action, not a bare failure (FR-037) — per
      [contracts/topics-self-service-and-approval.md](contracts/topics-self-service-and-approval.md)
      — in `src/test/java/net/fabcelhaft/hackathonorganiser/topics/TopicSelfServiceManagementIT.java`
- [X] T022 [P] [US3] Write failing unit tests for `TopicService.propose` (sets `approvalStatus` from the
      current `OrganiserSettings.topicApprovalRequired`) and the viewer-scoped 3-group visibility/ordering
      read model (FR-009a: viewer's-own-Pending, then viewer's-own-Approved, then all-other-Approved, each by
      creation date; a Topic appears in exactly one group; verified via `StepVerifier`) in
      `src/test/java/net/fabcelhaft/hackathonorganiser/topic/TopicServiceTest.java` (extends the existing file)

### Implementation for User Story 3

- [X] T023 [US3] Add `approval_status text NOT NULL DEFAULT 'APPROVED'` to the existing `topics` table via
      `ALTER TABLE topics ADD COLUMN IF NOT EXISTS ...` in `src/main/resources/schema.sql` (data-model.md)
- [X] T024 [P] [US3] Create the `TopicApprovalStatus` enum (`PENDING`, `APPROVED`) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/topic/TopicApprovalStatus.java`
- [X] T025 [US3] Add an `approvalStatus` field + accessors to the `Topic` entity, and update its class-level
      doc comment to note that `createdByUserId` is no longer strictly immutable — FR-015 (US4) supersedes
      002's original guarantee via one dedicated Organiser-only method, not this entity's mutability — in
      `src/main/java/net/fabcelhaft/hackathonorganiser/topic/Topic.java`, depends on T024
- [X] T026 [US3] Implement `TopicService.propose(UUID authorUserId, String name, String description)` (rejects
      a blank `name`/`description` with a `TopicConflictException`, matching `create()`'s existing validation
      pattern, FR-037; reads `OrganiserSettingsService.current()` at creation time, FR-013) and the
      viewer-scoped 3-group visibility/ordering read model (FR-009a, FR-012a) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/topic/TopicService.java`, depends on T007, T025 — makes
      T022 pass (partial)
- [X] T027 [US3] Implement the author-only self-service update path on `TopicService` (rejects a non-author;
      rejects a blank `name`/`description` with a `TopicConflictException`, FR-037; does not re-trigger
      approval, per spec Assumptions) in the same file, depends on T026 — makes T022 pass (fully)
- [X] T028 [US3] Implement `TopicSelfServiceController` (`GET/POST /topics/new`, `GET/POST /topics/{id}/edit`)
      per [contracts/topics-self-service-and-approval.md](contracts/topics-self-service-and-approval.md),
      catching `TopicConflictException` and re-rendering `topics/form` (200) with the error tied to the
      relevant field (FR-037), the same `onErrorResume` pattern 002's organiser `TopicController` already uses,
      in `src/main/java/net/fabcelhaft/hackathonorganiser/topics/TopicSelfServiceController.java`, depends on
      T026, T027 — makes T021 pass
- [X] T029 [US3] Update `HomeController` and `home/index.html` to render the real topic list (3 groups; author
      display-name + `[oidc-subject]`; a "Propose Topic" action; an "Edit" action visible only on the viewer's
      own Topics; the "Pending approval" text badge from T017 on Pending entries, FR-012b/FR-034; every form
      control given a programmatically associated label, FR-032) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/home/HomeController.java` and
      `src/main/resources/templates/home/index.html`, depends on T015, T016, T028

**Checkpoint**: User Stories 1–3 all work independently —
`mvn verify -Dit.test=HomeControllerIT,SettingsManagementIT,TopicSelfServiceManagementIT`.

---

## Phase 6: User Story 4 - Organiser Approves and Administers Topics (Priority: P2)

**Goal**: An Organiser toggles the topic-approval requirement, approves Pending Topics, and edits any Topic
including reassigning its author.

**Independent Test**: Enable approval, have a Participant propose a Topic, confirm it's Pending, approve it,
confirm the state change. Separately, edit an existing Topic's author and confirm it persists.

**Depends on**: User Story 3 (the `approval_status` column and propose/edit machinery it introduces) and User
Story 2 (the settings form this extends).

### Tests for User Story 4 ⚠️ write first, confirm they fail

- [X] T030 [P] [US4] Write failing `WebTestClient` integration test additions: enabling `topic_approval_required`
      via `/organiser/settings` makes every newly proposed Topic start `PENDING` (FR-013); disabling it stops
      that, without retroactively approving existing `PENDING` Topics (FR-016); `POST
      /organiser/topics/{id}/approve` moves a Pending Topic to Approved (FR-014); `POST /organiser/topics/{id}`
      now also accepts and persists a reassigned `created_by_user_id` (FR-015); multiple Pending Topics from
      different authors appear grouped together, ordered by creation date, in the Organiser's view (FR-009a) —
      per [contracts/topics-self-service-and-approval.md](contracts/topics-self-service-and-approval.md) — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/topic/TopicManagementIT.java` (extends the
      existing 002 file)

### Implementation for User Story 4

- [X] T031 [US4] Extend `OrganiserSettingsController` and `organiser/settings/form.html` to add the
      `topic_approval_required` toggle with its own plain-language effect sentence (FR-005a), depends on T019,
      T020
- [X] T032 [US4] Add `TopicService.approve(UUID topicId)` (Pending → Approved, no-op if already Approved) and
      `reassignAuthor(UUID topicId, UUID newAuthorUserId)` to
      `src/main/java/net/fabcelhaft/hackathonorganiser/topic/TopicService.java`, depends on T026 — makes T030
      pass (partial). `reassignAuthor` MUST follow the exact pattern `TopicService.create()` already uses for
      the identical "unknown user id" check (not a bare `Mono.empty()`/404): raise a `TopicConflictException`
      when `newAuthorUserId` doesn't exist, so the controller can re-render the edit form with a
      field-associated error (FR-037) instead of a bare 404 — see
      [contracts/topics-self-service-and-approval.md](contracts/topics-self-service-and-approval.md)
      Behavioral notes
- [X] T033 [US4] Extend `organiser/topic/TopicController`: add `POST /organiser/topics/{id}/approve`; extend
      `POST /organiser/topics/{id}` to accept `created_by_user_id` and apply it via `reassignAuthor`, catching
      `TopicConflictException` the same way the existing `create()`/`update()` handlers already do
      (`onErrorResume` → 200 re-render of `organiser/topics/form` with the error) rather than letting it 404;
      surface each Topic's Pending/Approved state in the list/detail model, in
      `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/topic/TopicController.java`, depends on T032 —
      makes T030 pass (fully)
- [X] T034 [P] [US4] Update `organiser/topics/list.html`, `detail.html`, `form.html` (the "Pending approval"
      badge, an Approve action, and an author-reassignment dropdown on the edit form) in
      `src/main/resources/templates/organiser/topics/`

**Checkpoint**: User Stories 1–4 all work independently —
`mvn verify -Dit.test=HomeControllerIT,SettingsManagementIT,TopicSelfServiceManagementIT,TopicManagementIT`.

---

## Phase 7: User Story 5 - View Rendered Homepage & Info Content (Priority: P3)

**Goal**: Every authenticated user sees a rendered (not raw) markdown document on the homepage's right column,
plus an Info section listing further arranged pages.

**Independent Test**: Publish markdown content, confirm it renders as formatted HTML on the homepage's right
column and in the Info section, in the arranged order.

**Depends on**: Foundational (shared layout). Independent of US1–US4's registration/topic behavior.

### Tests for User Story 5 ⚠️ write first, confirm they fail

- [X] T035 [P] [US5] Write failing unit tests for `MarkdownRenderer` (headings, lists, links, emphasis render
      correctly — FR-017; a submitted `<script>` tag / `on*` attribute is stripped from the output — FR-022; an
      `<img>` with `alt` renders inline — FR-026; a markdown `#` heading renders as `<h2>` and a `######`
      caps at `<h6>` — FR-036, research.md §1) in
      `src/test/java/net/fabcelhaft/hackathonorganiser/content/MarkdownRendererTest.java`
- [X] T036 [P] [US5] Write failing `WebTestClient` integration tests: the homepage right column renders the
      designated Content Page's markdown as sanitized formatted HTML (never raw markdown syntax, SC-006);
      `GET /info` lists non-homepage pages ordered by `sort_index` (tie-broken by `created_at`), with an
      empty-state message when none exist (Edge Cases); `GET /info/{id}` renders one page with its title as the
      top-level heading (FR-036); the homepage right column shows a clear empty/unset state when no page is
      designated (Edge Cases) — per [contracts/content-pages-and-info.md](contracts/content-pages-and-info.md)
      — in `src/test/java/net/fabcelhaft/hackathonorganiser/info/InfoManagementIT.java`

### Implementation for User Story 5

- [X] T037 [US5] Add `content_pages` table DDL, the partial unique index `content_pages_is_homepage_key ON
      content_pages (is_homepage) WHERE is_homepage`, and the default-page
      `INSERT ... WHERE NOT EXISTS (SELECT 1 FROM content_pages)` seed (FR-019a) to
      `src/main/resources/schema.sql` (data-model.md, research.md §5)
- [X] T038 [P] [US5] Create the `ContentPage` entity (`id`, `title`, `bodyMarkdown`, `sortIndex`, `isHomepage`,
      `createdAt`, `updatedAt`) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/content/ContentPage.java`
- [X] T039 [P] [US5] Create `ContentPageRepository extends ReactiveCrudRepository<ContentPage, UUID>` with
      derived finders `findAllByOrderBySortIndexAscCreatedAtAsc()` and `findByIsHomepageTrue()` in
      `src/main/java/net/fabcelhaft/hackathonorganiser/content/ContentPageRepository.java`
- [X] T040 [US5] Implement `MarkdownRenderer` (commonmark parse; a custom `HtmlNodeRendererFactory` shifting
      heading levels by +1 capped at 6; OWASP sanitizer policy = `Sanitizers.FORMATTING.and(LINKS).and(IMAGES)`;
      research.md §1) in `src/main/java/net/fabcelhaft/hackathonorganiser/content/MarkdownRenderer.java` —
      makes T035 pass
- [X] T041 [US5] Implement `ContentPageService` read paths (`findHomepage()`, `findInfoList()`,
      `findRenderedDetail(UUID id)` returning sanitized HTML via `MarkdownRenderer`) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/content/ContentPageService.java`, depends on T038, T039,
      T040
- [X] T042 [US5] Implement `InfoController` (`GET /info`, `GET /info/{id}`) per
      [contracts/content-pages-and-info.md](contracts/content-pages-and-info.md) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/info/InfoController.java`, depends on T041 — makes T036
      pass (partial)
- [X] T043 [US5] Update `HomeController` to render the real designated homepage Content Page (replacing T015's
      placeholder) via `ContentPageService.findHomepage()`, rendering a clear empty/unset state when none is
      designated, in
      `src/main/java/net/fabcelhaft/hackathonorganiser/home/HomeController.java` and
      `src/main/resources/templates/home/index.html` (right column now uses `th:utext` on the sanitized HTML,
      the single point in the codebase allowed to do so), depends on T041, T015, T016 — makes T036 pass (fully)
- [X] T044 [P] [US5] Create `src/main/resources/templates/info/list.html` and `info/detail.html` extending
      `fragments/layout.html`, each page's own title rendered as the top-level heading (FR-036), plus the
      empty-state markup for an empty Info list
- [X] T045 [US5] Verify the Info nav link's `th:href="@{/info}"` set back in T009 (a static URL expression that
      needed no code change to "activate" — it simply 404'd until this phase) now resolves correctly with
      `InfoController` in place; no template edit is expected here — this is a checkpoint, not an
      implementation task — depends on T009, T042

**Checkpoint**: User Stories 1–5 all work independently.

---

## Phase 8: User Story 6 - Organiser Manages Info & Homepage Content Pages (Priority: P3)

**Goal**: An Organiser adds, edits, removes, and reorders Content Pages, and designates which one is the
homepage page.

**Independent Test**: Add a markdown page, confirm it appears in Info, change its sort index, confirm the new
order is reflected for all users.

**Depends on**: User Story 5 (the read paths and rendering pipeline this authors into).

### Tests for User Story 6 ⚠️ write first, confirm they fail

- [X] T046 [P] [US6] Write failing `WebTestClient` integration tests: create/edit/delete a Content Page; setting
      `sort_index` reorders `/info` for all users (FR-020a); designating a page as the homepage page
      un-designates the previous one (FR-019, the partial-unique-index invariant); deleting the currently
      designated homepage page leaves `/` showing the empty/unset state until a replacement is designated
      (Edge Cases); a non-Organiser is denied every route (FR-021); creating or editing a page with a blank
      `title` or `body_markdown` re-renders the form (200) with the error presented as text tied to the field/
      action, not a bare failure (FR-037) — per
      [contracts/content-pages-and-info.md](contracts/content-pages-and-info.md) — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/content/ContentPageManagementIT.java`

### Implementation for User Story 6

- [X] T047 [US6] Create `ContentPageConflictException` (new, alongside the existing per-domain pattern —
      `TopicConflictException`, `GroupConflictException`, etc.) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/content/ContentPageConflictException.java`, and extend
      `ContentPageService` with `create`, `update` (including the sort-index field, FR-020a), and `delete`,
      rejecting a blank `title`/`body_markdown` with that exception (FR-037), with the homepage-designation
      swap (un-set the previous `is_homepage = true` row in the same write, FR-019) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/content/ContentPageService.java`, depends on T039
- [X] T048 [US6] Implement `ContentPageController` (list/new/create/edit/update/delete +
      homepage-designation) per [contracts/content-pages-and-info.md](contracts/content-pages-and-info.md),
      catching `ContentPageConflictException` and re-rendering the form (200) with the error tied to the
      relevant field (FR-037) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/content/ContentPageController.java`, depends
      on T047 — makes T046 pass
- [X] T049 [P] [US6] Create `src/main/resources/templates/organiser/content-pages/list.html` and `form.html`
      (title, markdown body textarea, sort-index field with a programmatically associated label — FR-032 —
      and homepage-designation checkbox) extending `fragments/layout.html`

**Checkpoint**: User Stories 1–6 all work independently.

---

## Phase 9: User Story 7 - Organiser Uploads and Embeds Images in Content Pages (Priority: P3)

**Goal**: An Organiser uploads images to a shared library and embeds them in Content Page markdown via
standard image syntax.

**Independent Test**: Upload an image, copy the provided syntax, paste it into a Content Page's body, save,
confirm it renders inline.

**Depends on**: User Story 6 (Content Pages to embed images into) for the end-to-end round trip; the upload/
library mechanics themselves are independently buildable once Foundational is done.

### Tests for User Story 7 ⚠️ write first, confirm they fail

- [X] T050 [P] [US7] Write failing `WebTestClient` integration tests: uploading a PNG/JPEG/GIF/WebP succeeds and
      the library shows the copyable `![alt](/content-images/{id})` syntax (FR-025); a non-image or >5 MB
      upload is rejected without being stored (FR-029); an embedded image renders inline on a Content Page
      (FR-026); deleting a still-referenced image is blocked, naming the referencing page(s) (FR-028); deleting
      an unreferenced image succeeds; editing alt text in place updates future copy syntax but leaves
      already-pasted markdown untouched (FR-025b); a non-Organiser is denied every management route (FR-027);
      `GET /content-images/{id}` serves raw bytes with the correct `Content-Type` to any authenticated user —
      per [contracts/content-images.md](contracts/content-images.md) — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/content/ContentImageManagementIT.java`
- [X] T051 [P] [US7] Write failing unit tests for `ContentImageService` (rejects a non-PNG/JPEG/GIF/WebP
      content type or a size over 5 MB before any write, FR-029; rejects a blank `alt_text`, FR-025a; the
      delete-block substring search over `content_pages.body_markdown` correctly names every referencing
      page's title, FR-028; verified via `StepVerifier`) in
      `src/test/java/net/fabcelhaft/hackathonorganiser/content/ContentImageServiceTest.java`

### Implementation for User Story 7

- [X] T052 [US7] Add `content_images` table DDL (`id`, `alt_text`, `content_type`, `byte_size`, `data bytea`,
      `created_at`, `updated_at`) to `src/main/resources/schema.sql` (data-model.md)
- [X] T053 [P] [US7] Create the `ContentImage` entity in
      `src/main/java/net/fabcelhaft/hackathonorganiser/content/ContentImage.java`
- [X] T054 [P] [US7] Create `ContentImageRepository extends ReactiveCrudRepository<ContentImage, UUID>` in
      `src/main/java/net/fabcelhaft/hackathonorganiser/content/ContentImageRepository.java`
- [X] T055 [P] [US7] Create `ContentImageConflictException` in
      `src/main/java/net/fabcelhaft/hackathonorganiser/content/ContentImageConflictException.java`
- [X] T056 [US7] Raise `spring.codec.max-in-memory-size` in `src/main/resources/application.yml` so a 5 MB
      multipart image upload isn't truncated/rejected by WebFlux's default (256 KB) in-memory buffer limit
      (needed for FR-029's cap to be the *only* size limit in effect)
- [X] T057 [US7] Implement `ContentImageService` (`upload` with format/size validation before any write and
      required `alt_text`, FR-025a/FR-029; `updateAltText`, FR-025b; `delete` with the reference-block search
      over `content_pages.body_markdown`, FR-028, research.md §3) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/content/ContentImageService.java`, depends on T053,
      T054, T055 — makes T051 pass
- [X] T058 [US7] Implement `ContentImageController` (`/organiser/content-images` list/upload/alt-text-edit/
      delete) per [contracts/content-images.md](contracts/content-images.md) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/content/ContentImageController.java`, depends
      on T057, T056
- [X] T059 [US7] Implement `ContentImageStreamController` (`GET /content-images/{id}` — raw bytes, the stored
      `Content-Type`, a long-lived `Cache-Control` header, plain authentication only, research.md §2) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/web/ContentImageStreamController.java`, depends on T054
      — makes T050 pass
- [X] T060 [P] [US7] Create `src/main/resources/templates/organiser/content-images/list.html` (upload form
      with a required, labeled alt-text field — FR-025a, FR-032; thumbnails via `<img src="/content-images/
      {id}">`; the copyable embed syntax per image; an inline alt-text edit form; a delete action that surfaces
      the FR-028 block message as text tied to the action, FR-037) extending `fragments/layout.html`

**Checkpoint**: All seven user stories are independently functional.

---

## Phase 10: Polish & Cross-Cutting Concerns

**Purpose**: Consistency and verification that spans every story, including the automated accessibility gate
(SC-009) that only makes sense once the screens it scans all exist.

- [X] T061 [P] Update `organiser/fragments/layout.html` to add the Info nav link, so an Organiser can reach
      Info content without leaving the admin area (Info is visible to every authenticated user, Organisers
      included — spec Assumptions)
- [X] T062 [P] Write `a11y.HomepageAccessibilityIT` (Playwright headless Chromium + Deque's `AxeBuilder`,
      `@SpringBootTest(webEnvironment = RANDOM_PORT)` + Testcontainers, research.md §9): scans the homepage, the
      topic propose/edit forms, the organiser settings screen, Content Page and Content Image management, and
      the Info section; asserts zero `critical`/`serious` WCAG 2.1 AA violations on each (SC-009); explicitly
      excludes 002's pre-existing organiser screens per FR-030's scope note — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/a11y/HomepageAccessibilityIT.java`
- [X] T063 Review T062's results and adjust `src/main/resources/static/css/app.css` as needed so all new UI
      meets WCAG 2.1 AA contrast minimums (4.5:1 normal text, 3:1 large text/UI component boundaries) in both
      the light and dark presentation (FR-038)
- [X] T064 Run `mvn verify` (full unit + `*ManagementIT`/`*IT` + `a11y.*IT` suite) and perform the
      [quickstart.md](quickstart.md) manual visual smoke test end-to-end across all seven user stories
      (Constitution Development Workflow #3)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately.
- **Foundational (Phase 2)**: Depends on Setup — BLOCKS all user stories (Organiser Settings and the shared
  nav/model-advice are read by nearly every later route).
- **User Stories (Phase 3–9)**: All depend on Foundational. Priority order per spec.md is US1 → US2 → US3 →
  US4 → US5 → US6 → US7; US2 depends on US1 (extends its gating into a UI), US4 depends on US3 (extends its
  data model/routes) and US2 (extends its settings form), US6 depends on US5 (extends its read paths), US7's
  end-to-end value depends on US6 (a Content Page to embed into) though its upload/library mechanics are
  independently buildable right after Foundational.
- **Polish (Phase 10)**: Depends on all seven user stories being complete (T062 in particular needs every
  in-scope screen to exist).

### Within Each User Story

- Tests written and confirmed failing before implementation (constitution NON-NEGOTIABLE).
- Entities/enums before services; services before controllers; controllers before templates that call them.
- Each story's Checkpoint is reachable via its own `mvn verify -Dit.test=...` run before starting the next.

### Parallel Opportunities

- Setup's single task and Foundational's T002/T003 (tests) and T005/T006/T008 (different files) can run in
  parallel within their phase.
- Once Foundational is done, US1 can start; US2–US4 have real sequential dependencies on US1/US3 as noted
  above, but US5's read-only rendering pipeline has no dependency on US1–US4 at all and could be staffed in
  parallel with them once Foundational is done, if team capacity allows.
- Within any story, tasks marked `[P]` touch different files and have no incomplete-task dependency.

---

## Parallel Example: User Story 1

```bash
# Tests together:
Task: "Write failing WebTestClient integration tests for User Story 1 in home/HomeControllerIT.java"
Task: "Write failing unit tests for ParticipantService.selfRegister/selfRevoke in participant/ParticipantServiceTest.java"
Task: "Write failing unit test for GroupService.findActiveGroupForParticipant in group/GroupServiceTest.java"

# Template + stylesheet together (once HomeController exists):
Task: "Create home/index.html template"
Task: "Create static/css/app.css"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1 (Setup) and Phase 2 (Foundational).
2. Complete Phase 3 (User Story 1).
3. **STOP and VALIDATE**: `mvn verify -Dit.test=HomeControllerIT` — a user with no Participant record can
   self-register and see their status reflected immediately, and self-revoke.
4. Demo if ready — this alone delivers SC-001/SC-002, the feature's core self-service promise.

### Incremental Delivery

1. Setup + Foundational → ready.
2. US1 → validate independently → demo (self-service registration works).
3. US2 → validate independently → demo (Organiser can now open/close registration and revocation).
4. US3 → validate independently → demo (Participants can browse/propose/edit Topics).
5. US4 → validate independently → demo (Organiser can gate and administer Topic approval).
6. US5 → validate independently → demo (rendered homepage/Info content).
7. US6 → validate independently → demo (Organiser authors that content).
8. US7 → validate independently → demo (Organiser embeds images) — full feature complete.
9. Polish → nav consistency, automated accessibility gate, full-suite + quickstart confirmation.

### Parallel Team Strategy

1. Team completes Setup + Foundational together.
2. Developer A takes US1 → US2 → US3 → US4 in sequence (real dependencies between them); Developer B starts
   US5 in parallel right after Foundational (no dependency on US1–US4), then takes US6 → US7 once US5 lands.
3. Polish once all seven stories are merged.

---

## Notes

- `[P]` tasks touch different files and have no incomplete-task dependency.
- `[Story]` label maps each task to its user story for traceability back to spec.md.
- `topics.approval_status`, `content_pages`, and `content_images` are new; `participant_skills`/
  `custom_field_values`/etc. from 002 are untouched by this feature.
- `GroupService.findActiveGroupForParticipant` + the existing `removeMember` are composed, not duplicated, to
  satisfy FR-007a (research.md §10) — no new `DatabaseClient` SQL is written for that step.
- `MarkdownRenderer` is the single point in the codebase that ever emits unescaped/`th:utext` HTML — every
  other template continues using Thymeleaf's default-escaped `th:text` (research.md §1).
- Verify each story's tests fail before implementing it; commit after each task or logical group.
- Stop at any checkpoint to validate a story independently before moving on.
