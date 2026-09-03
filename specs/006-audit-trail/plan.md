# Implementation Plan: Audit Trail for Topics and Participants

**Branch**: `006-audit-tables-each` | **Date**: 2026-09-03 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/006-audit-trail/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command; its definition describes the execution workflow.

## Summary

Every create/edit/status-change/join/leave/disband against a Topic or Participant gets recorded, in a single
shared `audit_entries` table, with who did it, what happened, in what capacity (Organiser vs. standard user),
and which record it affects. **Group has no audit trail of its own** — every event that affects a Group
(formation, membership changes, disbanding, compliance override) is recorded against that Group's Topic
instead, since a Group always belongs to exactly one Topic. A Topic join writes two entries — Topic side and
Participant side — sharing a common action id so the pairing is verifiable, not just inferred. History is
organiser-only, both via the UI (an "Audit" button on each of the Topic, Group, and Participant detail pages —
the Group's opens its Topic's trail) and server-side on the retrieval route itself, and is never fetched until
that button is used. The technical approach reuses this codebase's existing patterns end to end:
`schema.sql`-idempotent DDL for the new table, `DatabaseClient`-free `ReactiveCrudRepository` access (a normal
`@Id`-tagged entity, not a composite-key association table), explicit `AuditService.record(...)` calls threaded
through each mutating service method inside its existing reactive/transactional chain (no AOP, no event bus —
nothing new to reconcile with Reactive-First), and the existing `/organiser/**` `ROLE_ORGANISER` path rule in
`SecurityConfig` for FR-005/FR-006's access restriction, with zero new security code required.

## Technical Context

**Language/Version**: Java 25 (existing project baseline, `pom.xml`)

**Primary Dependencies**: Spring Boot 4.1.1 — `spring-boot-starter-webflux`, `spring-boot-starter-data-r2dbc`
(Postgres R2DBC driver), `spring-boot-starter-thymeleaf`, `spring-boot-starter-oauth2-client`; Pico CSS
(existing vendored/CDN asset, no new frontend dependency)

**Storage**: PostgreSQL (R2DBC), one new table `audit_entries`, DDL appended to the existing
`src/main/resources/schema.sql` idempotent script — no migration framework is in use anywhere in this project

**Testing**: JUnit 5 + Mockito (unit), `WebTestClient` (controller/integration), `StepVerifier` (non-trivial
reactive chains) — per the constitution's Test-First principle, unchanged

**Target Platform**: Linux server (existing containerized Spring Boot deployment)

**Project Type**: Web application — single server-rendered Spring Boot module (no separate frontend project)

**Performance Goals**: No new goal beyond the rest of the app's existing server-rendered page loads; SC-001 ("a
few seconds") is satisfied by a single indexed `SELECT ... ORDER BY occurred_at DESC` per audited record

**Constraints**: Every audit write MUST happen inside the same reactive/transactional chain as the mutation it
records — no fire-and-forget event publishing — so that the Topic-join pair (FR-004a, SC-005) is genuinely
atomic with the join itself, not just "usually together." Audit entries are immutable once written (FR-010): no
update/delete repository method is exposed for them, by construction. The audit view is intentionally unbounded
per the resolved clarification — no pagination is in scope. Whenever a single action affects two entities (a
Topic/Group and a Participant, as in join/leave/add-member/remove-member), the concurrency-safety mechanism
MUST acquire a lock scoped to both entities, not just one (research.md §5) — this closes a latent race found
during planning where `GroupService` locked only the Topic, leaving the Participant side of the same action
unguarded.

