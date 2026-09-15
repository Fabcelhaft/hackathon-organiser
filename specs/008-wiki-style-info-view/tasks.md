---

description: "Task list for feature implementation"
---

# Tasks: Wiki-Style Info View

**Input**: Design documents from `/specs/008-wiki-style-info-view/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: Constitution Principle V (Test-First Development) is NON-NEGOTIABLE for this project — every task list below includes failing tests written before their implementation, per the Red-Green-Refactor cycle.

**Reactive verification**: Per Constitution Development Workflow #4, unit tests exercising a service's reactive chain (any test asserting a `Mono`/`Flux` result where the chain composes more than one operator) MUST use `StepVerifier`, not a blocking `.block()` call. This applies to T002 below. Integration tests (`*ManagementIT`) continue using `WebTestClient` plus the existing `.block()`-based repository test-helper convention already established in `InfoManagementIT`/`ContentPageManagementIT` — that is test setup, not a reactive-chain assertion.

**Organization**: Tasks are grouped by user story (P1–P4 from spec.md) to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: Which user story this task belongs to (US1–US4)
- File paths are relative to the repository root

## Path Conventions

Single Maven/Spring Boot project (see [plan.md](plan.md) Project Structure), extending the existing layout —
no new packages, only changes inside `content/`, `info/`, `organiser/content/`, `home/`, `participants/`,
`topics/`, `a11y/`, and their template/test counterparts.

---

## Phase 1: Setup

**Purpose**: N/A for this feature — it adds no new dependency (`commonmark`, `owasp-java-html-sanitizer`,
Testcontainers, and Playwright/axe-core are already on the classpath from earlier features) and no new module.
Proceed directly to Foundational.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Generalise `ContentPage`'s single `isHomepage` boolean into the four-value `context` designation
(`NONE`/`HOMEPAGE`/`TOPIC_CREATION`/`USER_REGISTRATION`) that every later story reads or writes: US1's menu
must exclude non-`NONE` pages and order by the new title tie-break, US2's Edit link needs the currently
displayed page, US3 needs all three non-`NONE` values and the exclusivity swap, US4's empty state depends on
`findInfoList()` being defined correctly.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### Tests (write first, confirm they fail)

- [X] T002 [P] Write failing unit tests for `ContentPageService` (verified via `StepVerifier`) covering:
      designating a page for `HOMEPAGE` clears whichever page previously held `HOMEPAGE`, independently of
      whatever `TOPIC_CREATION`/`USER_REGISTRATION` holders exist (and likewise for the other two contexts);
      `findInfoList()` excludes every page whose `context != NONE` and orders the rest ascending by
      `sortIndex`, tie-broken alphabetically by `title` (not `createdAt`); `nextSortIndex()` returns `0` when
      no Content Page exists and `max(sortIndex) + 1` otherwise; `findRenderedByContext(ctx)` returns the
      sanitized-HTML-wrapped page currently holding `ctx`, empty when none does — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/content/ContentPageServiceTest.java` (new file)

### Implementation

- [X] T001 [P] Add `ContentPageContext` enum (`NONE`, `HOMEPAGE`, `TOPIC_CREATION`, `USER_REGISTRATION`),
      persisted as `Enum#name()` text per the existing `ParticipantStatus`/`TopicApprovalStatus` convention
      (research.md §1), in `src/main/java/net/fabcelhaft/hackathonorganiser/content/ContentPageContext.java`
- [X] T003 Update `src/main/resources/schema.sql` (data-model.md "Schema change" — **note the
      `information_schema.columns` existence guard**: this is `schema.sql`'s first-ever column drop, and the
      file re-runs on every application startup via `spring.sql.init.mode=always`, so the
      backfill-then-drop must be skippable once `is_homepage` is already gone, or the second startup after
      deploying this feature fails outright): add `content_pages.context text NOT NULL DEFAULT 'NONE'`; inside
      a guard that checks `is_homepage` still exists, backfill `context = 'HOMEPAGE'` from it, drop
      `content_pages_is_homepage_key`, then drop the `is_homepage` column; finally add
      `CREATE UNIQUE INDEX IF NOT EXISTS content_pages_context_key ON content_pages (context) WHERE context <>
      'NONE'` (unconditional — safe to re-run on its own)
