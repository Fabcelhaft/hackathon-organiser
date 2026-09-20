---

description: "Task list for Event-Driven Task Rules"
---

# Tasks: Event-Driven Task Rules

**Input**: Design documents from `/specs/011-event-driven-task-rules/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/](./contracts/)

**Tests**: **MANDATORY, not optional.** The project constitution's Principle V (Test-First) is marked
NON-NEGOTIABLE: "Tests MUST be written and reviewed before implementation... No feature is
considered done until its tests pass and are committed." Every story phase below therefore leads
with failing tests. Unit tests use JUnit 5 + Mockito; integration tests use `WebTestClient` (never
`MockMvc`) with Testcontainers.

**Organization**: Tasks are grouped by user story so each can be implemented, tested, and demoed
independently.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: US1 / US2 / US3, mapping to the user stories in spec.md
- Every task names its exact file path

## Terminology

One concept, four names across these documents. They are the same thing:

| Term | Used in | Means |
|---|---|---|
| **Task Rule** | spec.md, user-facing text | The configuration an Organiser creates |
| **Event Destination** | code, data-model.md | The entity and table it is stored in |
| **`TASK` destination** | tasks.md notes | An `EventDestination` whose `type` is `TASK` |
| **handler** | FR-032a, contracts/ | Umbrella term covering all three: Kafka, HTTP POST, Task |

**A Task Rule is an Event Destination of type `TASK`** — not a separate entity, table, service, or
repository (data-model.md §"Task Rule"). Nothing below creates a `TaskRule` class. Where a task says
"Task Rule", the code it touches is `EventDestination` and `EventDestinationService`.

## Path Conventions

Existing Spring Boot single-module layout. Java sources under
`src/main/java/net/fabcelhaft/hackathonorganiser/`, tests under
`src/test/java/net/fabcelhaft/hackathonorganiser/`, templates under
`src/main/resources/templates/`. Surefire runs `*Test` (unit); Failsafe runs `*IT` (integration).

---

## Phase 1: Setup (Schema)

**Purpose**: Database shape must exist before any code can compile against it. No project
initialisation is needed — this is an existing module and the feature adds **no new dependency**.

- [X] T001 Append the `tasks` table and its `tasks_done_created_idx` index to `src/main/resources/schema.sql`, following data-model.md "Task — new `tasks` table" (nullable `event_destination_id` with `ON DELETE SET NULL`, `rule_name` snapshot, `uuidv7()` default id)
- [X] T002 Append the `task_title_pattern` and `task_default_assignee_user_id` columns to `event_destinations`, then drop and recreate `event_destinations_type_fields_check` with the third `TASK` branch, in `src/main/resources/schema.sql`

**Note**: T001 and T002 touch the same file and are not parallel. T002 is the one that makes a
`TASK` row insertable at all — without the widened CHECK constraint, every Task Rule save fails.

**Checkpoint**: Schema supports Task Rules and Tasks.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Entities, enum constants, and repository access that every user story depends on.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T003 [P] Add the `TASK` constant to `src/main/java/net/fabcelhaft/hackathonorganiser/event/EventDestinationType.java` with a javadoc line pointing at spec.md FR-001
- [X] T004 [P] Add `taskTitlePattern` and `taskDefaultAssigneeUserId` fields plus accessors to `src/main/java/net/fabcelhaft/hackathonorganiser/event/EventDestination.java`
- [X] T005 [P] Create the `Task` entity as `@Table("tasks")` in `src/main/java/net/fabcelhaft/hackathonorganiser/task/Task.java` with fields id, eventDestinationId, ruleName, eventType, title, assigneeUserId, done, doneAt, createdAt
- [X] T006 [P] Create `src/main/java/net/fabcelhaft/hackathonorganiser/task/TaskRepository.java` extending `ReactiveCrudRepository<Task, UUID>`
- [X] T007 [P] Add the derived query `Flux<User> findByOrganiserTrueOrderByDisplayNameAsc()` to `src/main/java/net/fabcelhaft/hackathonorganiser/user/UserRepository.java`
- [X] T008 Extend the manual row mapper in `findEnabledDestinationsFor` to select and populate `task_title_pattern` and `task_default_assignee_user_id`, in `src/main/java/net/fabcelhaft/hackathonorganiser/event/EventDestinationService.java`

**Note on T008**: this is the quiet one. `findEnabledDestinationsFor` hand-maps every column; a
`TASK` destination returned by it with a null pattern would fire and silently produce fallback
titles for every Event. data-model.md "Query shapes" flags it.

**Checkpoint**: Foundation ready — user story implementation can begin.

---

## Phase 3: User Story 1 — Turn Events into Tasks Automatically (Priority: P1) 🎯 MVP

**Goal**: An Organiser configures a Task Rule with a wildcard title pattern; matching Events
automatically produce Tasks, visible on a new Tasks screen.

**Independent Test**: Configure one Task Rule for a single Event Type with a pattern containing at
least one wildcard, trigger that Event through normal use, and confirm a Task appears on
`/organiser/tasks` with the wildcard replaced by the Event's real value.

### Tests for User Story 1 ⚠️ Write these FIRST and confirm they FAIL

- [X] T009 [P] [US1] Unit tests for pattern validation in `src/test/java/net/fabcelhaft/hackathonorganiser/task/TitlePatternScannerTest.java` — cover every rejection row in contracts/title-pattern.md (empty, unclosed `{{`, empty path, leading/trailing/doubled dot) and confirm a single `{` stays literal (FR-007, FR-009)
- [X] T010 [P] [US1] Unit tests for resolution in `src/test/java/net/fabcelhaft/hackathonorganiser/task/TitlePatternResolverTest.java` covering **both** tables in contracts/title-pattern.md: (a) *"Walking a path"* — a digit segment indexes an array, a non-digit segment on an array is missing, an out-of-range index is missing, a digit segment on an object resolves as a field name; and (b) *"What gets written"* — string, number, boolean, timestamp, identifier, object, array, missing, **plus an explicit JSON-null case asserting the output is empty and never the string `null`** (FR-010, FR-011a, FR-012, research.md §3)
- [X] T011 [US1] Unit tests for title assembly in `src/test/java/net/fabcelhaft/hackathonorganiser/task/TitlePatternResolverTest.java` — trim, blank-to-fallback, and truncation to 500 characters ending in an ellipsis, in that order (FR-014, FR-015, research.md §5)
- [X] T012 [P] [US1] Integration test `src/test/java/net/fabcelhaft/hackathonorganiser/event/TaskDestinationSenderIT.java` — an enabled Task Rule produces one Task per Event occurrence with the resolved title; a disabled Rule produces none; two identical Events produce two separate Tasks; **a Rule carrying a default assignee produces an assigned Task, and a Rule without one produces an unassigned Task** (FR-005, FR-016, FR-019)
- [X] T013 [US1] Integration test in `src/test/java/net/fabcelhaft/hackathonorganiser/event/TaskDestinationSenderIT.java` asserting the triggering action still succeeds when Task creation fails, with no user-facing error. Verify `TaskDestinationSender.send`'s chain with **`StepVerifier`**, asserting it completes empty rather than signalling an error on a failing write — Constitution Development Workflow §4 requires `StepVerifier` for non-trivial reactive chains (FR-018, SC-003)
- [X] T014 [P] [US1] Extend `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/eventdestination/EventDestinationManagementIT.java` — creating a `TASK` destination starts disabled, a malformed pattern is rejected without discarding input, and a Rule with zero Event Types saves successfully (FR-003, FR-006, FR-007)
- [X] T015 [US1] Extend `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/eventdestination/EventDestinationManagementIT.java` — deleting a Task Rule removes its undone Tasks and keeps its done ones, while disabling it removes neither (FR-002a)

### Implementation for User Story 1

- [X] T016 [P] [US1] Implement the shared left-to-right scanner in `src/main/java/net/fabcelhaft/hackathonorganiser/task/TitlePatternScanner.java` — one pass used by both validation and rendering so the two can never disagree (research.md §4)
- [X] T017 [US1] Implement `src/main/java/net/fabcelhaft/hackathonorganiser/task/TitlePatternResolver.java` — walk the `JsonNode`, array-index on digit segments only when the current node is an array, emit only when `isValueNode() && !isNull()`, then trim → fallback → truncate (research.md §3, §5). Depends on T016
- [X] T018 [US1] Create `src/main/java/net/fabcelhaft/hackathonorganiser/task/TaskService.java` with `createFromEvent(EventDestination rule, EventType eventType, String resolvedTitle)` and `deleteUndoneForRule(UUID)`. `createFromEvent` MUST persist `ruleName` as a snapshot **and copy the Rule's `taskDefaultAssigneeUserId` onto the new Task's `assigneeUserId`**, leaving it null when the Rule has no default (FR-005, FR-013, FR-017, research.md §7)
- [X] T019 [US1] Implement `src/main/java/net/fabcelhaft/hackathonorganiser/event/TaskDestinationSender.java` with the same `Mono<Void> send(EventDestination, String jsonBody)` contract as the other two senders; parse `jsonBody` with an `ObjectMapper` and never signal an error (research.md §2, §6). Depends on T017, T018
- [X] T020 [US1] Add the third `TASK` branch to `dispatch` in `src/main/java/net/fabcelhaft/hackathonorganiser/event/EventPublisher.java`, leaving the detached `.subscribe(...)` dispatch untouched (FR-018). Depends on T019
- [X] T021 [US1] Add the `TASK` branch to `validateTypeFields` and persist the two new fields in `create`/`update`/`applyTypeFields`/`clearTypeFields`, in `src/main/java/net/fabcelhaft/hackathonorganiser/event/EventDestinationService.java` (FR-003, FR-007). Depends on T016
- [X] T022 [US1] Extend `delete` in `src/main/java/net/fabcelhaft/hackathonorganiser/event/EventDestinationService.java` to call `TaskService.deleteUndoneForRule` for a `TASK` row before deleting it (FR-002a). Depends on T018, T021
- [X] T023 [US1] Add `countUndoneForRule(UUID)` to `src/main/java/net/fabcelhaft/hackathonorganiser/task/TaskService.java` for the delete confirmation count (FR-002a). Depends on T018
- [X] T024 [US1] Read the `task_title_pattern` and `task_default_assignee_user_id` form fields, place the Organiser list in the model for the assignee picker, and supply the per-row undone count to the list view, in `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/eventdestination/EventDestinationController.java` (contracts/organiser-ui.md). Depends on T021, T023
- [X] T025 [P] [US1] Add the `TASK` option, the `taskFields` group with wildcard help text and a worked example, and extend `toggleTypeFields()` to a three-way switch that also hides Credential for `TASK`, in `src/main/resources/templates/organiser/event-destinations/form.html` (FR-008, FR-033)
- [X] T026 [P] [US1] Show the title pattern in the Connection cell for a `TASK` row, add the inert marker for any *enabled* handler with no Event Types **across all three types**, and add the `data-confirm` delete message naming the undone count, in `src/main/resources/templates/organiser/event-destinations/list.html` (FR-032, FR-032a, FR-002a)
- [X] T027 [P] [US1] Add exactly one new nav entry, "Tasks", linking to `/organiser/tasks` in `src/main/resources/templates/organiser/fragments/layout.html` (FR-020)
- [X] T028 [US1] Add `findUndone()` / `findAll()` list queries ordered `created_at DESC` to `src/main/java/net/fabcelhaft/hackathonorganiser/task/TaskService.java` (FR-028). Depends on T018
- [X] T029 [US1] Create `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/task/TaskController.java` with `GET /organiser/tasks` returning `Mono<Rendering>` (FR-020, FR-030). Depends on T028
- [X] T030 [US1] Create a read-only `src/main/resources/templates/organiser/tasks/list.html` — title with created time as secondary text beneath it, rendered via plain `th:text` on the `Instant` per research.md §11. Style with Pico CSS semantic defaults and the existing `src/main/resources/static/css/app.css` classes only (`actions`, `outline secondary`, `outline danger`, `flash-message`) — **no new CSS framework and no new stylesheet**, per Constitution Principle IV (FR-021a). Depends on T029

**Checkpoint**: Events create Tasks and Tasks are visible. MVP complete and demoable.

---

## Phase 4: User Story 2 — Assign and Complete Tasks (Priority: P2)

**Goal**: Each Task row carries an assignee control, a Save button, and a Done button.

**Independent Test**: With Tasks present, change one row's assignee, press Save, reload, and confirm
it persisted; then press Done and confirm the Task is recorded as done.

### Tests for User Story 2 ⚠️ Write these FIRST and confirm they FAIL

- [X] T031 [P] [US2] Unit tests in `src/test/java/net/fabcelhaft/hackathonorganiser/task/TaskServiceTest.java` — marking an already-done Task done and reopening an already-undone one both succeed without error. Assert each conditional-update chain with **`StepVerifier`** (zero affected rows completes normally), per Constitution Development Workflow §4 (FR-027, research.md §12)
- [X] T032 [P] [US2] Integration test `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/task/TaskManagementIT.java` — assign then reload persists; clearing the assignee unassigns; Done then Reopen round-trips (FR-024, FR-025, FR-026)
- [X] T033 [US2] Integration test in `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/task/TaskManagementIT.java` — a Task whose assignee no longer holds the Organiser role renders as unassigned and the list still loads (Edge Cases, research.md §9)

### Implementation for User Story 2

- [X] T034 [US2] Add `assign(UUID, UUID)`, `markDone(UUID)`, and `reopen(UUID)` to `src/main/java/net/fabcelhaft/hackathonorganiser/task/TaskService.java` as conditional updates (`WHERE id = :id AND done = …`), treating zero affected rows as success (FR-024, FR-025, FR-026, FR-027)
- [X] T035 [US2] Load the Organiser list once per render and expose it to every row, and filter a stale assignee to unassigned, in `src/main/java/net/fabcelhaft/hackathonorganiser/task/TaskService.java` (FR-021, research.md §9). Depends on T034
- [X] T036 [US2] Add `POST /organiser/tasks/{id}/assign`, `/done`, and `/reopen`, each returning `Mono<Rendering>` and redirecting back with the current filter preserved, in `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/task/TaskController.java` (contracts/organiser-ui.md). Depends on T034
- [X] T037 [US2] Add the assignee `<select>`, Save button, and Done/Reopen button to each row in `src/main/resources/templates/organiser/tasks/list.html`, keeping the table at exactly four columns (FR-021). Depends on T036
- [X] T038 [US2] Give every per-row control an `aria-label` naming its Task's title, so a screen reader does not hear "Save, Save, Save", in `src/main/resources/templates/organiser/tasks/list.html` (FR-031). Depends on T037
- [X] T039 [US2] Add the completion time to the secondary text beneath a done Task's title in `src/main/resources/templates/organiser/tasks/list.html` (FR-021a). Depends on T037

**Checkpoint**: User Stories 1 and 2 both work independently.

---

## Phase 5: User Story 3 — Focus on Outstanding Work (Priority: P3)

**Goal**: The list shows only undone Tasks by default, with an option to include done ones.

**Independent Test**: With both done and undone Tasks present, open the list with no filter chosen
and confirm only undone Tasks appear; switch the filter and confirm done ones appear.

### Tests for User Story 3 ⚠️ Write these FIRST and confirm they FAIL

- [X] T040 [US3] Integration test in `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/task/TaskManagementIT.java` — the default view lists only undone Tasks, and `?show=done` includes done ones (FR-022, FR-023). Not parallel: shares this file with US2's T032/T033
- [X] T041 [US3] Integration test in `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/task/TaskManagementIT.java` — an action taken from the done-inclusive view returns to that view, not the default (FR-023)
- [X] T042 [US3] Integration test in `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/task/TaskManagementIT.java` — with more than 200 done Tasks, exactly 200 render and the cut-off notice appears; and two identically-titled Tasks are distinguishable by their secondary text (FR-028a, FR-021a)

### Implementation for User Story 3

- [X] T043 [US3] Add the filter to the list queries in `src/main/java/net/fabcelhaft/hackathonorganiser/task/TaskService.java` — undone-only with no cap, done-inclusive with `LIMIT 201` so the 201st row signals the cut-off (FR-022, FR-028a, research.md §10)
- [X] T044 [US3] Read the `show` request parameter and pass both the rows and the was-cut-off flag to the view, in `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/task/TaskController.java` (FR-022, FR-023). Depends on T043
- [X] T045 [US3] Add the filter control above the table and carry the current `show` value as a hidden field on all three row forms, in `src/main/resources/templates/organiser/tasks/list.html` (FR-023). Depends on T044
- [X] T046 [US3] Add the cut-off notice and the empty state to `src/main/resources/templates/organiser/tasks/list.html` (FR-028a, FR-029). Depends on T044

**Checkpoint**: All three user stories independently functional.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T047 [P] Create `src/test/java/net/fabcelhaft/hackathonorganiser/a11y/TaskListAccessibilityIT.java` following the existing `EventDestinationAccessibilityIT` Playwright + axe pattern, covering both the default and done-inclusive views (FR-031, SC-008)
- [X] T048 [P] Add javadoc to every new class citing the spec requirements it implements, matching the house style in `EventPublisher` and `EventPayloadFactory`, across `src/main/java/net/fabcelhaft/hackathonorganiser/task/` and `src/main/java/net/fabcelhaft/hackathonorganiser/event/TaskDestinationSender.java`
- [X] T049 Verify list responsiveness at volume per SC-006 — seed 500 undone Tasks and confirm the default view and its Save/Done actions behave as at 10, in `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/task/TaskManagementIT.java`
- [X] T050 Run the full manual walkthrough and every edge case in [quickstart.md](./quickstart.md), including the Thymeleaf visual smoke-test the constitution's Development Workflow item 3 requires before the feature is considered complete

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies — start immediately
- **Foundational (Phase 2)**: depends on Setup — **blocks all user stories**
- **User Story 1 (Phase 3)**: depends on Foundational
- **User Story 2 (Phase 4)**: depends on Foundational; needs T030's template to layer onto
- **User Story 3 (Phase 5)**: depends on Foundational; needs T030's template to layer onto
- **Polish (Phase 6)**: depends on the stories being delivered

### User Story Dependencies

The three stories layer onto one shared template rather than being fully independent, which is a
direct consequence of the spec's four-column table: US1 creates
`organiser/tasks/list.html` read-only, US2 adds the row controls, US3 adds the filter. **US2 and US3
can be built in parallel** once T030 exists, since they touch different regions of that file — but
they are not fully independent of US1 in the way the template's ideal assumes. Each still passes its
own independent test criterion.

### Within Each User Story

- Tests are written and **must fail** before implementation (Constitution Principle V)
- Entities before services, services before controllers, controllers before templates

### Parallel Opportunities

- **Phase 2**: T003–T007 are five different files — fully parallel. T008 is separate
- **US1 tests**: three parallel tracks, one per test class — T009 / T010 / T012 / T014 start
  together; T011 follows T010, T013 follows T012, T015 follows T014 (each pair shares a file)
- **US1 templates**: T025, T026, T027 are three different templates — fully parallel
- **US2 tests**: T031 and T032 — parallel; T033 follows T032 (shares `TaskManagementIT`)
- **US3 tests**: T040, T041, T042 all extend `TaskManagementIT` — **sequential**, or written as one
  commit
- **Polish**: T047 and T048 — parallel

Tasks sharing a file never carry `[P]`, even when they are logically independent.

---

## Parallel Example: User Story 1 tests

```bash
# Four independent test classes start together:
Task: "TitlePatternScannerTest — validation rejections (T009)"
Task: "TitlePatternResolverTest — resolution table incl. JSON null (T010)"
Task: "TaskDestinationSenderIT — Event produces Task (T012)"
Task: "EventDestinationManagementIT — TASK create and validate (T014)"

# Then each track continues in its own file:
#   T010 → T011   (assembly order, same TitlePatternResolverTest)
#   T012 → T013   (failure isolation, same TaskDestinationSenderIT)
#   T014 → T015   (delete cascade, same EventDestinationManagementIT)
```

```bash
# Run only the integration tests past a failing unit stage:
./mvnw verify -Dtest=NoSuchUnitTest -Dit.test='TaskDestinationSenderIT,EventDestinationManagementIT'
```

---

## Implementation Strategy

### MVP First (User Story 1 only)

1. Phase 1: Setup — T001, T002
2. Phase 2: Foundational — T003–T008 (**blocks everything**)
3. Phase 3: User Story 1 — T009–T030
4. **STOP and VALIDATE**: configure a Rule, fire an Event, see the Task
5. Demoable: the whole point of the feature works end to end

### Incremental Delivery

1. Setup + Foundational → foundation ready
2. US1 → Events create visible Tasks → **MVP**
3. US2 → Tasks become workable (assign, complete)
4. US3 → the list stays usable at volume
5. Polish → accessibility, docs, the quickstart walkthrough

---

## Notes

- **Four tasks carry outsized risk.** T010 must include the JSON-null case: Jackson's
  `isValueNode()` returns `true` for `NullNode` and `asText()` returns `"null"`, so the naive
  implementation writes the word "null" into titles. T008 is easy to forget and would make every
  fired Rule produce fallback titles. T002's CHECK constraint, if missed, fails every Task Rule save.
  T018 must copy the Rule's default assignee onto the Task — without it the field is configurable,
  storable, and silently ignored, which is the one functional hole `/speckit-analyze` found (FR-005).
- **`StepVerifier` is required, not preferred.** Constitution Development Workflow §4 mandates it for
  non-trivial reactive chains; T013 and T031 name it explicitly. 22 existing test classes already use
  it, so there is a house pattern to follow.
- **T026 deliberately touches all three handler types.** FR-032a's inert marker is the feature's one
  intentional change to existing Kafka and HTTP POST rows, decided in clarification and recorded in
  the spec's Assumptions.
- No task adds a dependency to `pom.xml` — the feature introduces none.
- `[P]` tasks touch different files with no dependency on incomplete work.
- Commit after each task or logical group; stop at any checkpoint to validate a story.
