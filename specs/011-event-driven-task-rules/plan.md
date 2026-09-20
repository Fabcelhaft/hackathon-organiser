# Implementation Plan: Event-Driven Task Rules

**Branch**: `011-event-driven-task-rules` | **Date**: 2026-09-19 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/011-event-driven-task-rules/spec.md`

## Summary

Organisers gain a third kind of Event handler beside Kafka and HTTP POST: a **Task Rule**, which
creates a to-do item inside the application instead of sending the Event outward. A Rule carries a
title pattern whose `{{dotted.path}}` wildcards are filled from the Event's own JSON when it fires,
so proposing a Topic named "Robot Arm" against the pattern `Review new topic: {{topic.name}}`
produces a Task titled `Review new topic: Robot Arm`. Tasks are worked from one new Organiser
screen — a four-column table of title, assignee, Save, and Done, showing only undone Tasks unless
asked otherwise.

Technically this is mostly *reuse*, not new machinery. A Task Rule is a third
`EventDestinationType` in the existing `event_destinations` table (research.md §1), which means the
existing list, form, service, unique-name index, stale-write guard, and Event Type subscription
table all serve it unchanged — FR-034 is satisfied by not writing code. Task creation slots in as a
third `*DestinationSender` behind the existing `EventPublisher`, inheriting its detached
fire-and-forget dispatch, so **no triggering service changes at all** and FR-018/SC-003 hold by
construction.

Two mechanisms are genuinely new. Wildcards resolve against the *already-serialized* JSON envelope
rather than the in-memory payload map (research.md §2), which makes FR-011a's "exactly as it appears
in the Event's data" true by construction instead of by re-implementing Jackson's value rendering.
And a single hand-written left-to-right scanner drives both validation and rendering (research.md
§4), because a regex structurally cannot report the unclosed wildcard FR-007 must reject.

One table is added (`tasks`), two nullable columns and a widened CHECK constraint are added to
`event_destinations`, and **no new dependency** is introduced.

## Technical Context

**Language/Version**: Java 25 (existing project baseline, `pom.xml`)

**Primary Dependencies**: Spring Boot 4.1.1 — `spring-boot-starter-webflux`,
`spring-boot-starter-data-r2dbc` (Postgres R2DBC driver), `spring-boot-starter-thymeleaf`,
`spring-boot-starter-oauth2-client`. **No new runtime dependency.** Jackson
(`jackson-databind`, `jackson-datatype-jsr310`) is already present and already used by
`EventPublisher`; this feature reuses it for wildcard resolution.

**Storage**: PostgreSQL (R2DBC). One new table `tasks`; two new nullable columns
(`task_title_pattern`, `task_default_assignee_user_id`) and one widened CHECK constraint on the
existing `event_destinations`. DDL appended to the idempotent `src/main/resources/schema.sql` — no
migration framework exists in this project and none is introduced.

**Testing**: JUnit 5 + Mockito (unit), `WebTestClient` (controller/integration), `StepVerifier`
(non-trivial reactive chains), Testcontainers Postgres, Playwright + axe (accessibility) — all
already configured. **No new test dependency.**

**Target Platform**: Linux server (existing containerised Spring Boot deployment)

**Project Type**: Web application — single server-rendered Spring Boot module, no separate frontend

**Performance Goals**: The default undone view responds identically at 500 outstanding Tasks as at
10; the done-inclusive view is bounded at 200 rows regardless of accumulated history (SC-006,
FR-028a). Task creation adds nothing to the latency of the action that triggered the Event (SC-003).

**Constraints**: Task creation must never delay, block, or fail its triggering user action (FR-018)
— guaranteed by reusing `EventPublisher`'s existing detached dispatch rather than adding a new path.
Title resolution must never throw: every failure mode (missing path, JSON null, structure, blank
result, over-long result) has a defined non-throwing outcome (FR-012, FR-014, FR-015).

**Scale/Scope**: One new table, one new controller, one new service, one new sender, one new
template, one new nav entry; three existing files extended (`EventDestinationType`,
`EventDestinationService`, `EventPublisher`) and three existing templates changed. Thousands of Tasks
over a hackathon's lifetime.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | How this feature complies |
|---|---|---|
| **I — Spring Boot Native Only** | PASS | No new dependency of any kind. Wildcard resolution uses Jackson, already on the classpath via `spring-boot-starter-*` and already used by `EventPublisher`. No third-party templating or expression library is introduced for the pattern language. |
| **II — Reactive-First (WebFlux)** | PASS | Every new controller method returns `Mono<Rendering>`. Task persistence uses R2DBC via `DatabaseClient`/`ReactiveCrudRepository`. Task creation runs on `EventPublisher`'s existing detached Reactor pipeline — no blocking call is introduced on a Reactor thread, and the single JSON parse per TASK destination is CPU-only. |
| **III — Thymeleaf Server-Side Rendering** | PASS | One new server-rendered template plus three changed ones. The filter, Save, Done, and Reopen actions are POST → redirect → GET navigations. The only JavaScript is the type-switching `toggleTypeFields()` already in the form and the native `confirm()` wiring already used by `organiser/content-pages/list.html`. |
| **IV — Pico CSS** | PASS | The Task table, filter control, and inert marker use Pico's semantic defaults and the existing `app.css` classes (`actions`, `outline secondary`, `outline danger`, `flash-message`). No new CSS framework. |
| **V — Test-First (NON-NEGOTIABLE)** | PASS | Tasks are ordered failing-test-first. Unit tests cover the pattern scanner and resolver — including the `NullNode` case research.md §3 identifies. Integration tests use `WebTestClient` (never `MockMvc`) with Testcontainers, matching `EventDestinationManagementIT`. Accessibility is covered by a Playwright + axe test alongside the existing a11y suite. |
| **Workflow — `void` controllers forbidden** | PASS | All new handlers return `Mono<Rendering>`. |
| **Workflow — Thymeleaf visual smoke-test** | PASS | Required before done; the manual walkthrough in quickstart.md is that smoke-test. |

**Gate result: PASS — no violations, Complexity Tracking not required.**

### Re-check after Phase 1 design

Re-evaluated against data-model.md, both contracts, and quickstart.md. Still **PASS**. Phase 1 added
no dependency, no blocking call, no client-side rendering, and no CSS framework. Two design choices
were made specifically to stay inside the constitution:

- **No JSONPath library** for wildcard resolution (research.md §3). A hand-written walk over
  `JsonNode` keeps Principle I's dependency surface at zero cost, and the feature needs a fraction of
  what such a library offers.
- **No modal component** for FR-002a's delete confirmation (research.md §7). The native `confirm()`
  pattern already proven in feature 008 keeps Principle III intact.

## Project Structure

### Documentation (this feature)

```text
specs/011-event-driven-task-rules/
├── plan.md                      # This file
├── spec.md                      # Feature specification (39 FRs, 8 SCs)
├── research.md                  # Phase 0 — 12 decisions, all grounded in codebase precedent
├── data-model.md                # Phase 1 — schema, state transitions, query shapes
├── quickstart.md                # Phase 1 — validation guide
├── contracts/
│   ├── title-pattern.md         # Phase 1 — the wildcard language (user-facing contract)
│   └── organiser-ui.md          # Phase 1 — routes, form fields, view changes
├── checklists/
│   └── requirements.md          # Spec quality checklist, 16/16
└── tasks.md                     # Phase 2 output (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