- [X] T004 Update `ContentPage.java`: replace the `isHomepage`/`setHomepage` boolean field and accessors with a
      `context` field of type `ContentPageContext` and `getContext()`/`setContext(ContentPageContext)`, in
      `src/main/java/net/fabcelhaft/hackathonorganiser/content/ContentPage.java`, depends on T001
- [X] T005 Update `ContentPageRepository.java`: replace `findByIsHomepageTrue()` with
      `Mono<ContentPage> findByContext(ContentPageContext context)`; replace
      `findAllByOrderBySortIndexAscCreatedAtAsc()` with `findAllByOrderBySortIndexAscTitleAsc()`; add
      `@Query("SELECT COALESCE(MAX(sort_index), -1) FROM content_pages") Mono<Integer> findMaxSortIndex()`, in
      `src/main/java/net/fabcelhaft/hackathonorganiser/content/ContentPageRepository.java`, depends on T001, T003
- [X] T006 Update the two other existing test files that call the boolean API being removed in T004 —
      `page.setHomepage(false)` in
      `src/test/java/net/fabcelhaft/hackathonorganiser/a11y/HomepageAccessibilityIT.java:374` and
      `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/content/ContentImageManagementIT.java:304` —
      to `page.setContext(ContentPageContext.NONE)`. **Land this together with T004** (not truly parallel,
      despite touching different files from T004 itself): since all test sources compile together, leaving
      either file on the old API breaks compilation of the entire test suite (every later checkpoint, including
      US1's) the moment T004 lands. Depends on T001, T004.
- [X] T007 Update `ContentPageService.java` (contracts/wiki-info-and-content-pages.md): rename
      `findHomepage()`/`findRenderedHomepage()` to `findByContext(ContentPageContext)`/
      `findRenderedByContext(ContentPageContext)`; change `findInfoList()` to filter `context == NONE` over
      the new `findAllByOrderBySortIndexAscTitleAsc()`; add `findRenderedDefault()` (the first element of
      `findInfoList()`, rendered); add `nextSortIndex()` (`findMaxSortIndex().map(max -> max + 1)`); change
      `create`/`update` to take a `ContentPageContext context` parameter instead of `boolean homepage`,
      generalising `unsetPreviousHomepageIfNeeded` into `unsetPreviousContextIfNeeded(ContentPageContext,
      UUID excludeId)` that no-ops when `context == NONE` and otherwise clears that same context from whichever
      page currently holds it; update `findRenderedDetail(id)` to `.filter(page -> page.getContext() ==
      ContentPageContext.NONE)` before rendering, so a deleted, unknown, **or currently-designated** id
      completes empty (FR-008) instead of rendering a designated page's content at `/info/{id}` — in
      `src/main/java/net/fabcelhaft/hackathonorganiser/content/ContentPageService.java`, depends on T001, T004,
      T005 — makes T002 pass
- [X] T008 Update `HomeController.java`'s `findRenderedHomepage()` call site to
      `contentPageService.findRenderedByContext(ContentPageContext.HOMEPAGE)`, in
      `src/main/java/net/fabcelhaft/hackathonorganiser/home/HomeController.java`, depends on T007

**Checkpoint**: Foundation ready — `mvn test -Dtest=ContentPageServiceTest` passes and `mvn test-compile`
succeeds across the whole test tree (T006 confirms no stray `is_homepage` API usage remains anywhere); user
story implementation can now begin in priority order.

---

## Phase 3: User Story 1 - Browse info pages like a wiki (Priority: P1) 🎯 MVP

**Goal**: Opening the Info section shows a left-hand menu of every undesignated Content Page and one page's
rendered content on the right, in a single view — no separate list page, no separate detail page.

**Independent Test**: With at least two Content Pages, open `/info`, confirm the menu and the first page's
content both render; click another menu entry, confirm the content pane and the current-page indicator both
update; request `/info/{unknown-id}`, confirm "not found" renders inside the same menu-plus-content layout.

### Tests for User Story 1 ⚠️ write first, confirm they fail

- [X] T009 [P] [US1] Rewrite `InfoManagementIT` for the merged wiki view (contracts/wiki-info-and-content-
      pages.md): `GET /info` renders the full menu plus the first-by-order page's content; `GET /info/{id}`
      renders the same menu with `id`'s content shown and that entry carrying `aria-current="page"`; two pages
      sharing `sort_index` appear adjacent in title-alphabetical order, stable across repeated requests; `GET
      /info/{unknown-id}` and `GET /info/{id-of-a-now-designated-page}` both return `404` while still rendering
      the full menu and a "not found" message — replaces the superseded `is_homepage`-column assertions with
      `context`-based ones. This file also carries the pre-existing homepage-rendering tests
      (`homepageRightColumnRendersTheDesignatedContentPageAsSanitizedFormattedHtml`,
      `homepageRightColumnShowsAClearEmptyStateWhenNoPageIsDesignated`) and the top-level-heading assertion
      (`infoDetailRendersOnePageWithItsTitleAsTheTopLevelHeading`, FR-007) — carry all of these forward using
      the new `context`/`ContentPageContext` API rather than dropping them during the rewrite — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/info/InfoManagementIT.java`

### Implementation for User Story 1

- [X] T010 [US1] Create `src/main/resources/templates/info/index.html`: a left-hand `<nav>` `<ul>` menu (one
      `<li>`/`<a>` per `findInfoList()` entry) plus a right-hand content pane; a "not found" branch that still
      renders the menu; the displayed page's `title` remains the sole top-level heading (unchanged from
      `info/detail.html`'s existing `th:utext` body rendering) — supersedes `info/list.html` + `info/detail.html`
- [X] T011 [US1] Delete `src/main/resources/templates/info/list.html` and
      `src/main/resources/templates/info/detail.html` (superseded by T010)
- [X] T012 [US1] Rewrite `InfoController.java`: `GET /info` builds the menu from `findInfoList()` and renders
      `findRenderedDefault()`'s result into `info/index`; `GET /info/{id}` builds the same menu plus
      `findRenderedDetail(id)` (only ever resolving a `context == NONE` page); an empty result renders
      `info/index` with a not-found flag and `Rendering.status(HttpStatus.NOT_FOUND)` (not a thrown
      `ResponseStatusException`, which would skip the view and drop the menu) — in
      `src/main/java/net/fabcelhaft/hackathonorganiser/info/InfoController.java`, depends on T007, T010 — makes
      T009 pass

**Checkpoint**: User Story 1 is fully functional and testable independently —
`mvn verify -Dit.test=InfoManagementIT`.

---

## Phase 4: User Story 2 - Organisers author from the wiki view (Priority: P2)

**Goal**: While viewing any info page, an Organiser sees Edit and New page actions above the content, each
opening the corresponding organiser screen in a new tab; a participant sees neither.

**Independent Test**: As an Organiser, view any info page, confirm Edit/New page appear above the content and
each opens in a new tab with the wiki view unchanged in the original tab; as a participant, confirm neither
action appears anywhere.

### Tests for User Story 2 ⚠️ write first, confirm they fail

- [X] T013 [P] [US2] Extend `InfoManagementIT` (an Organiser session needs a persisted `User` with
      `organiser = true` behind a `HackathonOidcUser` principal, per this codebase's `mockOidcLogin()`
      convention — a bare `mockOidcLogin()` does not resolve `CurrentUserModelAdvice`'s `isOrganiser`
      attribute): an Organiser viewing `/info/{id}` sees an Edit link targeting
      `/organiser/content-pages/{id}/edit` and a New page link targeting `/organiser/content-pages/new`, both
      `target="_blank" rel="noopener"`; a plain participant's response body contains neither link anywhere; on
      the not-found view, an Organiser sees New page but not Edit — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/info/InfoManagementIT.java`

### Implementation for User Story 2

- [X] T014 [US2] Add Edit/New page action markup to `info/index.html`, `th:if="${isOrganiser}"` (the
      model attribute `CurrentUserModelAdvice` already injects into every model); Edit further gated on a page
      actually being displayed (absent from the not-found branch, present alongside New page everywhere else),
      both links carrying `target="_blank" rel="noopener"` (research.md §6) — depends on T010, T012 — makes
      T013 pass

**Checkpoint**: User Stories 1 and 2 both work independently —
`mvn verify -Dit.test=InfoManagementIT`.

---

## Phase 5: User Story 3 - Context-specific special pages (Priority: P3)

**Goal**: An Organiser can designate a Content Page for the homepage, topic creation, or user registration
context (at most one page per context); a designated page's content renders at the top of that context and is
excluded from the Info menu; the organiser overview shows current designations; deleting a designated page
requires a confirmation naming the context it will empty.

**Independent Test**: Designate one page for topic creation and another for user registration; confirm each
context's participant-facing form shows the right content above its fields, both pages are gone from the Info
menu, and the organiser overview names each page's designation.

### Tests for User Story 3 ⚠️ write first, confirm they fail

- [X] T015 [P] [US3] Extend `ContentPageManagementIT` (contracts/wiki-info-and-content-pages.md). This file's
      shared `persistPage(String title, String bodyMarkdown, int sortIndex, boolean homepage)` helper is used
      by all 7 existing tests — change its signature to take `ContentPageContext context` and update every
      call site. Specifically:
      - Rename `designatingAPageAsHomepageUndesignatesThePreviousOne` to submit `context=HOMEPAGE` via the
        form and to read back `contentPageRepository.findByContext(ContentPageContext.HOMEPAGE)` instead of
        `findByIsHomepageTrue()`; the swap-undesignation assertion is unchanged otherwise.
      - Rename/preserve `deletingTheDesignatedHomepagePageLeavesTheHomepageShowingTheEmptyStateUntilAReplacementIsDesignated`
        using the same `context`-based helpers — this is the only existing regression test for FR-018b and
        must not be dropped.
      - Add: the organiser overview (`GET /organiser/content-pages`) lists each page's designation (or none,
        FR-018); the delete control for a designated page's row carries a confirmation naming its context,
        while an undesignated page's delete control is unchanged from today (FR-018a); submitting a blank or
        non-numeric `sort_index` re-renders the form with a validation error instead of silently defaulting to
        `0` (FR-019); the New page form's `sort_index` field arrives pre-filled to one above the current
        highest index, or `0` when no pages exist yet (FR-019a/FR-019b) — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/content/ContentPageManagementIT.java`
- [X] T016 [P] [US3] Extend `RegistrationManagementIT`: a page designated `USER_REGISTRATION` renders its body
      (unwrapped, no card, title omitted, per FR-016b) directly above `/register`'s form fields, and the form
      still has exactly one `<h1>` once that content is present (FR-016c — designated content's own headings
      must render `h2`+, never compete with the form's page heading); with no such designation the form
      renders exactly as today (FR-017); after a `USER_REGISTRATION`-designated page is deleted, the form
      falls back to rendering with no designated content rather than erroring (FR-018b) — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/participants/RegistrationManagementIT.java`
