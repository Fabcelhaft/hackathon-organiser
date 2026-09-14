---

description: "Task list for Sortable Custom Fields"
---

# Tasks: Sortable Custom Fields

**Input**: Design documents from `/specs/009-custom-fields-sortable/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/custom-field-ordering.md, quickstart.md

**Tests**: Included. The project constitution (`.specify/memory/constitution.md`, Principle V) makes
Test-First Development non-negotiable — every task below that changes behaviour has a test task sequenced
before it, and each test task must be observed to FAIL before its implementation task starts. Unit tests use
JUnit 5 + Mockito + `StepVerifier` (`CustomFieldServiceTest`, `ParticipantServiceTest`); integration tests use
`WebTestClient` + Testcontainers + `mockOidcLogin()` (`*ManagementIT`). No new test infrastructure. Run a single
class with `mvn -B verify -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=<Class>` or the
whole suite with `mvn -B verify`.

**Organization**: Tasks are grouped by user story (spec.md). Method, attribute, and helper names below were
confirmed against the current codebase (`CustomFieldService.create/update/findAll/registrationFields`,
`ParticipantService.loadCustomFieldValueViews/findDirectoryListing`, `CustomFieldController.editFormView`, the
ITs' `persistDefinition(...)` helpers) and supersede any illustrative wording in the design docs.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

Single Spring Boot module (existing layout, no new top-level directories):
`src/main/java/net/fabcelhaft/hackathonorganiser/...`, `src/main/resources/...`,
`src/test/java/net/fabcelhaft/hackathonorganiser/...`.

---

## Phase 1: Setup (Schema and Entity)

**Purpose**: Make the new attribute exist end to end (column + mapped field) so every later phase can read
and write it. No behaviour change yet.

- [ ] T001 Append the idempotent column addition to `src/main/resources/schema.sql` (after the feature 006 block, with a `-- Feature 009: Sortable Custom Fields (data-model.md; FR-001, FR-002)` comment): `ALTER TABLE custom_field_definitions ADD COLUMN IF NOT EXISTS sort_index integer NOT NULL DEFAULT 0;` — research.md §1
- [ ] T002 Add `private int sortIndex;` with `getSortIndex()`/`setSortIndex(int)` to `src/main/java/net/fabcelhaft/hackathonorganiser/customfield/CustomFieldDefinition.java` (no `@Column` needed — the default naming strategy maps it to `sort_index`, as `ContentPage.sortIndex` already does); extend the class Javadoc with one sentence on the attribute's role — data-model.md "Custom Field Definition"

---

## Phase 2: Foundational (The Single Ordering Rule)

**Purpose**: The one comparator and the two service methods that every view (all three stories) consumes.
Nothing user-visible changes until a story phase wires it through, but no story is testable without it.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [ ] T003 Write failing unit tests in `src/test/java/net/fabcelhaft/hackathonorganiser/customfield/CustomFieldServiceTest.java` (new section `// --- findAll / registrationFields: display order (FR-007, FR-012) ---`): (a) `findAll()` given repository `Flux.just(Omega@3, Mid@0, Zeta@-1, Alpha@0)` emits Zeta, Alpha, Mid, Omega; (b) same index, labels `"apple"` and `"Banana"` → apple first (case-insensitive); (c) same index and identical label `"Twin"` with `createdAt` 10s apart → earlier first; (d) a definition with `null` `createdAt` does not throw and sorts last within its group; (e) `registrationFields()` with a disabled COUNTRY definition at index -5 omits it and keeps the remaining order. Use `StepVerifier.create(...).expectNextMatches(...)`. Confirm the class fails to compile/pass before T004
- [ ] T004 Add the `public static final Comparator<CustomFieldDefinition> DISPLAY_ORDER` constant to `src/main/java/net/fabcelhaft/hackathonorganiser/customfield/CustomFieldDefinition.java`: `Comparator.comparingInt(CustomFieldDefinition::getSortIndex).thenComparing(CustomFieldDefinition::getLabel, String.CASE_INSENSITIVE_ORDER).thenComparing(CustomFieldDefinition::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))` with a Javadoc citing FR-007/FR-012 and research.md §2
- [ ] T005 In `src/main/java/net/fabcelhaft/hackathonorganiser/customfield/CustomFieldService.java` change `findAll()` to `definitionRepository.findAll().sort(CustomFieldDefinition.DISPLAY_ORDER)` and make `registrationFields()` filter `findAll()` (not the repository) so it inherits the order; update both Javadocs to state the ordering guarantee (contracts/custom-field-ordering.md "Display order")
- [ ] T006 Run `CustomFieldServiceTest` and confirm the T003 tests pass and every pre-existing test in the class still passes (the `findAll`-based `setCountryEnabled` path is unaffected by ordering)

