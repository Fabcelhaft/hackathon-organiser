---

description: "Task list for Cleaner Topic List UI"
---

# Tasks: Cleaner Topic List UI

**Input**: Design documents from `/specs/013-cleaner-topic-list-ui/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: INCLUDED and mandatory. Constitution Principle V (Test-First Development) is marked
NON-NEGOTIABLE for this project — every behavioural task below is preceded by a failing test.

**Organization**: Grouped by user story so each can be implemented, tested and shipped on its own.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on incomplete work)
- **[Story]**: US1, US2, US2a, US3, US4 — maps to the user stories in spec.md
- Exact file paths are given in every task

## Path Conventions

Single-module Maven project. Main code under `src/main/java/net/fabcelhaft/hackathonorganiser/`,
resources under `src/main/resources/`, tests under `src/test/java/net/fabcelhaft/hackathonorganiser/`.

---

## Phase 1: Setup

**Purpose**: Establish the baselines this feature is measured against. None of these change behaviour.

- [X] T001 Run `mvn verify` and confirm a green baseline before any change; record any pre-existing failure so it is not later mistaken for a regression
- [ ] T002 [P] Capture before-screenshots of `/` and `/topics/overview` at 1024px viewport width into `specs/013-cleaner-topic-list-ui/` for the SC-003 (row height halved) and SC-004 (name column widened) comparisons
- [ ] T003 [P] Record the current `main` commit as the organiser-space baseline for the SC-008 scope check described in [quickstart.md](./quickstart.md) scenario 7

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The shared row-layout mechanics. FR-012 and FR-013 span US1, US2 and US2a, so these
land once, first.

**⚠️ CRITICAL**: No user story work begins until this phase is complete.

**⚠️ SCOPE TRAP**: `.actions` is shared with every organiser-space table. Editing it violates
FR-021 and fails SC-008. Add a modifier class instead — see [research.md](./research.md) §7.

- [X] T004 Add the `.actions-nowrap` modifier (`flex-wrap: nowrap`) to `src/main/resources/static/css/app.css`, leaving the shared `.actions` rule byte-unchanged, with a comment explaining why it is a modifier and not an edit
- [X] T005 Wrap both tables in `src/main/resources/templates/topics/overview.html` in the existing `.table-scroll` div, matching how `src/main/resources/templates/home/index.html` already wraps its tables (FR-012)
- [X] T006 [P] Add a Playwright assertion helper under `src/test/java/net/fabcelhaft/hackathonorganiser/a11y/` that fails when any table row renders a control vertically below another control, for reuse by US1 and US2 (FR-013)

**Checkpoint**: Row-layout mechanics in place; user stories can begin.

---

## Phase 3: User Story 1 - Scan the Topic list without visual noise (Priority: P1) 🎯 MVP

**Goal**: The per-row "View" button disappears; the Topic name becomes the link to its detail page.

**Independent Test**: Load both screens with several Topics; every Topic occupies one row of normal
height, clicking the name opens its detail page, and no "View" control is rendered anywhere.

### Tests for User Story 1 ⚠️

> Write these FIRST and confirm they FAIL before implementing.

- [X] T007 [P] [US1] In `src/test/java/net/fabcelhaft/hackathonorganiser/home/HomeControllerIT.java`, rename `everyHomePageRowOffersAViewDetailsLinkToTheTopicDetailsView` to reflect name-as-link and extend it to assert the rendered name is wrapped in an anchor to `/topics/{id}` (FR-001)
- [X] T008 [P] [US1] In `src/test/java/net/fabcelhaft/hackathonorganiser/topics/TopicOverviewManagementIT.java`, add a test asserting each row's Topic name links to `/topics/{id}` and that the body contains no "View Details" control (FR-001, FR-002)
- [X] T009 [P] [US1] In `src/test/java/net/fabcelhaft/hackathonorganiser/a11y/TopicOverviewAccessibilityIT.java`, assert the name link is keyboard-focusable with a visible focus indicator (FR-003) and that badges sit outside the link target (FR-004)

### Implementation for User Story 1

- [X] T010 [US1] In `src/main/resources/templates/topics/overview.html`, wrap each Topic name in an anchor to `/topics/{id}`, delete the trailing View action cell and its `<th></th>` in both tables, and reduce the empty-state `colspan` expression accordingly (FR-001, FR-002, FR-017)
- [X] T011 [US1] In `src/main/resources/templates/home/index.html`, apply the same change to both tables; reduce the empty-state `colspan` literal from `4 + …` to `3 + …` (FR-017) — **note T046 reduces it again when the skills column goes**
- [X] T012 [US1] Apply `.actions-nowrap` alongside `.actions` on the remaining row action cells in `src/main/resources/templates/topics/overview.html` and `src/main/resources/templates/home/index.html` (FR-013)
- [X] T013 [P] [US1] Ensure the name anchor and its badges stay on one line by reusing the existing `.name-with-badge` wrapper in `src/main/resources/templates/topics/overview.html`, which currently lacks it (FR-004)
- [X] T014 [US1] Run the Phase 3 tests and confirm they now pass; re-run `HomeControllerIT` and `TopicOverviewManagementIT` in full to catch collateral assertion breakage

**Checkpoint**: US1 is independently shippable. Rows are shorter and the View button is gone.

---

## Phase 4: User Story 2 - Upvote without the control dominating the row (Priority: P1)

Split into two parts per the delivery phasing in [plan.md](./plan.md). Part A is presentational and
low risk; Part B is the only new behaviour in the feature.

**Goal**: One compact control carries the count and the vote action, updating in place.

**Independent Test**: With upvoting enabled, the count and control occupy one line in one column;
activating increments and marks the row voted without a reload; activating again reverses it; with
scripting disabled the form fallback still records the vote.

### Part A — the compact control (synchronous)

#### Tests ⚠️

- [X] T015 [P] [US2] In `src/test/java/net/fabcelhaft/hackathonorganiser/topics/TopicOverviewManagementIT.java`, assert each row renders exactly **one** vote form (not two mutually exclusive ones) carrying `aria-pressed` and an `aria-label` of `Upvote {name}` / `Withdraw upvote for {name}` (FR-005, FR-008, FR-008a)
- [X] T016 [P] [US2] Add the equivalent assertions to `src/test/java/net/fabcelhaft/hackathonorganiser/home/HomeControllerIT.java`
- [X] T017 [P] [US2] Add a guard test asserting `src/main/resources/templates/topics/detail.html` still renders its labelled "Upvote"/"Withdraw upvote" button unchanged (FR-021a)

#### Implementation

- [X] T018 [US2] Create `src/main/resources/templates/fragments/topic-vote.html` exposing a `control(...)` fragment: one form with a swappable action, `[data-vote-form]`, `[data-vote-button]` with `aria-pressed`, and `[data-vote-count]`, per [contracts/vote-control-fragment.md](./contracts/vote-control-fragment.md)
- [X] T019 [US2] Replace the two-form vote cells in `src/main/resources/templates/topics/overview.html` with a call to the new fragment
- [X] T020 [US2] Replace the two-form vote cells in `src/main/resources/templates/home/index.html` with the same fragment call
- [X] T021 [P] [US2] Add `.vote-control` chip styling to `src/main/resources/static/css/app.css` using only existing `--app-*`/Pico tokens, distinguishing voted from unvoted by more than colour (FR-007, Constitution IV)
- [X] T022 [US2] Run Phase 4 Part A tests; confirm `aria-label` wording is preserved so the pre-existing `contains("Upvote")` assertions still hold ([research.md](./research.md) §3)

**Checkpoint**: The control is compact and still a plain form submit. Shippable as-is.

### Part B — asynchronous updating

#### Tests ⚠️

- [X] T023 [P] [US2] In `src/test/java/net/fabcelhaft/hackathonorganiser/topics/TopicUpvoteManagementIT.java`, assert `POST /topics/{id}/upvote` with `X-Vote-Fragment: true` returns `200` + `text/html` containing the four contract values, and that the same request **without** the header still returns `303` to the allow-listed target (FR-006a, FR-006b)
- [X] T024 [P] [US2] In `src/test/java/net/fabcelhaft/hackathonorganiser/topics/TopicUpvoteManagementIT.java`, assert the fragment branch still honours the gate: `404` when upvoting is disabled and `404` for a Topic not visible to the caller
- [X] T025 [P] [US2] In `src/test/java/net/fabcelhaft/hackathonorganiser/a11y/HomepageAccessibilityIT.java`, assert exactly one visually-hidden `role="status" aria-live="polite"` region exists when upvoting is enabled, and none when it is disabled (FR-008b, FR-009)
- [X] T026 [P] [US2] Add a Playwright test asserting that voting does not reload the page and leaves scroll position and keyboard focus unchanged (FR-006a, SC-009) — use `setExact(true)` on row locators, since every control shares the `"Upvote "` prefix ([research.md](./research.md) §8.3)
- [X] T027 [P] [US2] Add a Playwright test under `src/test/java/net/fabcelhaft/hackathonorganiser/a11y/` with JavaScript disabled asserting the form fallback records the vote and returns to the originating screen (FR-006b, SC-010)
- [X] T028 [P] [US2] Add a Playwright test under `src/test/java/net/fabcelhaft/hackathonorganiser/a11y/` that stubs the vote request to fail, asserting the count reverts, a notice appears beside the control, and no row changes height (FR-006c, FR-006c1, FR-006c2, SC-009a)

#### Implementation

- [X] T029 [US2] Add the `X-Vote-Fragment` branch to `src/main/java/net/fabcelhaft/hackathonorganiser/topics/TopicUpvoteController.java`, returning the `fragments/topic-vote :: control` rendering; reuse the existing `gate(...)` so toggle and visibility rules stay in one place
- [X] T030 [US2] Create `src/main/resources/static/js/topic-vote.js` as an IIFE with `'use strict'` matching `country-select.js` conventions: intercept submit, apply the optimistic change, POST with the header, then copy count/`aria-pressed`/`aria-label`/form `action` onto the **existing** nodes — never replacing the button, so focus survives ([research.md](./research.md) §2)
- [X] T031 [US2] Implement last-activation-wins reconciliation in `topic-vote.js` so overlapping clicks settle on the final intent rather than the last response to arrive (FR-006d)
- [X] T032 [US2] Implement failure handling in `topic-vote.js`: revert count and pressed state, render the row-anchored notice with `role="alert"`, and never announce success for an unconfirmed vote (FR-006c, FR-008d, FR-008e)
- [X] T033 [US2] Add the visually-hidden `role="status" aria-live="polite"` region and the `<script th:src="@{/js/topic-vote.js}">` tag to `src/main/resources/templates/home/index.html` and `src/main/resources/templates/topics/overview.html` — **the script tag MUST sit after the closing `</th:block>`** or Thymeleaf discards it silently ([research.md](./research.md) §8.1)
- [X] T034 [P] [US2] Add `.vote-notice` styling to `src/main/resources/static/css/app.css`: `position: absolute` within a `position: relative` action cell, anchored left and below the control so it neither reflows the row nor obscures the control (FR-006c2, FR-006c3)
- [ ] T035 [US2] Verify the notice is not clipped by `.table-scroll`'s `overflow-x: auto` on the last row and at narrow widths — the open risk recorded in [research.md](./research.md) §5; adjust anchoring if it is

**Checkpoint**: US2 complete. Voting is instant, accessible, and degrades cleanly without scripting.

---

## Phase 5: User Story 2a - Joining without losing your place (Priority: P2)

**Goal**: Joining returns the participant to the screen they acted from, and Join sits quietly
beside the vote control.

**Independent Test**: Join from `/topics/overview` and land back on `/topics/overview` with the
confirmation; join from the Dashboard and land back on the Dashboard.

### Tests for User Story 2a ⚠️

- [X] T036 [P] [US2a] In `src/test/java/net/fabcelhaft/hackathonorganiser/topics/TopicSelfServiceManagementIT.java`, assert joining with `redirect=/topics/overview` returns `303` to `/topics/overview`, and with `redirect=/` returns `303` to `/` (FR-011a)
- [X] T037 [P] [US2a] In `src/test/java/net/fabcelhaft/hackathonorganiser/topics/TopicSelfServiceManagementIT.java`, assert a tampered redirect (`https://example.com/`) is rejected and falls back to `/`, so joining cannot become an open redirect (FR-011b)
- [X] T038 [P] [US2a] In `src/test/java/net/fabcelhaft/hackathonorganiser/topics/TopicSelfServiceManagementIT.java`, assert a failed join (Topic full, or participant already in a Group) returns to the originating screen with its explanatory flash message intact