```text
src/main/java/net/fabcelhaft/hackathonorganiser/
├── event/
│   ├── EventDestinationType.java        # CHANGED — add TASK constant
│   ├── EventDestination.java            # CHANGED — two new fields
│   ├── EventDestinationService.java     # CHANGED — TASK validation branch; delete removes undone Tasks
│   ├── EventPublisher.java              # CHANGED — third dispatch branch
│   └── TaskDestinationSender.java       # NEW — resolves the title, creates the Task
├── task/
│   ├── Task.java                        # NEW — @Table("tasks") entity
│   ├── TaskRepository.java              # NEW — ReactiveCrudRepository
│   ├── TaskService.java                 # NEW — list, assign, done, reopen, delete-undone-for-rule
│   ├── TitlePatternScanner.java         # NEW — shared scan for validation and rendering
│   └── TitlePatternResolver.java        # NEW — JsonNode walk, assembly, fallback, truncation
├── organiser/
│   ├── task/TaskController.java         # NEW — /organiser/tasks
│   └── eventdestination/
│       └── EventDestinationController.java  # CHANGED — TASK form fields, assignee options, delete count
└── user/UserRepository.java             # CHANGED — findByOrganiserTrueOrderByDisplayNameAsc

src/main/resources/
├── schema.sql                           # CHANGED — tasks table, 2 columns, widened CHECK
└── templates/organiser/
    ├── tasks/list.html                  # NEW — the four-column table
    ├── event-destinations/list.html     # CHANGED — TASK row, inert marker, delete confirm
    ├── event-destinations/form.html     # CHANGED — Task type option and field group
    └── fragments/layout.html            # CHANGED — one new nav entry

src/test/java/net/fabcelhaft/hackathonorganiser/
├── task/
│   ├── TitlePatternScannerTest.java     # NEW — unit: validation cases
│   ├── TitlePatternResolverTest.java    # NEW — unit: resolution table, NullNode, truncation
│   └── TaskServiceTest.java             # NEW — unit: idempotent done/reopen
├── event/TaskDestinationSenderIT.java   # NEW — integration: Event → Task
├── organiser/task/TaskManagementIT.java # NEW — integration: list, filter, assign, done, reopen
└── a11y/TaskListAccessibilityIT.java    # NEW — Playwright + axe
```

