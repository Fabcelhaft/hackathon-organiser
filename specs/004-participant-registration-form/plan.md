# Implementation Plan: Participant Registration Form, Profile Fields & Directory

**Branch**: `004-participant-registration-form` | **Date**: 2026-08-29 | **Spec**: [spec.md](spec.md)

## Summary

Replace 003's bare-record self-registration with a real profile form: Custom Field Definitions gain a
single-select type and a built-in, ISO-3166-backed Country field, each independently flaggable Public
(visible to others) and/or Overview (a directory table column). Registration and self-edit both submit
through this same form, validated identically, with a capacity cap (max registrations, Active-only,
race-safe) and a permanent "Not Participated" lockout enforced server-side. A new Participants directory
(audience-configurable: Organisers only / Organisers+Participants / everyone) lists registered Participants
alphabetically with Overview columns and links to a detail view that resolves Public/Overview/Skill-visibility
flags per viewer. Everything server-rendered with Thymeleaf/Pico CSS on the existing WebFlux + R2DBC + OIDC
stack from 002/003, adding zero new runtime dependencies: the ISO 3166 country list comes from the JDK's
`java.util.Locale`, and the registration-capacity race is closed with a Postgres advisory lock inside a
reactive transaction rather than any new library.

## Technical Context

**Language/Version**: Java 25 (per `pom.xml`, unchanged from 002/003)