**Checkpoint**: `CustomFieldService.findAll()`/`registrationFields()` are ordered. Story phases can begin.

---

## Phase 3: User Story 1 - Organiser assigns a sort index to a Custom Field (Priority: P1) 🎯 MVP

**Goal**: The Organiser can set a whole-number sort index on the create/edit form, invalid input is rejected
without saving, and the Organiser's Custom Fields list shows the index and is already in display order.

**Independent Test**: Create three Custom Fields with indices -1 / (blank) / 3 via the organiser form, reload
`/organiser/custom-fields`, and confirm the rows appear in index order with alphabetical ties and a visible
"Sort index" column; submit `abc` and confirm a 200 re-render with an error and no new row
(quickstart.md §1–§4).

### Tests for User Story 1 (write first, confirm they FAIL)

- [ ] T007 [P] [US1] In `src/test/java/net/fabcelhaft/hackathonorganiser/customfield/CustomFieldServiceTest.java` update every existing `customFieldService.create(...)` (6 calls) and `customFieldService.update(...)` (6 calls) invocation to the new trailing-`int sortIndex` arity (pass `0`), then add: (a) `createPersistsTheGivenSortIndex` — `create("T-Shirt Size", FREE_TEXT, true, null, false, false, -5)` saves a definition with `getSortIndex() == -5`; (b) `updateChangesSortIndexEvenWhenFieldTypeIsLocked` — stub value count > 0, call `update(id, label, required, null /*no type change*/, null, null, 7)`, verify the saved definition has index 7 and no error; (c) `updateChangesSortIndexOnTheCountryRow` — COUNTRY definition, `update(id, "Country", false, null, null, null, 4)` succeeds with index 4 (FR-006)
- [ ] T008 [P] [US1] In `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/customfield/CustomFieldManagementIT.java` add tests (reuse `persistDefinition(...)`, set `definition.setSortIndex(...)` on the returned entity and re-save where an index is needed): (a) POST `/organiser/custom-fields` without `sort_index` → 303 and stored index 0; (b) POST with `sort_index=-5` → 303 and stored -5; (c) POST with `sort_index=abc` → 200, body contains `Sort index must be a whole number`, and no definition with that label exists afterwards; (d) POST with `sort_index=99999999999` → same as (c); (e) GET `/{id}/edit` of a definition with index 12 → body contains `name="sort_index"` with `value="12"`; (f) POST `/{id}` with `sort_index=3` → 303 and stored 3, other attributes unchanged, and a subsequent GET `/organiser/custom-fields` shows that row after a fixture at index 0 and before one at index 5 (FR-011: new position on next load); (g) POST `/{id}` on the seeded COUNTRY row with `sort_index=5` → 303 and stored 5; (h) GET `/organiser/custom-fields` with fixtures Omega@3, Mid@0, Zeta@-1, Alpha@0 (created in that order) → body positions (`indexOf`) satisfy Zeta < Alpha < Mid < Omega and the body contains a `Sort index` header cell; (i) GET `/organiser/custom-fields/new` → body contains `name="sort_index"` with `value="0"`

### Implementation for User Story 1

