# Implementation Plan: Sortable Custom Fields

**Branch**: `009-custom-fields-sortable` | **Date**: 2026-09-14 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/009-custom-fields-sortable/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command; its definition describes the execution workflow.

## Summary

Every Custom Field Definition gains a whole-number `sort_index` (default 0) that the Organiser sets on the
existing create/edit form. Everywhere Custom Fields are listed — Organiser management list, Organiser
Participant detail, compliance rule dropdown, registration/self-edit form, Participants directory columns,
Participant detail — they are ordered by `sort_index` ascending, then label case-insensitively, then creation
time (the clarified tie-break). The technical approach is deliberately narrow: one new column appended to
`schema.sql` exactly as `content_pages.sort_index` already is; one shared `Comparator` applied inside
`CustomFieldService.findAll()`/`registrationFields()` so that there is a single ordering point; and the two
places in `ParticipantService` that still read `CustomFieldDefinitionRepository.findAll()` directly are
re-pointed at the service so no view can bypass the order. Invalid sort-index input is rejected server-side
with the same 200 form re-render the form already uses for every other conflict (the existing
`ContentPageController` precedent silently coerces garbage to 0, which the spec explicitly forbids here).
No audit, no drag-and-drop, no option reordering.

## Technical Context

**Language/Version**: Java 25 (existing project baseline, `pom.xml`)

**Primary Dependencies**: Spring Boot 4.1.1 — `spring-boot-starter-webflux`, `spring-boot-starter-data-r2dbc`
(Postgres R2DBC driver), `spring-boot-starter-thymeleaf`; Pico CSS (existing asset). No new dependency.

**Storage**: PostgreSQL (R2DBC). One new column `custom_field_definitions.sort_index integer NOT NULL DEFAULT
0`, added via `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` appended to the idempotent `src/main/resources/schema.sql`
— the same mechanism 004 used for `public`/`overview`/`enabled` on this very table. No migration framework.

**Testing**: JUnit 5 + Mockito (unit, `StepVerifier` for reactive chains), `WebTestClient` + Testcontainers
Postgres (`*IT` classes) — unchanged; `mvn -B verify` in CI.

**Target Platform**: Linux server (existing containerised Spring Boot deployment)

**Project Type**: Web application — single server-rendered Spring Boot module

**Performance Goals**: None new. Ordering is an in-memory sort of a list that is tens of items long at most
(one row per Custom Field Definition), applied once per page render — SC-005 ("no user-perceivable delay") is
trivially met.

**Constraints**: Exactly one ordering definition in the codebase (research.md §2) so every view agrees by
construction (SC-002). Ordering must never change *which* fields a view shows (FR-013) — it is applied to the
same streams the views already consume, before their existing `filter(...)` calls. Sort index edits must bypass
the `field_type` lock and the COUNTRY-row restrictions (FR-006). Invalid input must be a rejection, not a
coercion (FR-005).

**Scale/Scope**: One column, one comparator, one new form input and one new list column, two service-method
signature extensions (`create`/`update` gain an `int sortIndex`), two call-site redirects in
`ParticipantService`, no new routes, no new templates. Hackathon-scale data (a handful to a few dozen Custom
Fields).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Result |
|---|---|---|
| I. Spring Boot Native Only | New column mapped by the existing Spring Data R2DBC entity (`sortIndex` → `sort_index`, same as `ContentPage`); ordering via a plain `java.util.Comparator` on the existing `Flux` — no new library, no manual wiring | PASS |
| II. Reactive-First (WebFlux) | `Flux.sort(Comparator)` is a non-blocking Reactor operator; all touched service/controller methods keep returning `Mono`/`Flux`; form data still read via `ServerWebExchange.getFormData()` | PASS |
| III. Thymeleaf SSR | One new `<input type="number">` on the existing organiser form and one new `<td>` on the existing list — both server-rendered; every other view is unchanged because its model list simply arrives already ordered | PASS |
| IV. Pico CSS | No new markup beyond a labelled input and a table column, both styled by Pico's semantic defaults; no CSS added | PASS |
| V. Test-First | Failing unit tests for the comparator/service and failing `WebTestClient` ITs for the form (valid, invalid, empty index), the list order, and the participant-facing orders are written before implementation; existing tests whose `create`/`update` signatures change are updated in the same red step | PASS |