**Scale/Scope**: One new table, two read routes (Topic and Participant "Audit" views — the Group detail page's
"Audit" action reuses the Topic route, adding no third one), and actor-context threading through the existing
mutating methods of `TopicService`, `GroupService`, and `ParticipantService` — hackathon-scale data volumes
(hundreds of participants/topics per event, not a high-volume production audit log), consistent with every
other table in `schema.sql`.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Result |
|---|---|---|
| I. Spring Boot Native Only | New table via a plain `ReactiveCrudRepository` + `schema.sql`; actor-context threading is plain constructor/method parameters — no new dependency, no manual DI container | PASS |
| II. Reactive-First (WebFlux) | All new/changed service methods return `Mono`/`Flux`; audit writes are chained (`.then(...)`/`.flatMap(...)`) inside the same reactive pipeline as the mutation, several of which already run inside `TransactionalOperator` — no blocking call introduced | PASS |
| III. Thymeleaf SSR | The two new "Audit" views (Topic, Participant — Group's "Audit" link reuses the Topic one) are plain server-rendered Thymeleaf pages reached by a normal link/button (a GET navigation), not a client-side/AJAX panel | PASS |
| IV. Pico CSS | New audit table markup reuses Pico's semantic `<table>`/`<dl>` styling already used on every other organiser detail page — no new CSS framework or bespoke stylesheet | PASS |
| V. Test-First | `AuditService`/`AuditEntryRepository` unit tests (JUnit 5 + Mockito) and `WebTestClient` tests for the two new GET routes plus the Group detail page's link target (including a non-Organiser 403/404 case) are written before implementation, per the existing workflow | PASS |

No violations. Complexity Tracking is not needed.

## Project Structure

### Documentation (this feature)

```text
specs/006-audit-trail/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

Single Spring Boot module (existing layout, no new top-level directories) — one new package for the shared
audit concept, plus small additions to the three existing entity packages/controllers/templates that already
own Topic, Group, and Participant:

```text
src/main/java/net/fabcelhaft/hackathonorganiser/
├── audit/                              # NEW package
│   ├── AuditEntry.java                 # @Table entity (audit_entries)
│   ├── AuditEntryRepository.java       # ReactiveCrudRepository, read-only usage (no update/delete calls)
│   ├── AuditEventType.java             # enum: CREATED, EDITED, STATUS_CHANGED, JOINED, LEFT, DISBANDED, DELETED
│   ├── AuditSubjectType.java           # enum: TOPIC, PARTICIPANT — no GROUP value
│   ├── AuditActor.java                 # record(UUID userId, boolean organiser) — passed into services
│   └── AuditService.java               # record(...) + findForTopic/findForParticipant(...) — no findForGroup;
│                                        #   Group has no audit trail of its own
├── topic/TopicService.java             # MODIFIED: propose/updateAsAuthor/approve(...) take an AuditActor
├── group/GroupService.java             # MODIFIED: create/join/leave/addMember/removeMember/disband/
│                                        #   setComplianceOverride(...) take an AuditActor; every audit write
│                                        #   here targets AuditSubjectType.TOPIC + the Group's topicId, never a
│                                        #   Group-typed subject
├── participant/ParticipantService.java # MODIFIED: register/setStatus/updateSkills/updateCustomFields/
│                                        #   selfRevoke/delete(...) take an AuditActor
├── organiser/topic/TopicController.java       # MODIFIED: new GET /organiser/topics/{id}/audit
└── organiser/participant/ParticipantController.java  # MODIFIED: new GET /organiser/participants/{id}/audit

# organiser/group/GroupController.java gains NO new route: its detail view links directly to
# /organiser/topics/{topicId}/audit (the Group's own topicId), reusing the Topic route as-is.

src/main/resources/
├── schema.sql                          # MODIFIED: append audit_entries DDL (idempotent, per existing convention)
└── templates/organiser/
    ├── audit/list.html                 # NEW fragment: the shared audit-entries table, included by both views below
    ├── topics/detail.html              # MODIFIED: "Audit" button/link + audit fragment
    ├── groups/detail.html              # MODIFIED: "Audit" button/link — th:href to /organiser/topics/{topicId}/audit,
    │                                    #   no fragment inclusion of its own (there is no Group-scoped data to render)
    └── participants/detail.html        # MODIFIED: "Audit" button/link + audit fragment

src/test/java/net/fabcelhaft/hackathonorganiser/
├── audit/AuditServiceTest.java                        # NEW (unit, Mockito)
├── organiser/topic/TopicControllerAuditTest.java       # NEW (WebTestClient) — or added to existing test class
├── organiser/group/GroupControllerAuditTest.java       # NEW (WebTestClient) — asserts the detail page's Audit
│                                                        #   link points at its Topic's audit route (SC-006)
└── organiser/participant/ParticipantControllerAuditTest.java  # NEW (WebTestClient)
```

**Structure Decision**: Single-module web application (unchanged from 001–005) — no new Maven module, no
frontend/backend split. The feature adds one new package (`audit`) for the shared table and its read/write
service, colocates each new "Audit" GET route on the existing controller that already owns that entity's other
organiser routes (matching how `GroupController` already owns `/members`, `/disband`, etc. on itself rather
than a separate controller) — except `GroupController`, which gains no route at all, since Group audit data is
just the Topic's audit data reused — and appends to the existing single `schema.sql` rather than introducing a
migration tool.

## Post-Design Constitution Check

*Re-checked after Phase 1 (research.md, data-model.md, contracts/, quickstart.md).*

Nothing in the Phase 0/1 design introduces a new dependency, a blocking call, a non-Thymeleaf rendering path, a
non-Pico stylesheet, or an untested code path beyond what the initial gate above already covers — the
`audit_entries` table, `AuditService`, and the two new GET routes (Group reusing the Topic one) all follow
existing, already-passing patterns (research.md §1–§9). All five gates remain PASS; no re-justification needed.

## Complexity Tracking

Not applicable — no Constitution Check violations were identified at either gate.