### Implementation for User Story 2a

- [X] T039 [US2a] Extract the redirect allow-list from `src/main/java/net/fabcelhaft/hackathonorganiser/topics/TopicUpvoteController.java` into one shared helper both controllers call — two divergent allow-lists is how one eventually gets it wrong ([research.md](./research.md) §8a)
- [X] T040 [US2a] In `src/main/java/net/fabcelhaft/hackathonorganiser/topics/TopicJoinController.java`, replace the hardcoded `redirectHomeWithFlash(...)` with the allow-listed return target, preserving the existing flash messages and both conflict `onErrorResume` branches (FR-011a, FR-011b, FR-011d)
- [X] T041 [US2a] Add `<input type="hidden" name="redirect" value="/topics/overview"/>` to the Join forms in `src/main/resources/templates/topics/overview.html`, and `value="/"` to those in `src/main/resources/templates/home/index.html`
- [X] T042 [P] [US2a] Size the Join control in `src/main/resources/static/css/app.css` to sit beside the compact vote control without dominating it, keeping its text label and a comfortable touch target (FR-011c)

**Checkpoint**: Joining and voting now behave consistently about preserving the participant's place.

---

## Phase 6: User Story 3 - A Dashboard Topics card with room to breathe (Priority: P2)

**Goal**: The "Your Skills" column leaves both Dashboard tables, along with the work computing it.