No violations. Complexity Tracking is not needed.

## Project Structure

### Documentation (this feature)

```text
specs/009-custom-fields-sortable/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
│   └── custom-field-ordering.md
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

Single Spring Boot module (existing layout). No new packages, routes, or templates — only additions to files
that already own Custom Field definitions and the views that list them:

```text
src/main/java/net/fabcelhaft/hackathonorganiser/
├── customfield/
│   ├── CustomFieldDefinition.java          # MODIFIED: + int sortIndex (column sort_index) + DISPLAY_ORDER comparator
│   └── CustomFieldService.java             # MODIFIED: findAll()/registrationFields() apply DISPLAY_ORDER;
│                                            #   create(..., int sortIndex) / update(..., int sortIndex)
├── participant/ParticipantService.java     # MODIFIED: loadCustomFieldValueViews() and findDirectoryListing()
│                                            #   read customFieldService.findAll() instead of the repository
└── organiser/customfield/CustomFieldController.java
                                             # MODIFIED: parse sort_index (blank → 0, non-integer → error
                                             #   re-render), pass to service, expose sortIndex to the form
                                             #   (0 on /new, current value on /{id}/edit and on re-renders)

# UNCHANGED but now ordered for free (they consume the service streams above):
#   organiser/compliance/ComplianceController.java   (availableFields via customFieldService.findAll())
#   organiser/participant/ParticipantController.java (detail via participantService.findDetail())
#   participants/ParticipantsDirectoryController.java (findDirectoryListing / findDetailForViewer)
#   participants/RegistrationController.java, ProfileController.java (registrationFieldViews*)

src/main/resources/
├── schema.sql                              # MODIFIED: append ALTER TABLE custom_field_definitions
│                                            #   ADD COLUMN IF NOT EXISTS sort_index integer NOT NULL DEFAULT 0
└── templates/organiser/custom-fields/
    ├── form.html                           # MODIFIED: + "Sort index" number input (name=sort_index)
    └── list.html                           # MODIFIED: + "Sort index" column

# UNCHANGED templates (order comes from the model): fragments/profile-fields-form.html,
#   participants/list.html, participants/detail.html, organiser/participants/detail.html,
#   organiser/compliance/form.html

src/test/java/net/fabcelhaft/hackathonorganiser/
├── customfield/CustomFieldServiceTest.java                 # MODIFIED: ordering tests; new create/update arity
├── participant/ParticipantServiceTest.java                 # MODIFIED: stubs move from repository.findAll()
│                                                            #   to customFieldService.findAll(); order assertions
├── organiser/customfield/CustomFieldManagementIT.java      # MODIFIED: sort_index create/edit/invalid/empty/list-order
├── participants/ParticipantsDirectoryManagementIT.java     # MODIFIED: column order + detail order assertion
├── participants/ProfileManagementIT.java                   # MODIFIED: form field order assertion
└── organiser/compliance/ComplianceManagementIT.java        # MODIFIED: dropdown order assertion
```

**Structure Decision**: Single-module web application (unchanged from 001–006). The feature touches only the
`customfield` package, the one service that reads definitions outside it (`ParticipantService`), the one
controller that writes definitions (`CustomFieldController`), and that controller's two templates. All other
listing views are left untouched on purpose: they already receive their field lists from
`CustomFieldService`/`ParticipantService`, so making those two sources ordered is what delivers "all views" with
the smallest possible diff (research.md §2).

## Post-Design Constitution Check

*Re-checked after Phase 1 (research.md, data-model.md, contracts/, quickstart.md).*

The design adds no dependency, no blocking call, no client-side rendering, no stylesheet, and no untested path.
The only behavioural deviation from an existing precedent — rejecting a malformed `sort_index` instead of
coercing it to 0 as `ContentPageController.parseIntOrZero` does — is required by FR-005 and reuses the form's
existing error re-render path rather than adding a new one (research.md §4). All five gates remain PASS.

## Complexity Tracking

Not applicable — no Constitution Check violations were identified at either gate.