- [X] T017 [P] [US3] Extend `TopicSelfServiceManagementIT`: a page designated `TOPIC_CREATION` renders its body
      above `/topics/new`'s form fields, and the form still has exactly one `<h1>` once that content is present
      (FR-016c); the same content is absent from `/topics/{id}/edit` (FR-015); after a `TOPIC_CREATION`-
      designated page is deleted, `/topics/new` falls back to rendering with no designated content rather than
      erroring (FR-018b); spot-check (or add, if none exists) an assertion in
      `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/topic/TopicManagementIT.java` (`GET
      /organiser/topics/new`) and
      `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/participant/ParticipantManagementIT.java` (`GET
      /organiser/participants/new`) that no designated content renders on either admin form (FR-016a)
      — in `src/test/java/net/fabcelhaft/hackathonorganiser/topics/TopicSelfServiceManagementIT.java`

### Implementation for User Story 3

- [X] T018 [US3] Update `organiser/content-pages/form.html`: replace the "Designate as the homepage page"
      checkbox with a single-select `context` control offering None/Homepage/Topic creation/User registration
      (FR-012a), pre-selecting the page's current value on the edit path
- [X] T019 [US3] Update `ContentPageController.java`: parse the submitted `context` field into
      `ContentPageContext` (blank/unrecognised → `NONE`); replace `parseIntOrZero` with strict `sort_index`
      parsing that rejects a blank or non-numeric value via `ContentPageConflictException`, re-rendering the
      form with the submitted `title`/`bodyMarkdown`/`context` and an error (mirroring the existing
      title/body-blank validation path, FR-019); `newForm()` becomes reactive, calling
      `contentPageService.nextSortIndex()` and passing the result as the pre-filled `sortIndex` model
      attribute (FR-019a/FR-019b) — in
      `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/content/ContentPageController.java`, depends
      on T007, T018 — makes half of T015 pass