- [ ] T009 [US1] In `src/main/java/net/fabcelhaft/hackathonorganiser/customfield/CustomFieldService.java` add a trailing `int sortIndex` parameter to `create(...)` (call `definition.setSortIndex(sortIndex)` before `save`) and to `update(...)` (call `definition.setSortIndex(sortIndex)` inside the existing `Mono.defer` next to the `public_`/`overview` assignments — i.e. outside the type-change guard and the COUNTRY type check); update both Javadocs (FR-003, FR-006; research.md §3)
- [ ] T010 [US1] In `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/customfield/CustomFieldController.java`: add `private static int parseSortIndex(String raw)` (null/blank → 0; else `Integer.parseInt(raw.trim())`, rethrowing `NumberFormatException` as `CustomFieldConflictException("Sort index must be a whole number")`); in `create` and `update` the parse MUST be the head of the same chain the existing `onErrorResume` is attached to — shape: `Mono.fromCallable(() -> parseSortIndex(form.getFirst("sort_index"))).flatMap(sortIndex -> customFieldService.create(...)).<Rendering>map(...).onErrorResume(CustomFieldConflictException.class, ...)` — NOT a bare call inside the `getFormData().flatMap` lambda, which would throw synchronously past the resume and surface as a 500 (T008(c)/(d) guard this); pass the value to `customFieldService.create/update`; add `.modelAttribute("sortIndex", 0)` to `newForm()`, `.modelAttribute("sortIndex", definition.getSortIndex())` to `editFormView(...)`, and `sortIndex` (parsed value, or 0 when parsing failed) to the create error re-render and the update error re-render (set `existing.setSortIndex(...)` beside the existing `existing.setLabel/setRequired` lines) — research.md §4, contracts/custom-field-ordering.md
- [ ] T011 [P] [US1] In `src/main/resources/templates/organiser/custom-fields/form.html` add, directly after the Type `<select>`: `<label for="sort_index">Sort index</label><input type="number" id="sort_index" name="sort_index" step="1" th:value="${sortIndex}"/>` plus a `<small>` hint "Lower numbers appear first; fields with the same number are ordered alphabetically. Default 0." (Pico styles these natively; no CSS)
- [ ] T012 [P] [US1] In `src/main/resources/templates/organiser/custom-fields/list.html` add a `<th>Sort index</th>` immediately after `<th>Label</th>` and a matching `<td th:text="${field.sortIndex}">0</td>` after the label cell (FR-010)
- [ ] T013 [US1] Run `CustomFieldServiceTest` and `CustomFieldManagementIT`; confirm all T007/T008 tests pass and no pre-existing test regressed. Fix compile errors in any other caller of `create`/`update` (none are expected — `CustomFieldController` and `CustomFieldServiceTest` are the only callers today)

**Checkpoint**: The Organiser can set, see, and be corrected on the sort index; the management list is ordered.
This is the MVP.

---

## Phase 4: User Story 2 - Participants see Custom Fields in the configured order (Priority: P1)

**Goal**: The registration form, the self-edit form, the Participants directory columns, and the Participant
detail view all present Custom Fields in display order.

**Independent Test**: With fixtures Zeta@-1, Alpha@0, Mid@0, Omega@3 (all public + overview), open
`/register` or `/profile/edit`, `/participants`, and `/participants/{id}` and confirm the order Zeta, Alpha,
Mid, Omega in each (quickstart.md §5, §7).

### Tests for User Story 2 (write first, confirm they FAIL)

- [ ] T014 [P] [US2] In `src/test/java/net/fabcelhaft/hackathonorganiser/participant/ParticipantServiceTest.java` move every `when(customFieldDefinitionRepository.findAll())...` stub (currently at ~lines 932, 957, 980, 1003, 1046) to `when(customFieldService.findAll())...`, then add: (a) `findDirectoryListingKeepsOverviewColumnsInServiceOrder` — service mock returns `Flux.just(b, a)` (both overview, labels "B" then "A"), assert `row.overviewValues()` definitions are `[b, a]` (pass-through, not re-sorted); (b) `findDetailForViewerKeepsFieldsInServiceOrder` — same stub, assert `fields` order `[b, a]`; (c) `findDetailKeepsFieldsInServiceOrder` — same for the organiser read model. Use the existing `definitionOf(...)`, `stubNoStoredFieldValues()`, `stubSkills(...)` helpers
- [ ] T015 [P] [US2] In `src/test/java/net/fabcelhaft/hackathonorganiser/participants/ParticipantsDirectoryManagementIT.java` add `directoryColumnsAndDetailFieldsFollowSortIndexThenLabel`: persist (via `persistDefinition(...)`, then `setSortIndex` + re-save) Omega@3, Mid@0, Zeta@-1, Alpha@0 as public+overview in that creation order, one ACTIVE participant; GET `/participants` → header `indexOf` positions Zeta < Alpha < Mid < Omega and, on the row, the four cells' values (insert distinct values via `insertFreeTextValue`) appear in the same order; GET `/participants/{id}` as another viewer → labels in the same order, and a fifth fixture "Hidden"@-10 that is overview but NOT public is absent from that viewer's detail body even though it has the lowest index (FR-013: ordering never changes visibility)
- [ ] T016 [P] [US2] In `src/test/java/net/fabcelhaft/hackathonorganiser/participants/ProfileManagementIT.java` add `editFormListsFieldsInSortIndexThenLabelOrder`: same four fixtures (public), GET `/profile/edit` → label `indexOf` positions Zeta < Alpha < Mid < Omega; and in `src/test/java/net/fabcelhaft/hackathonorganiser/participants/RegistrationManagementIT.java` add the equivalent assertion for GET `/register` for a not-yet-registered user
- [ ] T017 [P] [US2] Also in `ProfileManagementIT` add `mixedCaseLabelsAtTheSameIndexSortCaseInsensitively`: fixtures "Banana"@0 then "apple"@0 → on `/profile/edit`, `apple` precedes `Banana` (spec US2 scenario 4)