**Independent Test**: As a participant whose skills match a listed Topic, the Dashboard shows no
skills column while `/topics/overview` still shows "Needed Skills".

### Tests for User Story 3 ⚠️

- [X] T043 [P] [US3] Delete `findOpenTopicsForHomePageIntersectsNeededSkillsWithTheViewersOwnSkills` and `findOpenTopicsForHomePageGivesAViewerWithNoParticipantRecordAnEmptySkillsListNeverAnError` from `src/test/java/net/fabcelhaft/hackathonorganiser/topic/TopicDiscoveryServiceTest.java` — they assert the removed behaviour and cannot be rescued
- [X] T044 [P] [US3] Delete the skill-matching and skill-display-mode assertions from `src/test/java/net/fabcelhaft/hackathonorganiser/home/HomeControllerIT.java`, and add one asserting the Dashboard body contains no skills column; equivalent overview coverage already exists in `TopicOverviewManagementIT`, so no coverage is lost (FR-015, FR-016)
- [X] T045 [P] [US3] In `src/test/java/net/fabcelhaft/hackathonorganiser/home/HomeControllerIT.java`, add a test asserting the Dashboard's empty-state row spans the correct post-removal column count (FR-017)

### Implementation for User Story 3

- [X] T046 [US3] Remove the "Your Skills" `<th>` and `<td>` from both tables in `src/main/resources/templates/home/index.html` and reduce the empty-state `colspan` again, to `2 + …` (FR-015, FR-017) — depends on T011
- [X] T047 [US3] Remove `viewerOfferedSkills` from the `OpenTopicRow` record in `src/main/java/net/fabcelhaft/hackathonorganiser/topic/TopicDiscoveryService.java` ([data-model.md](./data-model.md))
- [X] T048 [US3] In `src/main/java/net/fabcelhaft/hackathonorganiser/topic/TopicDiscoveryService.java`, simplify `findOpenTopicsForHomePage(...)`: the per-row `concatMap(displayedNeededSkillIds → viewerOfferedSkillIds → loadSkills → map)` chain collapses to a plain `map`, dropping three database round trips per row (FR-015a, SC-011)
- [X] T049 [US3] In `src/main/java/net/fabcelhaft/hackathonorganiser/topic/TopicDiscoveryService.java`, delete the now-unreferenced private `viewerOfferedSkillIds(...)` helper, and drop the dead `viewerParticipantIdOrNull` parameter from `findOpenTopicsForHomePage(...)`; **keep** `displayedNeededSkillIds(...)` and `loadSkills(...)`, which the overview and detail paths still use (FR-015b)
- [X] T050 [US3] Update the single call site in `src/main/java/net/fabcelhaft/hackathonorganiser/home/HomeController.java` for the new signature, removing the participant-id lookup if it now serves nothing else
- [X] T051 [US3] Update the remaining `TopicDiscoveryServiceTest` call sites for the two-argument signature and confirm the suite is green