- [X] T020 [US3] Update `organiser/content-pages/list.html`: replace the "Homepage" column with a
      "Designation" column showing the page's context (or an empty/"—" cell for `NONE`, FR-018); add
      `th:if="${page.context.name() != 'NONE'}"` around an `onclick="return confirm('...')"` (naming the
      context) on that row's delete button only, leaving an undesignated row's delete control byte-for-byte
      unchanged (FR-018a) — makes the rest of T015 pass
- [X] T021 [US3] Update `RegistrationController.java`: `registerForm` zips in
      `contentPageService.findRenderedByContext(ContentPageContext.USER_REGISTRATION)` and passes it (nullable)
      as a `designatedContent` model attribute alongside the form's existing attributes — in
      `src/main/java/net/fabcelhaft/hackathonorganiser/participants/RegistrationController.java`, depends on
      T007 — makes half of T016 pass
- [X] T022 [US3] Update `participants/register.html`: render `designatedContent.bodyHtml()` via `th:utext`,
      unwrapped (no card/container), directly above the `<form>` `th:if="${designatedContent} != null"` —
      makes the rest of T016 pass
- [X] T023 [US3] Update `TopicSelfServiceController.java`: `newForm` only (not `editForm`/`update`) zips in
      `contentPageService.findRenderedByContext(ContentPageContext.TOPIC_CREATION)` and passes it as
      `designatedContent` — in
      `src/main/java/net/fabcelhaft/hackathonorganiser/topics/TopicSelfServiceController.java`, depends on T007
      — makes half of T017 pass
