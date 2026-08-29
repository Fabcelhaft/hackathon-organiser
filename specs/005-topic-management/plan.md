# Implementation Plan: Topic Management, Group Formation & Compliance

**Branch**: `005-topic-management` | **Date**: 2026-08-29 | **Spec**: [spec.md](spec.md)

## Summary

Any Participant can propose a Topic with a name, description, and needed Skills (extending the existing
propose/edit flow onto 002's already-built `topic_skills` machinery). The Home Page replaces its current
name/description/author list with a capped, fullness-sorted 3-column table (Name / participant count / Skills
the viewer offers); a new Topic Overview page shows every visible Topic with its author, participant count,
needed Skills, and Compliance status. A self-service "Join" action creates a Topic's Group on first join and
grows it on subsequent joins — race-safe under a Postgres advisory lock keyed on the Topic, the same mechanism
004 already established for a different capacity limit — subject to an instance-wide Compliance Ruleset
(mandatory Maximum, optional Minimum, zero or more Custom-Field diversity requirements, AND-combined) that an
Organiser configures and can override per-Group. A separate Organiser toggle independently gates whether
joining is available at all, and a third toggle controls whether the Skills columns show only still-needed
Skills or every associated Skill. Everything server-rendered with Thymeleaf/Pico CSS on the existing WebFlux +
R2DBC + OIDC stack from 002–004, adding zero new runtime dependencies: the join race is closed with the exact
advisory-lock-inside-a-`TransactionalOperator`-transaction pattern 004 already introduced, and compliance
evaluation is a plain read-time computation over data (Group membership, Custom Field values) this codebase
already stores.

## Technical Context

**Language/Version**: Java 25 (per `pom.xml`, unchanged from 002–004)

**Primary Dependencies**: No new runtime dependency. Reuses Spring Boot 4.1.1's `spring-boot-starter-webflux`,
`spring-boot-starter-data-r2dbc` (its auto-configured `R2dbcTransactionManager`/`TransactionalOperator`,
already exposed as a bean by 004's `TransactionalOperatorConfig`, is reused unchanged for the Join race guard —
research.md §2), `spring-boot-starter-thymeleaf`, `spring-boot-starter-oauth2-client`,
`spring-boot-starter-actuator`, plus 003's test-scope Playwright + axe-core pair (reused for this feature's new
screens, research.md §9).

**Storage**: PostgreSQL via R2DBC (`postgres:18.6-alpine`), same `spring.sql.init.mode=always` idempotent
`schema.sql` convention as 002–004. `organiser_settings` gains `max_group_members`, `min_group_members`,
`topic_joining_enabled`, `skill_display_mode`; `groups` gains `compliance_override`; one new table,
`compliance_diversity_requirements`, holds the Compliance Ruleset's optional per-field rules. No change to
`topics`, `topic_skills`, `groups`(existing columns), or `group_members` — Topic Skills already had their table
(002); this feature only extends who is allowed to write to it (research.md §6).

**Testing**: JUnit 5 + Mockito (unit, service-layer), `WebTestClient` against a real `SecurityWebFilterChain` +
Testcontainers PostgreSQL (integration, `*ManagementIT`/`*IT` naming per 002–004's pattern, including a
dedicated concurrent-join race test mirroring 004's concurrent-registration test), plus 003's Playwright +
axe-core `a11y.*IT` suite extended to this feature's new screens (Home Page table + Join action, Topic
proposal/edit Skill picker, Topic Overview, compliance-settings and override screens) per FR-021–027/SC-008.

**Target Platform**: Linux container (existing devcontainer / `docker-compose.yml`), unchanged.

**Project Type**: Web — single Spring Boot WebFlux application, server-rendered Thymeleaf, no separate frontend
build (Constitution III).

**Performance Goals**: No numeric SLA in the spec; inherits the constitution's non-blocking-reactive mandate.
Hackathon-event scale (tens to low hundreds of concurrent authenticated users), consistent with 002–004.