**Checkpoint**: Dashboard rows are wider and cheaper to serve.

---

## Phase 7: User Story 4 - Quieter card-level actions (Priority: P3)

**Goal**: "Propose Topic" and "All Topics" stop dominating the Dashboard's Topics card.

**Independent Test**: Both controls still present and still lead to the proposal form and the
overview; both visibly lighter than before.

### Tests for User Story 4 ⚠️

- [X] T052 [P] [US4] In `src/test/java/net/fabcelhaft/hackathonorganiser/home/HomeControllerIT.java`, assert both controls render and link to `/topics/new` and `/topics/overview` respectively (FR-018)
- [X] T053 [P] [US4] In `src/test/java/net/fabcelhaft/hackathonorganiser/home/HomeControllerIT.java`, assert "Propose Topic" keeps the primary treatment and "All Topics" the secondary one (FR-019)

### Implementation for User Story 4

- [X] T054 [US4] Restyle the card action bar in `src/main/resources/templates/home/index.html`, keeping both as buttons at reduced visual weight (FR-018, FR-019, FR-020)
- [X] T055 [P] [US4] Add any supporting rule to `src/main/resources/static/css/app.css`, keeping both controls at a comfortable touch target size and existing token colours (FR-020, Constitution IV)
- [X] T056 [US4] Confirm with the axe scan that the quieter treatment still clears contrast at WCAG 2.1 AA (SC-006)