### Implementation for User Story 2

- [ ] T018 [US2] In `src/main/java/net/fabcelhaft/hackathonorganiser/participant/ParticipantService.java` replace `customFieldDefinitionRepository.findAll()` with `customFieldService.findAll()` in `loadCustomFieldValueViews(UUID)` (~line 854) and in `findDirectoryListing()` (~line 909, keep the `.filter(CustomFieldDefinition::isOverview)`); update the `findDirectoryListing` Javadoc to say columns follow `CustomFieldDefinition.DISPLAY_ORDER`. `customFieldDefinitionRepository` stays injected for its `findById` uses — research.md §2, data-model.md "ParticipantService"
- [ ] T019 [US2] Run `ParticipantServiceTest`, `ParticipantsDirectoryManagementIT`, `ProfileManagementIT`, `RegistrationManagementIT`; confirm T014–T017 pass and nothing regressed (the template files `fragments/profile-fields-form.html`, `participants/list.html`, `participants/detail.html` need no change — verify by reading them, do not edit)

**Checkpoint**: Every Participant-facing view is ordered. US1 + US2 together satisfy SC-001–SC-004.

---

## Phase 5: User Story 3 - Organiser views match the Participant order (Priority: P2)

**Goal**: The Organiser's Participant detail view and the compliance rule field dropdown show Custom Fields in
the same display order as the management list and the Participant views.

**Independent Test**: With the same four fixtures, open `/organiser/participants/{id}` and `/organiser/compliance`
and confirm the value blocks and the dropdown options read Zeta, Alpha, Mid, Omega (quickstart.md §6).

**Dependency note**: the Organiser Participant detail view shares `ParticipantService.loadCustomFieldValueViews`
with US2. If US3 is implemented before US2, pull T018 forward — it is the only code change US3 needs.

### Tests for User Story 3 (write first, confirm they FAIL)

- [ ] T020 [P] [US3] In `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/participant/ParticipantManagementIT.java` add `organiserDetailListsCustomFieldsInSortIndexThenLabelOrder`: fixtures Omega@3, Mid@0, Zeta@-1, Alpha@0 (creation order as written), one participant; GET `/organiser/participants/{id}` as organiser → label `indexOf` positions Zeta < Alpha < Mid < Omega
- [ ] T021 [P] [US3] In `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/compliance/ComplianceManagementIT.java` add `fieldDropdownListsCustomFieldsInSortIndexThenLabelOrder`: same fixtures via `persistDefinition(String)` + `setSortIndex` + re-save; GET `/organiser/compliance` → within the `<select>` the `<option>` texts appear Zeta < Alpha < Mid < Omega

### Implementation for User Story 3

- [ ] T022 [US3] Confirm by reading that `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/compliance/ComplianceController.java` (`renderForm`, `customFieldService.findAll()` → stream filter) and `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/participant/ParticipantController.java` (`participantService.findDetail`) consume ordered sources and need no edit; add a one-line Javadoc note on `ComplianceController.renderForm` that `availableFields` preserves `CustomFieldDefinition.DISPLAY_ORDER` (FR-009)
- [ ] T023 [US3] Run `ParticipantManagementIT` and `ComplianceManagementIT`; confirm T020/T021 pass (they should pass as soon as T018 is in place — if they pass before T018, the fixture is not exercising the order: fix the fixture, not the assertion)