**Structure Decision**: The existing package-by-feature layout is followed. Task Rule *configuration*
stays in the `event` package because a Rule is an Event Destination (research.md §1); the Task
*entity and workflow* get a new `task` package, with its Organiser view under `organiser/task` to
match `organiser/eventdestination`, `organiser/customfield`, and the rest.

## Key risks and where they are handled

| Risk | Where it bites | Handled by |
|---|---|---|
| Jackson's `NullNode` renders as the word `null` | A title reading `Contact null` — explicitly forbidden | research.md §3; unit test required |
| A regex cannot detect an unclosed `{{` | FR-007 silently accepts a malformed pattern | research.md §4 — one shared scanner |
| `ON DELETE CASCADE` cannot express FR-002a's split | Done Tasks wrongly deleted, or the Rule undeletable | data-model.md — `ON DELETE SET NULL` + explicit undone delete |
| `ON DELETE SET NULL` erases which Rule made a done Task | Contradicts FR-017 | `rule_name` snapshot, mirroring the audit `subject_label` precedent |
| The existing CHECK constraint rejects a `TASK` row | Every Task Rule save fails | data-model.md — third branch, drop-and-recreate |
| `findEnabledDestinationsFor`'s manual row mapper | New columns silently null on every fired Rule | data-model.md, Query shapes — mapper must be extended |
| Repeated identical row controls | Screen reader hears "Save, Save, Save" (FR-031) | contracts/organiser-ui.md — per-row `aria-label` |
| A later refactor "tidying" the detached subscribe | FR-018/SC-003 quietly broken | quickstart.md — explicit test, not trust |

## Deliberate scope note

FR-032a's inert-handler marker applies to **all three** handler types, so this feature makes one
visible change to existing Kafka and HTTP POST rows. That was decided in clarification and is
recorded in the spec's Assumptions. Nothing else about those two handlers — fields, validation,
delivery behaviour, stored data — is touched.

## Phase status

- [x] Phase 0 — research complete, all unknowns resolved ([research.md](./research.md))
- [x] Phase 1 — design complete ([data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md))
- [ ] Phase 2 — task breakdown (`/speckit-tasks`, not produced by this command)