**Checkpoint**: All user stories complete.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [X] T057 Run the full `mvn verify` suite and confirm green
- [ ] T058 [P] Execute every scenario in [quickstart.md](./quickstart.md), including the scripting-disabled and screen-reader passes that no automated test covers
- [X] T059 [P] Verify the SC-008 scope boundary: `git diff --stat main -- src/main/resources/templates/organiser/ src/main/resources/templates/topics/detail.html` must be empty, and `.actions` must be unchanged in `app.css`
- [ ] T060 [P] Capture after-screenshots at 1024px and confirm SC-003 (row height at least halved) and SC-004 (no name of 40 characters or fewer wraps in the Dashboard card)
- [X] T061 [P] Run `HomepageAccessibilityIT` and `TopicOverviewAccessibilityIT` and confirm zero critical or serious WCAG 2.1 AA violations (SC-006)
- [X] T062 Confirm no schema change was made, so the `CLAUDE.md` demo-data rule stays untriggered and `docs/demo-data.sql` needs no update
- [X] T063 [P] Remove any CSS left dead by the removed View button and skills column from `src/main/resources/static/css/app.css`
- [X] T064 [P] Update the comment block at the top of `src/main/resources/static/css/app.css` to describe the new `.actions-nowrap`, `.vote-control` and `.vote-notice` rules, matching the file's existing documentation style
- [X] T065 Verify SC-002 by inspection: at most two interactive controls per row, and exactly one on a row the viewer cannot join

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)**: no dependencies
- **Foundational (Phase 2)**: depends on Setup; **blocks all user stories**
- **US1 (Phase 3)**: depends on Foundational — the MVP
- **US2 (Phase 4)**: depends on Foundational; Part B depends on Part A
- **US2a (Phase 5)**: depends on Foundational; T039 touches a file US2 also edits
- **US3 (Phase 6)**: depends on Foundational; T046 depends on T011
- **US4 (Phase 7)**: depends on Foundational only — fully independent
- **Polish (Phase 8)**: depends on every story you intend to ship

### ⚠️ File contention — the real constraint

The stories are logically independent but physically contend on two files. Treat these as
serialization points regardless of how the work is staffed:

| File | Touched by |
|---|---|
| `templates/home/index.html` | T011 (US1), T020 (US2), T033 (US2), T041 (US2a), T046 (US3), T054 (US4) |
| `templates/topics/overview.html` | T005 (Found.), T010 (US1), T019 (US2), T033 (US2), T041 (US2a) |
| `static/css/app.css` | T004 (Found.), T021/T034 (US2), T042 (US2a), T055 (US4), T063/T064 (Polish) |

Two developers cannot take US1 and US3 in parallel without conflicting on `home/index.html`, and the
`colspan` expression there is edited twice (T011 then T046) for two different reasons. Sequencing
by story priority avoids this entirely; parallel staffing needs explicit coordination on these three
files.