**Primary Dependencies**: No new runtime dependency. Reuses Spring Boot 4.1.1's
`spring-boot-starter-webflux`, `spring-boot-starter-data-r2dbc` (its auto-configured
`R2dbcTransactionManager` / `TransactionalOperator` is newly *used* by this feature, not newly added),
`spring-boot-starter-thymeleaf`, `spring-boot-starter-oauth2-client`, `spring-boot-starter-actuator`, plus
003's `commonmark`/OWASP sanitizer (untouched by this feature) and its test-scope Playwright + axe-core pair
(reused for this feature's new screens, see research.md §7). The ISO 3166 country catalog is read from the
JDK's own `java.util.Locale.getISOCountries()`/`Locale.of(...)`/`getDisplayCountry(Locale.ENGLISH)` — no
country-list library.

**Storage**: PostgreSQL via R2DBC (`postgres:18.6-alpine`), same `spring.sql.init.mode=always` idempotent
`schema.sql` convention as 002/003. `custom_field_definitions` gains `public`, `overview`, `enabled` columns
plus two new `field_type` values (`SINGLE_SELECT`, `COUNTRY`); one `COUNTRY` row is seeded once and guarded
by a partial unique index. `organiser_settings` gains `max_registrations`, `self_edit_enabled`,
`skill_visibility_enabled`, `participants_directory_audience`. No new tables: `custom_field_value_options`
(003's schema, actually 002's) is reused for `SINGLE_SELECT` values (business-rule-capped to one row) and
`custom_field_values.free_text_value` is reused to hold a Country's selected ISO alpha-2 code.

**Testing**: JUnit 5 + Mockito (unit, service-layer), `WebTestClient` + Testcontainers PostgreSQL
(integration, `*ManagementIT`/`*IT` naming per 002/003's pattern), plus 003's Playwright + axe-core `a11y.*IT`
suite extended to this feature's new screens (registration form, self-edit form, directory table, detail
view, the extended organiser settings/custom-field controls) per FR-037–045/SC-009.

**Target Platform**: Linux container (existing devcontainer / `docker-compose.yml`), unchanged.

**Project Type**: Web — single Spring Boot WebFlux application, server-rendered Thymeleaf, no separate
frontend build (Constitution III).

**Performance Goals**: No numeric SLA in the spec; inherits the constitution's non-blocking-reactive mandate.
Hackathon-event scale (tens to low hundreds of concurrent authenticated users), consistent with 002/003.

**Constraints**: WCAG 2.1 AA on all new UI (FR-037–045), including an accessible combobox/listbox pattern for
the searchable Country field (FR-045) built from plain HTML/ARIA + a few lines of vanilla JS — no client-side
framework (Constitution III), matching 003's precedent for the confirmation `<dialog>`. The capacity check
(FR-009, Edge Cases' concurrent-last-slot case) MUST be race-safe under concurrent submissions. Custom Field
values, Skill selections, and Participant status changes made by one registration/edit submission MUST be
atomic (all-or-nothing), so no half-applied record is ever visible (FR-003, Edge Cases).

**Scale/Scope**: Single ongoing hackathon (all four new Organiser Settings fields are columns on the existing
singleton row); 6 user stories, FR-001–FR-045 (45 functional requirements including sub-letters).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Result |
|---|---|---|
| I. Spring Boot Native Only | Zero new dependencies. The ISO 3166 list comes from the JDK (`java.util.Locale`), and the capacity race is closed with `TransactionalOperator` (already auto-configured by `spring-boot-starter-data-r2dbc`) plus a native Postgres `pg_advisory_xact_lock` call — no new library, no alternative DI container. | PASS |
| II. Reactive-First (WebFlux) | All new/changed controllers and services return `Mono`/`Flux`. The advisory-lock + capacity-check + write sequence composes inside one `TransactionalOperator.transactional(...)` reactive chain — no blocking calls introduced. | PASS |
| III. Thymeleaf SSR | All new pages (registration form, self-edit form, directory table, detail view) are server-rendered Thymeleaf. The Country combobox's vanilla JS (filtering an already-server-rendered option list) is not a client-side rendering framework, matching 003's `<dialog>` precedent. | PASS (documented, not a violation) |
| IV. Pico CSS Styling | Continue using Pico CSS exclusively; small custom CSS only for the required/optional field marker, the visible/private badge (FR-020, FR-041), and the accessible combobox's listbox popover positioning — none of which Pico's classless defaults cover. | PASS |
| V. Test-First (NON-NEGOTIABLE) | Every new service/controller method gets a failing unit/integration test first, following 002/003's `XxxServiceTest` (Mockito) / `organiser/xxx/XxxManagementIT` / plain `xxx/XxxManagementIT` (`WebTestClient`) naming. The capacity race gets a dedicated concurrent-request integration test (two simultaneous `POST /register` calls against a max-1 setting). The Playwright + axe-core suite gains new specs for this feature's screens, reusing 003's already-justified tooling rather than adding a new category. | PASS |

No unjustified violations; no new Complexity Tracking entries (unlike 003, this feature introduces no new
test *category* or dependency — it only extends existing ones).

**Post-Phase-1 re-check**: Phase 1 design (data-model.md, contracts/, quickstart.md) confirms every new table
column, route, and template extends an existing pattern (partial unique index for a fixed-cardinality
invariant, `DatabaseClient`-backed composite-key tables, package-by-domain + audience-split web packages,
`CurrentUserModelAdvice`-style shared model attributes) with no `spring-webmvc`-pulling library, blocking I/O,
client-side framework, non-Pico CSS, or untested code path introduced. Gate re-confirmed: PASS.

## Project Structure

### Documentation (this feature)

```text
specs/004-participant-registration-form/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
│   ├── custom-fields-and-country.md
│   ├── organiser-settings.md
│   ├── registration-and-self-edit.md
│   └── participants-directory.md
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

Single Spring Boot module (Constitution I/II/III), same package-by-domain layout 002/003 established. This
feature extends the existing `customfield`, `organisersettings`, and `participant` domain packages, and adds
one new participant-facing web package (`participants`, plural — distinct from the existing singular
`participant` domain package) for routes that must be reachable by non-Organisers per the configurable
directory audience, mirroring how `home`/`info`/`topics` already sit outside `/organiser/**`.

```text
src/main/java/net/fabcelhaft/hackathonorganiser/
├── customfield/                          # EXTENDED
│   ├── CustomFieldType.java              # + SINGLE_SELECT, COUNTRY
│   ├── CustomFieldDefinition.java        # + public, overview, enabled fields
│   ├── CustomFieldService.java           # + setVisibility(), setCountryEnabled(), registrationFields(),
│   │                                        SINGLE_SELECT ≥1-option rule, COUNTRY create/delete blocked
│   └── IsoCountryCatalog.java            # NEW — static ISO 3166 list via java.util.Locale (research.md §1)
├── organisersettings/                    # EXTENDED
│   ├── OrganiserSettings.java            # + maxRegistrations, selfEditEnabled, skillVisibilityEnabled,
│   │                                        participantsDirectoryAudience
│   ├── DirectoryAudience.java            # NEW enum: ORGANISERS_ONLY | ORGANISERS_AND_PARTICIPANTS | ALL_AUTHENTICATED
│   ├── OrganiserSettingsConflictException.java  # NEW — invalid max_registrations (FR-007)
│   └── OrganiserSettingsService.java     # + validated update() for the four new fields
├── participant/                          # EXTENDED
│   ├── ParticipantService.java           # + submitRegistration()/submitSelfEdit() (atomic, transactional),
│   │                                        capacity check (advisory lock), Not-Participated lockout guards,
│   │                                        visibility-resolved detail/list read models
│   └── RegistrationCapacityReachedException.java  # NEW — distinct from ParticipantConflictException (FR-035)
├── participants/                         # NEW — participant-facing directory + own-profile + registration
│   │                                        routes; sits outside /organiser/** (audience is configurable,
│   │                                        not fixed to ROLE_ORGANISER)
│   ├── ParticipantsDirectoryAccessPolicy.java  # NEW — single source of truth for the audience check, used
│   │                                              by both the controllers below and the nav model advice
│   ├── RegistrationController.java       # GET/POST /register — form-driven register/reactivate (FR-001–010)
│   ├── ProfileController.java            # GET /profile (read-only own view), GET/POST /profile/edit (self-edit)
│   └── ParticipantsDirectoryController.java  # GET /participants (table), GET /participants/{id} (detail)
├── home/HomeController.java              # EXTENDED — "Register" link now navigates to GET /register instead
│   │                                        of an immediate POST; Not-Participated lockout messaging (FR-006a)
├── organiser/
│   ├── customfield/CustomFieldController.java  # EXTENDED — SINGLE_SELECT option management, Country
│   │                                              enable/disable toggle, Public/Overview checkboxes
│   └── settings/OrganiserSettingsController.java  # EXTENDED — max registrations, self-edit, skill-visibility,
│                                                     directory-audience fields
└── web/CurrentUserModelAdvice.java       # EXTENDED — + showParticipantsMenuItem (via
                                             ParticipantsDirectoryAccessPolicy), for the shared nav fragment

src/main/resources/
├── schema.sql            # EXTENDED — custom_field_definitions columns + COUNTRY seed row + partial unique
│                            index; organiser_settings columns + CHECK constraint
└── templates/
    ├── fragments/layout.html                    # EXTENDED — conditional "Participants" nav item
    ├── fragments/profile-fields-form.html        # NEW — shared Custom-Field/Skill/Country field-rendering
    │                                                fragment, included by both register.html and edit.html
    ├── participants/register.html                # NEW
    ├── participants/edit.html                     # NEW
    ├── participants/list.html                     # NEW — directory table
    ├── participants/detail.html                   # NEW — detail view (self, other, organiser variants)
    ├── organiser/custom-fields/{list,form}.html   # EXTENDED — SINGLE_SELECT + Country + Public/Overview UI
    └── organiser/settings/form.html               # EXTENDED — four new fields

src/main/resources/static/js/
└── country-select.js     # NEW — vanilla JS filtering for the accessible Country combobox (FR-045)

src/test/java/net/fabcelhaft/hackathonorganiser/
├── customfield/{CustomFieldServiceTest,IsoCountryCatalogTest}.java   # EXTENDED / NEW
├── organisersettings/OrganiserSettingsServiceTest.java               # EXTENDED
├── participant/ParticipantServiceTest.java                           # EXTENDED — incl. capacity-race case
├── organiser/customfield/CustomFieldManagementIT.java                # EXTENDED
├── organiser/settings/SettingsManagementIT.java                      # EXTENDED
├── participants/{RegistrationManagementIT,ProfileManagementIT,
│                 ParticipantsDirectoryManagementIT}.java             # NEW
└── a11y/{RegistrationAccessibilityIT,ParticipantsDirectoryAccessibilityIT}.java  # NEW
```

**Structure Decision**: Continue the existing single-module, package-by-domain layout, extending
`customfield`/`organisersettings`/`participant` in place rather than forking new "v2" packages. The one new
top-level package, `participants` (plural), holds every route a non-Organiser might legitimately reach under
this feature's configurable directory audience — exactly the same reasoning 003 used to keep `home`/`info`/
`topics` outside `/organiser/**`, so `SecurityConfig`'s `/organiser/**` → `ROLE_ORGANISER` rule keeps being the
single source of truth for what's Organiser-only, while `ParticipantsDirectoryAccessPolicy` is the single
source of truth for the *configurable* audience check, reused by both the directory controllers and the nav
model advice so the menu item and the enforced access can never drift apart (Edge Cases: direct-URL access
must be denied exactly when the menu item is hidden).

## Complexity Tracking

*No entries — this feature introduces no new dependency, test category, or architectural pattern beyond what
002/003 already established; see Constitution Check above.*