**Checkpoint**: All views agree (SC-002).

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T024 Run the full suite `mvn -B verify` from the repository root and confirm green; commit
- [ ] T025 Manual validation per `specs/009-custom-fields-sortable/quickstart.md` §1–§9 against a running instance (`docker-compose up -d`, `mvn spring-boot:run`) — constitution Development Workflow #3 requires the Thymeleaf smoke test; pay attention to §3 (browser number input bypass) and §8 (identical labels)
- [ ] T026 [P] Re-read `specs/009-custom-fields-sortable/spec.md` Functional Requirements FR-001–FR-014 against the diff and tick each off in a short checklist comment in the PR description; confirm FR-014 (no audit) holds — `git grep -n "auditService" src/main/java/net/fabcelhaft/hackathonorganiser/customfield` must return nothing
- [ ] T027 [P] Verify a fresh database (drop the Testcontainers/dev volume, start the app) and an existing database (pre-feature rows) both come up with every definition at `sort_index = 0` and purely alphabetical views (SC-003, FR-002)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — T001 and T002 touch different files and can run together.
- **Foundational (Phase 2)**: Depends on T002 (the entity field). BLOCKS all user stories.
- **User Stories (Phase 3–5)**: All depend on Phase 2.
  - US1 (Phase 3) and US2 (Phase 4) are independent of each other.
  - US3 (Phase 5) needs T018 from US2 for the organiser detail view; the compliance dropdown needs only Phase 2.
- **Polish (Phase 6)**: After all desired stories.

### User Story Dependencies

- **US1 (P1)**: Phase 2 only. Delivers the MVP on its own.
- **US2 (P1)**: Phase 2 only. Independently testable with fixtures inserted directly through the repository
  (the ITs do exactly that), so it does not need US1's form.
- **US3 (P2)**: Phase 2 + T018.

### Within Each User Story

- Test tasks are written and observed failing before the implementation tasks that follow them.
- Service before controller before template (US1); service redirection before IT run (US2).

### Parallel Opportunities

- T001 ∥ T002
- T007 ∥ T008 (different test files); then T011 ∥ T012 after T010
- T014 ∥ T015 ∥ T016 ∥ T017 (four different test files)
- T020 ∥ T021
- T026 ∥ T027
- Across stories: once Phase 2 is done, one developer can take US1 (form/controller/templates) while another
  takes US2 (`ParticipantService` + participant ITs) — they share no files.

---

## Parallel Example: User Story 1

```bash
# Red step — both test files at once:
Task: "T007 update create/update arity + sortIndex unit tests in customfield/CustomFieldServiceTest.java"
Task: "T008 sort_index create/edit/invalid/list-order ITs in organiser/customfield/CustomFieldManagementIT.java"

# Green step — after T009 (service) and T010 (controller), both templates at once:
Task: "T011 Sort index input in templates/organiser/custom-fields/form.html"
Task: "T012 Sort index column in templates/organiser/custom-fields/list.html"
```

## Parallel Example: User Story 2

```bash
# Red step — four test files at once:
Task: "T014 ParticipantServiceTest stub move + pass-through order tests"
Task: "T015 ParticipantsDirectoryManagementIT column/detail order"
Task: "T016 ProfileManagementIT + RegistrationManagementIT form order"
Task: "T017 ProfileManagementIT case-insensitive tie"
# Green step:
Task: "T018 ParticipantService: two call sites → customFieldService.findAll()"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 1 (T001–T002) → Phase 2 (T003–T006).
2. Phase 3 (T007–T013).
3. **STOP and VALIDATE**: quickstart.md §1–§4. The Organiser can already order fields and see the result in
   the management list.

### Incremental Delivery

1. Add US2 (T014–T019) → quickstart.md §5, §7 → every Participant-facing view is ordered.
2. Add US3 (T020–T023) → quickstart.md §6 → Organiser views match.
3. Polish (T024–T027).

### Notes

- Keep the ordering rule in exactly one place (`CustomFieldDefinition.DISPLAY_ORDER`). If an implementation
  step is tempted to sort in a template or a controller, the fix is to route that view through
  `CustomFieldService.findAll()` instead.
- Do not follow `ContentPageController.parseIntOrZero` for the new input — FR-005 requires rejection, not
  coercion (research.md §4).
- The IT `persistDefinition(...)` helpers leave `sortIndex` at Java's default 0; set it explicitly on the
  returned entity and `save` again when a fixture needs a non-zero index.
- Commit after each phase checkpoint.