### Within each story

- Tests are written and confirmed failing before implementation (Constitution V)
- Template changes before the CSS that styles them
- Controller changes before the client script that calls them

### Parallel opportunities

- T002, T003 (Setup)
- T007, T008, T009 (US1 tests — three different test files)
- T015, T016, T017 (US2 Part A tests)
- T023–T028 (US2 Part B tests — six different concerns, all written before T029)
- T036, T037, T038 (US2a tests)
- T043, T044, T045 (US3 tests)
- T058–T061, T063, T064 (Polish)

---

## Parallel Example: User Story 2 Part B

```bash
# All six Part B tests, written before any implementation:
Task: "Fragment branch contract test in TopicUpvoteManagementIT"          # T023
Task: "Fragment branch gate test (404 disabled / not visible)"            # T024
Task: "Live region presence test in HomepageAccessibilityIT"              # T025
Task: "Playwright: no reload, scroll and focus preserved"                 # T026
Task: "Playwright: scripting disabled, form fallback works"               # T027
Task: "Playwright: stubbed failure reverts and shows the notice"          # T028
```

---

## Implementation Strategy

### MVP (US1 only)

1. Phase 1 Setup → Phase 2 Foundational → Phase 3 US1
2. **STOP and VALIDATE**: rows are one line, the View button is gone, names link
3. This alone answers the original complaint and is safe to ship

### Recommended incremental delivery

Mirrors the staging in [plan.md](./plan.md), lowest risk first:

1. **US1 + US3 + US4** → the whole visual cleanup. Templates, CSS and one service simplification; nothing behavioural
2. **US2a** → joining stops losing the participant's place. Mirrors a proven existing pattern
3. **US2 Part A** → the compact vote control, still a plain form submit
4. **US2 Part B** → asynchronous updating. The only new behaviour, and the only stage that can leave the app worse than it started

Stage 4 is the one to validate hardest. FR-006b's no-scripting fallback is the safety net: if the
enhancement is backed out, the feature degrades to Stage 3 rather than breaking.

### Notes

- `[P]` means different files and no dependency on incomplete work
- Verify each test fails before implementing against it
- Commit after each task or logical group
- Any checkpoint is a safe stopping point


---

## Completion status (/speckit-implement, 2026-10-01)

**60 of 65 tasks complete. Full suite: 442 tests, 0 failures, BUILD SUCCESS.**

Five tasks are deliberately left unchecked rather than claimed:

- **T002, T003, T060** — before/after screenshots at 1024px for the SC-003 (row height halved) and
  SC-004 (name column widened) comparisons. These need the app running against a browser with the
  demo data loaded; the automated suite proves the structural changes but not the measured visual
  deltas. The git baseline for T003 was taken implicitly (`main` is the comparison point used by
  T059, which passes).
- **T035** — confirming the failure notice is not clipped by `.table-scroll`'s `overflow-x: auto`
  on the last row and at narrow widths. The automated failure test asserts the notice appears and
  that the row does not change height, but not that it is fully visible inside the scroll
  container. This is the open risk recorded in research.md §5 and still wants a human look.
- **T058** — the screen-reader pass of quickstart.md. The scripting-disabled scenario IS automated
  (`votingStillWorksWithJavaScriptDisabled`), and the announcement machinery is asserted
  structurally, but no automated test can confirm what a real screen reader actually speaks.

### Bugs found by the tests during implementation

1. **The live region ignored the upvoting toggle.** `th:if` and `th:replace` were on the same
   element; Thymeleaf processes fragment inclusion (precedence 100) before conditionals
   (precedence 300), so the condition was silently discarded and the region rendered even with
   upvoting switched off (FR-009). Fixed by moving the `th:if` to an outer element. Caught only
   because the FR-009 assertion was extended to cover the live region.
2. **A test-isolation defect of my own making.** The upvoting toggle was restored at the end of the
   test body rather than in `@AfterEach`, so one failing assertion left the feature disabled and
   cascaded into four unrelated tests.
3. **A test race.** The in-place vote test waited on `aria-pressed`, which the *optimistic* update
   already satisfies — proving nothing about the server round trip. It now waits on the live
   region, which is only written on confirmation.
