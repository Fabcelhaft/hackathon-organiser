# Implementation Plan: Topic Improvements — Upvoting, Author Visibility & Reference IDs

**Branch**: `012-topic-improvements-upvoting` | **Date**: 2026-09-26 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/012-topic-improvements-upvoting/spec.md`

## Summary

Three additive changes to the existing Topic domain (002/005): (1) a one-per-user, retractable,
anonymous upvote on each Topic — an admin-toggleable feature that also becomes a tiebreaker
(behind fullness) in the Home Page's existing sort — persisted in a new `topic_upvotes`
composite-key association table; (2) a permanent, sequential numeric reference ID assigned to a
Topic the moment it is Approved, with a one-time, idempotent startup backfill for Topics approved
before this feature shipped, sourced from the existing Audit Trail (006); (3) a new author-display
field on the participant-facing Topic Details view (`/topics/{id}`), which shows no author
information today — unlike the Topic Overview (all-Topics list), which already has one and needs
no change. All three extend
`TopicDiscoveryService`'s shared read model (`OpenTopicRow`/`OverviewRow`/`TopicDetailView`)
rather than introducing a parallel query path, and all new UI is server-rendered Thymeleaf/Pico,
consistent with the constitution.

## Technical Context

**Language/Version**: Java 25 (project's configured `java.version`)

**Primary Dependencies**: Spring Boot 4.1.1 (WebFlux, Data R2DBC, Thymeleaf, Security/OIDC) — no new dependency

**Storage**: PostgreSQL via R2DBC; DDL lives in `src/main/resources/schema.sql`, additive `CREATE
TABLE IF NOT EXISTS` / `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`, re-run on every startup
(`spring.sql.init.mode=always`) — this feature's backfill (Story 2) rides the same mechanism

**Testing**: JUnit 5 + Mockito (unit), `WebTestClient`-based integration tests (existing `*IT.java`
convention under `src/test/java/.../topics/`), `StepVerifier` for reactive chains — constitution
Principle V (test-first) applies unchanged

**Target Platform**: Linux server (containerised, per existing `Dockerfile`/`docker-compose.yml`)

**Project Type**: Web service (single Spring Boot module, server-rendered) — no frontend/backend split

**Performance Goals**: No new goal beyond the existing Home Page/Topic Overview response-time
expectations already established by features 003/005; upvote cast/withdraw is a single-row
insert/delete plus a redirect (SC-001), not a background job

**Constraints**: Anonymity is a hard constraint (spec FR-003, SC-003) — no code path, including the
existing Organiser-facing Audit Trail (006), may ever expose which specific user cast an upvote

**Scale/Scope**: Same instance scale as the rest of the application (a single hackathon's Topics/
Participants) — no sharding or pagination concerns beyond what 005 already established

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Result |
|---|---|---|
| I. Spring Boot Native Only | New table access via `DatabaseClient` (existing pattern for `topic_skills`-style composite-key tables); no new library | PASS |
| II. Reactive-First (WebFlux) | New service methods (`TopicUpvoteService`, `TopicService.approve` extension) return `Mono`/`Flux`; new controller (`TopicUpvoteController`) mirrors `TopicJoinController`'s `Mono<Rendering>` shape | PASS |
| III. Thymeleaf SSR | Upvote control, upvote counts, reference ID, and the new Topic Details author row are all rendered via existing Thymeleaf fragments (Home Page, Topic Overview, Topic Details); no client framework | PASS |
| IV. Pico CSS | Upvote button reuses Pico's existing button/badge semantics; no new CSS framework | PASS |
| V. Test-First | Failing `WebTestClient` ITs for upvote/unupvote and reference-ID-on-approve precede implementation, per existing `topics/*IT.java` convention | PASS |

No violations — Complexity Tracking is not needed.

## Project Structure

### Documentation (this feature)

```text
specs/012-topic-improvements-upvoting/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
│   ├── topic-upvote-action.md
│   └── topic-reference-number.md
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

Single Spring Boot module (existing layout — Option 1, package-by-feature, no new top-level
directories):

```text
src/main/java/net/fabcelhaft/hackathonorganiser/
├── topic/
│   ├── Topic.java                     # +referenceNumber field
│   ├── TopicService.java              # approve(): assigns referenceNumber via new sequence
│   ├── TopicUpvoteService.java        # NEW — cast/withdraw/counts/viewerUpvoted, DatabaseClient-backed (no entity class, same as TopicSkill-style pure associations)
│   └── TopicDiscoveryService.java     # +upvoteCount/viewerHasUpvoted per row (US1); Home Page tiebreak comparator (US1); +authorDisplayName on TopicDetailView (US3)
├── topics/
│   └── TopicUpvoteController.java     # NEW — POST /topics/{id}/upvote, POST /topics/{id}/unupvote
├── organisersettings/
│   ├── OrganiserSettings.java         # +topicUpvotingEnabled field
│   └── OrganiserSettingsService.java  # update(...) +topicUpvotingEnabled param
└── organiser/settings/
    └── OrganiserSettingsController.java  # +checkboxValue("topic_upvoting_enabled")

src/main/resources/
├── schema.sql                          # +topic_upvotes table, +topics.reference_number (+seq +backfill), +organiser_settings.topic_upvoting_enabled
└── templates/
    ├── home/index.html                 # +upvote control/count column (US1), reference number badge (US2)
    ├── topics/overview.html            # +upvote control/count column (US1), reference number (US2) — no author change (US3 is Topic Details only)
    ├── topics/detail.html              # +upvote control/count (US1), reference number (US2), +author row as the first Topic Info row (US3, a new field this view lacks today)
    └── organiser/settings/form.html    # +topic_upvoting_enabled checkbox

src/test/java/net/fabcelhaft/hackathonorganiser/
├── topics/
│   ├── TopicUpvoteManagementIT.java       # NEW (US1)
│   ├── TopicOverviewManagementIT.java     # extended: upvotes (US1), reference number (US2) — not author (unchanged)
│   └── TopicDetailManagementIT.java       # extended: upvotes (US1), reference number (US2), author row (US3)
└── home/HomeControllerIT.java             # extended: upvotes (US1), reference number (US2)
```

**Structure Decision**: Extends the existing package-by-feature layout under
`net.fabcelhaft.hackathonorganiser` — `topic` (domain/services), `topics` (self-service
controllers), `organisersettings`/`organiser.settings` (admin toggle) — introducing exactly one
new class (`TopicUpvoteService`, no entity type — same as the existing `topic_skills`-style
associations) and one new controller (`TopicUpvoteController`), matching the precedent
`TopicJoinService`/`TopicJoinController` set for feature 005's self-service actions. No new
top-level module or directory.

## Complexity Tracking

*No Constitution Check violations — table not needed.*