- [X] T024 [US3] Update `topics/form.html`: render `designatedContent.bodyHtml()` via `th:utext`, unwrapped,
      above the `<form>` `th:if="${designatedContent} != null"` — naturally absent on the edit path since
      `editForm` never populates it — makes the rest of T017 pass

**Checkpoint**: User Stories 1–3 all work independently —
`mvn verify -Dit.test=ContentPageManagementIT,RegistrationManagementIT,TopicSelfServiceManagementIT`.

---

## Phase 6: User Story 4 - Info section with no pages yet (Priority: P4)

**Goal**: Before any undesignated Content Page exists, visitors see an explanatory empty-state message instead
of an empty menu and content pane; Organisers additionally see the action to create the first page.

**Independent Test**: With zero undesignated pages, open `/info` as a participant and confirm the empty-state
message appears; repeat as an Organiser and confirm the create-page action is also present.

### Tests for User Story 4 ⚠️ write first, confirm they fail

- [X] T025 [P] [US4] Extend `InfoManagementIT`: with zero undesignated Content Pages (every page in the test
      database deleted or designated), a participant requesting `/info` or `/info/{any-id}` sees the
      empty-state message instead of a menu/content pane; an Organiser additionally sees the "New page" action
      — in `src/test/java/net/fabcelhaft/hackathonorganiser/info/InfoManagementIT.java`

### Implementation for User Story 4

- [X] T026 [US4] Add an empty-state branch to `info/index.html`: `th:if="${pages.isEmpty()}"` explanatory
      message, with T014's "New page" action still shown `th:if="${isOrganiser}"` in this branch
- [X] T027 [US4] Ensure `InfoController`/`ContentPageService.findRenderedDefault()` route an empty
      `findInfoList()` result to the empty-state branch (`200`) rather than the not-found branch (`404`), at
      both `GET /info` and `GET /info/{id}` — depends on T012, T026 — makes T025 pass

**Checkpoint**: All four user stories are independently functional.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Automated accessibility coverage for the new layout, plus full-suite and manual verification.