**Constraints**: WCAG 2.1 AA on all new UI (FR-021–FR-027), matching 003's target and 004's precedent for
extending the existing Playwright + axe-core suite rather than adding new tooling. The Join action's capacity
enforcement (FR-013, Edge Cases' concurrent-last-slot case) and first-Group-creation (Edge Cases' concurrent-
first-joiner case) MUST both be race-safe under concurrent requests — closed with one Postgres advisory lock
keyed on the Topic id (research.md §2), the same mechanism and rationale as 004's registration-capacity guard.
Compliance status MUST reflect the current Compliance Ruleset and Group membership on every read, with no
caching and no deployment required after a settings change (FR-011/FR-017/FR-020d's "no deployment required"
bar, already established by 003/004's toggles).

**Scale/Scope**: Single ongoing hackathon (the Compliance Ruleset's max/min/toggles are four more columns on
the existing `organiser_settings` singleton row; diversity requirements are a short, Organiser-curated list,
not a high-cardinality table); 8 user stories, FR-001–FR-027 (27 functional requirements including
sub-letters).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Result |
|---|---|---|
| I. Spring Boot Native Only | Zero new dependencies. The Join race is closed with `TransactionalOperator` (already auto-configured, already exposed as a bean by 004) plus a native Postgres `pg_advisory_xact_lock` call, keyed differently than 004's but the identical mechanism — no new library, no alternative DI container. | PASS |
| II. Reactive-First (WebFlux) | All new/changed controllers and services return `Mono`/`Flux`. The advisory-lock + capacity/override check + Group-create-or-grow sequence composes inside one `TransactionalOperator.transactional(...)` reactive chain — no blocking calls introduced. Compliance evaluation is a pure, non-blocking read composed the same way. | PASS |
| III. Thymeleaf SSR | All new pages (Home Page's revised topic table, the Skill picker on the propose/edit form, Topic Overview, the compliance settings screen, the Group override control, the two new settings toggles) are server-rendered Thymeleaf; no client-side framework introduced. | PASS |
| IV. Pico CSS Styling | Continue using Pico CSS exclusively; small custom CSS only for the Compliance status badge's non-color text/icon treatment (FR-025) and the Skill multi-select/diversity-requirement row layout — neither covered by Pico's classless defaults. | PASS |
| V. Test-First (NON-NEGOTIABLE) | Every new service/controller method gets a failing unit/integration test first, following 002–004's `XxxServiceTest` (Mockito) / `organiser/xxx/XxxManagementIT` / plain `xxx/XxxManagementIT` (`WebTestClient`) naming. The Join race gets a dedicated concurrent-request integration test (two simultaneous joins against a Topic one slot from Maximum), mirroring 004's concurrent-registration test. The Playwright + axe-core suite gains new specs for this feature's screens, reusing 003's already-justified tooling. | PASS |

No unjustified violations; no new Complexity Tracking entries (this feature introduces no new dependency, test
category, or architectural pattern beyond what 002–004 already established — see Complexity Tracking below).

**Post-Phase-1 re-check**: Phase 1 design (data-model.md, contracts/, quickstart.md) confirms every new table
column, route, and template extends an existing pattern (singleton-settings-row extension, a real one-to-many
table for a payload-carrying collection, `DatabaseClient`-backed composite-key tables left untouched, an
advisory lock inside a `TransactionalOperator` transaction, package-by-domain + audience-split web packages,
computed-at-read-time status mirroring how Pending-visibility is already computed) with no `spring-webmvc`-
pulling library, blocking I/O, client-side framework, non-Pico CSS, or untested code path introduced. Gate
re-confirmed: PASS.

## Project Structure

### Documentation (this feature)

```text
specs/005-topic-management/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md         # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
│   ├── topic-proposal-and-skills.md
│   ├── home-and-topic-overview.md
│   ├── join-action.md
│   ├── compliance-settings-and-override.md
│   └── organiser-toggles.md
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

Single Spring Boot module (Constitution I/II/III), same package-by-domain layout 002–004 established. This
feature extends the existing `topic`, `group`, and `organisersettings` domain packages in place, adds one new
domain package (`compliance`, for the Custom Field diversity requirement collection and the compliance
evaluator — research.md §3/§5), and adds routes to the existing self-service `topics` web package (Join,
Topic Overview) since both must be reachable by any authenticated/Participant user, not just Organisers.

```text
src/main/java/net/fabcelhaft/hackathonorganiser/
├── topic/                                 # EXTENDED
│   ├── TopicService.java                  # propose()/updateAsAuthor() gain skillIds (research.md §6)
│   ├── TopicDiscoveryService.java         # NEW — shared Home Page / Topic Overview read model (research.md §7)
│   ├── TopicJoinService.java              # NEW — eligibility gate in front of GroupService.join (data-model.md)
│   └── TopicJoinConflictException.java    # NEW — distinct join-rejection messages (FR-026)
├── group/                                 # EXTENDED
│   ├── Group.java                         # + complianceOverride
│   └── GroupService.java                  # + join() (race-safe, research.md §2), setComplianceOverride(),
│                                             activeMemberCount()
├── organisersettings/                     # EXTENDED
│   ├── OrganiserSettings.java             # + maxGroupMembers, minGroupMembers, topicJoiningEnabled,
│   │                                         skillDisplayMode
│   ├── SkillDisplayMode.java              # NEW enum: STILL_NEEDED_ONLY | ALL_ASSOCIATED
│   └── OrganiserSettingsService.java      # + validated update() for the four new fields
├── compliance/                            # NEW
│   ├── ComplianceDiversityRequirement.java
│   ├── ComplianceDiversityRequirementRepository.java
│   ├── ComplianceStatus.java              # enum: COMPLIANT | NOT_COMPLIANT | COMPLIANT_OVERRIDE
│   ├── ComplianceConflictException.java
│   └── ComplianceService.java             # evaluate() (research.md §5) + requirement CRUD
├── customfield/CustomFieldService.java    # EXTENDED — deleteDefinition()'s reference guard gains a third
│                                             countReferencing() term (research.md §8)
├── home/HomeController.java               # EXTENDED — Home Page topic table now calls
│                                             TopicDiscoveryService.findOpenTopicsForHomePage(), + Join action
├── topics/                                # EXTENDED (self-service, outside /organiser/**)
│   ├── TopicSelfServiceController.java    # EXTENDED — propose/edit forms gain the Skill picker
│   ├── TopicJoinController.java           # NEW — POST /topics/{id}/join
│   └── TopicOverviewController.java       # NEW — GET /topics/overview
└── organiser/
    ├── settings/OrganiserSettingsController.java  # EXTENDED — Topic-joining-enabled + Skill Display Mode
    ├── group/GroupController.java                 # EXTENDED — compliance-override set/clear action
    └── compliance/ComplianceController.java        # NEW — Maximum/Minimum fields + diversity requirement
                                                        add/remove list (Organiser-only, FR-019)

src/main/resources/
├── schema.sql             # EXTENDED — organiser_settings/groups columns + CHECK constraints;
│                             compliance_diversity_requirements table (data-model.md)
└── templates/
    ├── fragments/layout.html                 # EXTENDED — static "Topic Overview" nav item (every authenticated
    │                                            user, unconditional — no access-policy class needed)
    ├── home/index.html                       # EXTENDED — 3-column topic table + Join action + live region
    ├── topics/form.html                      # EXTENDED — Skill picker (mirrors organiser/topics/form.html)
    ├── topics/overview.html                  # NEW — Topic Overview table
    ├── organiser/settings/form.html          # EXTENDED — two new toggles
    ├── organiser/groups/detail.html          # EXTENDED — compliance status badge + override control
    └── organiser/compliance/form.html        # NEW — Maximum/Minimum + diversity requirement list

src/test/java/net/fabcelhaft/hackathonorganiser/
├── topic/{TopicServiceTest,TopicDiscoveryServiceTest,TopicJoinServiceTest}.java     # EXTENDED / NEW
├── group/GroupServiceTest.java                                                      # EXTENDED — incl. join race case
├── organisersettings/OrganiserSettingsServiceTest.java                              # EXTENDED
├── compliance/ComplianceServiceTest.java                                            # NEW
├── customfield/CustomFieldServiceTest.java                                          # EXTENDED — third reference source
├── topics/{TopicSelfServiceManagementIT,TopicJoinManagementIT,
│           TopicOverviewManagementIT}.java                                          # EXTENDED / NEW
├── organiser/{settings/SettingsManagementIT,group/GroupManagementIT,
│              compliance/ComplianceManagementIT}.java                               # EXTENDED / NEW
└── a11y/{HomepageAccessibilityIT,TopicOverviewAccessibilityIT,
         ComplianceSettingsAccessibilityIT}.java                                     # EXTENDED / NEW
```

**Structure Decision**: Continue the existing single-module, package-by-domain layout, extending `topic`/
`group`/`organisersettings`/`customfield` in place rather than forking new "v2" packages — the same reasoning
003 and 004 both already applied when they extended those exact packages. The one new domain package,
`compliance`, holds a genuinely new entity (the diversity requirement collection) and its evaluation logic;
it depends on `organisersettings` (for max/min) and reads `group`/`customfield`-owned tables directly via
`DatabaseClient`, but nothing in `group`/`topic`/`organisersettings` depends back on it except through the
thin `ComplianceStatus` return value, keeping the dependency direction one-way. Every new route a non-Organiser
might legitimately reach (Join, Topic Overview) lands in the existing `topics` (plural) self-service package,
outside `/organiser/**`, exactly the same reasoning 003/004 already used to keep that package where it is;
every Organiser-only compliance/override control lands under `organiser/**`, so `SecurityConfig`'s existing
`/organiser/**` → `ROLE_ORGANISER` rule keeps being the single source of truth for what's Organiser-only, with
no new access-policy class needed (unlike 004's `ParticipantsDirectoryAccessPolicy`) since Topic Overview's
visibility is unconditional, not a configurable audience.

## Complexity Tracking

*No entries — this feature introduces no new dependency, test category, or architectural pattern beyond what
002–004 already established (a second advisory-lock-guarded capacity check, a second real one-to-many table
alongside `custom_field_options`, and a second read-time-computed status alongside Pending-visibility); see
Constitution Check above.*