- [X] T028 [P] Write `a11y.InfoAccessibilityIT` (Playwright headless Chromium + Deque's `AxeBuilder`, following
      `HomepageAccessibilityIT`'s existing pattern): scans the wiki view's populated, empty-state, and
      not-found renders for both a participant and an Organiser session; asserts zero `critical`/`serious`
      WCAG 2.1 AA violations (new landmark nav with `aria-current`, one `<h1>` per rendered page) — in
      `src/test/java/net/fabcelhaft/hackathonorganiser/a11y/InfoAccessibilityIT.java`
- [X] T029 Run `mvn verify` (full unit + `*ManagementIT`/`*IT` + `a11y.*IT` suite) and confirm no regressions in
      `home/HomeControllerIT`, `content/MarkdownRendererTest`, and the organiser topic/participant management
      tests (confirming the `context` rename introduced no leakage into unrelated flows)
- [X] T030 Perform the [quickstart.md](quickstart.md) manual visual smoke test end-to-end across all four user
      stories (Constitution Development Workflow #3)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No tasks — nothing to add.
- **Foundational (Phase 2)**: BLOCKS all user stories — every story reads or writes `ContentPage.context`,
  `findInfoList()`'s new ordering, or `nextSortIndex()`. T006 in particular must land in this phase (not
  later) since it fixes a whole-test-tree compilation break introduced by T004.
- **User Stories (Phase 3–6)**: All depend on Foundational. Priority order per spec.md is US1 → US2 → US3 →
  US4; US2 depends on US1 (extends its template with the authoring actions), US4 depends on US1 (extends the
  same template with the empty-state branch). US3 depends only on Foundational — it touches
  `ContentPageController`/registration/topic-creation, not `info/index.html` — and could be built in parallel
  with US1/US2/US4 by a second developer.
- **Polish (Phase 7)**: Depends on all four user stories being complete (T028 in particular needs every
  render branch to exist).

### Within Each User Story

- Tests written and confirmed failing before implementation (constitution NON-NEGOTIABLE).
- Entities/enums before repositories/services; services before controllers; controllers before templates that
  call them.
- Each story's Checkpoint is reachable via its own `mvn verify -Dit.test=...` run before starting the next.

### Parallel Opportunities

- Foundational's T001 (enum) and T002 (test) can start in parallel; T006 can run as soon as T004 lands, in
  parallel with T005/T007/T008's continuing chain. T003–T005, T007, T008 are otherwise a straight-line
  dependency chain touching shared files.
- Once Foundational is done, US3 (T015–T024) has no dependency on US1/US2's template work and can be staffed
  in parallel with them; US4 needs US1's template (T010) to exist first.
- Within US3, the three test tasks (T015/T016/T017) touch different files and can run in parallel; likewise
  the Registration (T021/T022) and Topic-creation (T023/T024) implementation pairs are independent of each
  other once T007/T018/T019/T020 land.

---

## Parallel Example: Foundational + User Story 3

```bash
# Foundational, in parallel:
Task: "Add ContentPageContext enum in content/ContentPageContext.java"
Task: "Write failing unit tests for ContentPageService in content/ContentPageServiceTest.java"

# User Story 3 tests, in parallel (once Foundational is done):
Task: "Extend ContentPageManagementIT for the context single-select, overview, and delete confirmation"
Task: "Extend RegistrationManagementIT for USER_REGISTRATION-designated content"
Task: "Extend TopicSelfServiceManagementIT for TOPIC_CREATION-designated content"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 2 (Foundational).
2. Complete Phase 3 (User Story 1).
3. **STOP and VALIDATE**: `mvn verify -Dit.test=InfoManagementIT` — the Info section is a single wiki view with
   no separate list/detail navigation.
4. Demo if ready — this alone delivers SC-001/SC-002/SC-008, the feature's core redesign.

### Incremental Delivery

1. Foundational → ready.
2. US1 → validate independently → demo (wiki browsing works).
3. US2 → validate independently → demo (Organisers author straight from the wiki view).
4. US3 → validate independently → demo (homepage/registration/topic-creation designations).
5. US4 → validate independently → demo (empty state) — full feature complete.
6. Polish → automated accessibility gate, full-suite + quickstart confirmation.

### Parallel Team Strategy

1. Team completes Foundational together.
2. Developer A takes US1 → US2 → US4 in sequence (real template dependency between them); Developer B starts
   US3 in parallel right after Foundational (no dependency on US1/US2/US4).
3. Polish once all four stories are merged.

---

## Notes

- `[P]` tasks touch different files and have no incomplete-task dependency.
- `[Story]` label maps each task to its user story for traceability back to spec.md.
- `content_pages.context` replaces `content_pages.is_homepage`; no other table changes. This is the first
  column this codebase's `schema.sql` has ever dropped — T003's `information_schema.columns` guard exists
  specifically because that file re-runs on every startup (see T003's note).
- `MarkdownRenderer`'s existing heading-shift (`h1` reserved for the page/section title, markdown headings
  rendered `h2`+) already produces the *content's* correct heading levels for FR-016c; T016/T017 add the
  missing piece — an assertion that the *host form* (`/register`, `/topics/new`) still has exactly one `<h1>`
  once that content is spliced in.
- Content Page changes, including designation changes, are **not** written to the audit trail (spec
  Clarifications) — no audit-related task appears in this list.
- Verify each story's tests fail before implementing it; commit after each task or logical group.
- Stop at any checkpoint to validate a story independently before moving on.
