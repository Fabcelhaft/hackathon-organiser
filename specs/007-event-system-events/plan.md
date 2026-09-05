# Implementation Plan: Event Notification System

**Branch**: `007-event-system-events` | **Date**: 2026-09-03 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/007-event-system-events/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command; its definition describes the execution workflow.

## Summary

Organisers configure any number of named **Event Destinations** (Kafka or HTTP POST) under a new
`/organiser/event-destinations` area, each independently subscribed to any subset of a fixed,
13-entry **Event Type** catalog covering the app's key lifecycle actions (Participant
registration/status changes, User creation, Topic proposal/approval, Topic-Group membership,
Organiser-role changes, and Group formation/disbandment/compliance changes). When a matching
domain occurrence happens, the system builds a JSON **Event** (an event-type identifier plus the
JSON representation of the object(s) involved, reusing existing entity field names 1:1 — for every
Participant-carrying Event Type this now also includes the full associated User and the
Participant's currently-enabled Custom Field definitions/answers, FR-010c/FR-010d) and delivers an
independent copy to every currently enabled, subscribed Destination — asynchronously,
with automatic retry-with-backoff and no dedicated delivery history, exactly as resolved in
spec.md's Clarifications. Technically, this reuses the project's existing patterns wherever a
precedent exists: a plain `@Table` entity + `ReactiveCrudRepository` for `EventDestination`
(mirroring `CustomFieldDefinition`), a `DatabaseClient`-backed pure-association table for its
Event Type subscriptions (mirroring `topic_skills`) — plus one genuinely new mechanism, since no
precedent exists in this codebase: an explicit `updatedAt`-comparison stale-write check for FR-018
(data-model.md "Event Destination" — Concurrency; the existing `*ConflictException` classes are
business-rule validators only, not concurrency guards). A small `EventPublisher` is called from each existing
triggering service method (`ParticipantService`, `TopicService`, `GroupService`, `UserService`,
`HackathonOidcUserService`, `OrganiserSettingsService`, `ComplianceService`) that fires a detached,
non-blocking Reactor pipeline per Destination rather than awaiting it, `WebClient` for HTTP
delivery (already a dependency), and the lean `kafka-clients` library (new dependency, producer
only) for Kafka delivery, bridged into `Mono` via its async callback API so no Reactor thread ever
blocks.

## Technical Context

**Language/Version**: Java 25 (existing project baseline, `pom.xml`)

**Primary Dependencies**: Spring Boot 4.1.1 — `spring-boot-starter-webflux`,
`spring-boot-starter-data-r2dbc` (Postgres R2DBC driver), `spring-boot-starter-thymeleaf`,
`spring-boot-starter-oauth2-client`; **new**: `org.apache.kafka:kafka-clients` (producer-only
Kafka client, research.md §2) — no other new runtime dependency; `WebClient` (already present)
covers HTTP delivery.

**Storage**: PostgreSQL (R2DBC), two new tables — `event_destinations` and
`event_destination_event_types` — DDL appended to the existing `src/main/resources/schema.sql`
idempotent script, no migration framework in use anywhere in this project.

**Testing**: JUnit 5 + Mockito (unit), `WebTestClient` (controller/integration), `StepVerifier`
(non-trivial reactive chains) per the constitution's Test-First principle; **new test-scope
dependency**: `org.testcontainers:kafka` for Kafka-destination integration tests (research.md §8);
HTTP-destination tests use the JDK's built-in `com.sun.net.httpserver.HttpServer` — no new
dependency.

**Target Platform**: Linux server (existing containerized Spring Boot deployment)

**Project Type**: Web application — single server-rendered Spring Boot module (no separate
frontend project)

**Performance Goals**: No new goal beyond the rest of the app's server-rendered page loads; the
defining performance requirement is negative — SC-008 requires the triggering action to add *no*
delay attributable to Event delivery, satisfied by never chaining delivery Monos into a triggering
method's own returned reactive pipeline (research.md §1).

**Constraints**: Event delivery MUST be asynchronous with respect to its triggering domain
occurrence (FR-020a-1) — no triggering service method's `Mono`/`Flux` may await a Destination send.
Each Destination's delivery attempts MUST be independent of every other Destination's (FR-012).
Retries MUST be bounded (FR-020a; research.md §7: 3 retries, 2s initial exponential backoff) with
no Organiser-facing delivery history on exhaustion (FR-020b) — failures are logged only. A
Compliance-Ruleset save (`OrganiserSettingsService.update`, `ComplianceService.addRequirement`/
`removeRequirement`) MUST re-evaluate every active Group and fire `GROUP_COMPLIANCE_CHANGED` only
for the ones whose status actually flips (FR-010b; research.md §5).

**Scale/Scope**: One new organiser-facing CRUD area (Event Destinations: list + create/edit form),
two new tables, a fixed 13-entry Event Type enum, an `EventPublisher` plus two transport senders
(`HttpDestinationSender`, `KafkaDestinationSender`), and `EventPublisher.publish(...)` call sites
added to seven existing service classes — hackathon-scale data volumes (a handful of Destinations,
event bursts in the tens/hundreds around registration/team-formation windows, not a high-volume
production message bus), consistent with every other table in `schema.sql`.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Result |
|---|---|---|
| I. Spring Boot Native Only | New `EventDestination`/`EventDestinationEventType` persistence uses a plain `ReactiveCrudRepository` + `DatabaseClient` (matching `topic_skills`); `kafka-clients` is a lean protocol client (not a web framework or DI container) added because Kafka delivery is a first-class functional requirement (FR-002) — no alternative in the Spring ecosystem avoids adding *some* Kafka client, and this is the minimal-surface option (research.md §2) | PASS |
| II. Reactive-First (WebFlux) | All new/changed service methods return `Mono`/`Flux`; `HttpDestinationSender` uses `WebClient` natively; `KafkaDestinationSender` bridges `kafka-clients`' async callback API into `Mono.create(...)` so no Reactor thread blocks (research.md §2); `EventPublisher.publish(...)` intentionally does **not** return a `Mono` the caller awaits — it triggers a detached, still-non-blocking pipeline, which is the mechanism FR-020a-1 requires, not a violation of "no blocking I/O on Reactor threads" (nothing blocks) | PASS |
| III. Thymeleaf SSR | The new Destination list and create/edit form are plain server-rendered Thymeleaf pages under `/organiser/event-destinations`, reached by normal GET/POST navigation, matching every other organiser CRUD screen (e.g. `organiser/customfield`) | PASS |
| IV. Pico CSS | Reuses Pico's existing semantic `<table>`/`<form>` styling already used on every other organiser list/form page — no new CSS | PASS |
| V. Test-First | `EventDestinationServiceTest`, `EventPublisherTest`, `HttpDestinationSenderTest` (JDK `HttpServer`), `KafkaDestinationSenderIT` (Testcontainers Kafka), and `WebTestClient` tests for the new controller (including a non-Organiser 403 case) are written before implementation, per the existing workflow | PASS |

No violations. Complexity Tracking is not needed.

## Project Structure

### Documentation (this feature)

```text
specs/007-event-system-events/
├── plan.md                       # This file (/speckit.plan command output)
├── research.md                   # Phase 0 output (/speckit.plan command)
├── data-model.md                 # Phase 1 output (/speckit.plan command)
├── quickstart.md                 # Phase 1 output (/speckit.plan command)
├── contracts/                    # Phase 1 output (/speckit.plan command)
│   ├── delivery-transport.md
│   └── event-payloads.md
├── checklists/requirements.md
└── tasks.md                      # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

Single Spring Boot module (existing layout, no new top-level directories) — one new package for
the Event Destination/publishing concept, plus small `EventPublisher.publish(...)` additions to
the existing service classes that already own each triggering occurrence:

```text
src/main/java/net/fabcelhaft/hackathonorganiser/
├── event/                                        # NEW package
│   ├── EventDestination.java                     # @Table entity (event_destinations)
│   ├── EventDestinationType.java                 # enum: KAFKA, HTTP_POST
│   ├── EventDestinationRepository.java            # ReactiveCrudRepository
│   ├── EventDestinationConflictException.java     # business-rule validation errors AND the new
│                                                   #   updatedAt stale-write check (data-model.md, no
│                                                   #   existing precedent for the latter)
│   ├── EventDestinationService.java               # CRUD + event-type-selection via DatabaseClient
│                                                   #   against event_destination_event_types (mirrors
│                                                   #   TopicService's topic_skills handling)
│   ├── EventType.java                             # fixed 13-entry enum (data-model.md)
│   ├── DomainEvent.java                           # record(EventType eventType, Map<String,Object> payload)
│   ├── EventPublisher.java                        # publish(DomainEvent): looks up subscribed+enabled
│                                                   #   Destinations, fires one detached, retried Mono<Void>
│                                                   #   delivery per Destination (research.md §1, §7);
│                                                   #   MODIFIED — new publish(Mono<DomainEvent>) overload
│                                                   #   flat-maps enrichment into the same detached
│                                                   #   pipeline (research.md §10)
│   ├── HttpDestinationSender.java                 # WebClient POST (contracts/delivery-transport.md)
│   ├── KafkaDestinationSender.java                 # kafka-clients producer, cached per Destination id
│                                                   #   (research.md §2), Mono.create bridge
│   ├── EventPayloadFactory.java                    # builds the topic/participant/user/group/customFields
│                                                   #   JSON maps (data-model.md "Event payload shapes");
│                                                   #   MODIFIED — the 5 Participant-event builders now
│                                                   #   return Mono<DomainEvent> (UserRepository +
│                                                   #   CustomFieldService lookups, research.md §10)
│   └── ComplianceChangeEventHook.java              # shared before/after Compliance re-evaluation wrapper
│                                                   #   (research.md §5), injected into OrganiserSettingsService
│                                                   #   and ComplianceService
├── participant/ParticipantService.java            # MODIFIED: selfRegister/register/selfRevoke/changeStatus
│                                                   #   call EventPublisher.publish(Mono<DomainEvent>);
│                                                   #   private loadFieldViews(...) removed in favor of
│                                                   #   customfield.CustomFieldService.currentAnswers(...)
│                                                   #   (research.md §10) — CustomFieldValueView relocated
│                                                   #   there as customfield.CustomFieldAnswer
├── customfield/CustomFieldService.java            # MODIFIED: new currentAnswers(UUID participantId) —
│                                                   #   the Custom Field definition+answer assembly moved
│                                                   #   down from ParticipantService to break the
│                                                   #   event→participant dependency cycle (research.md §10)
├── customfield/CustomFieldAnswer.java             # NEW: record(definition, options, freeTextValue,
│                                                   #   selectedOptionIds) — relocated from
│                                                   #   participant/ParticipantService.CustomFieldValueView
├── topic/TopicService.java                        # MODIFIED: propose/approve call EventPublisher.publish(...)
├── group/GroupService.java                        # MODIFIED: join/leave/addMember/removeMember/disband/
│                                                   #   setComplianceOverride call EventPublisher.publish(...);
│                                                   #   join/leave's participantJoinedTopic/LeftTopic calls
│                                                   #   switch to the Mono<DomainEvent> overload (research.md §10)
├── user/UserService.java                          # MODIFIED: setOrganiser(...) calls EventPublisher.publish(...)
├── security/HackathonOidcUserService.java          # MODIFIED: first-login user creation calls
│                                                   #   EventPublisher.publish(USER_CREATED, ...)
├── organisersettings/OrganiserSettingsService.java # MODIFIED: update(...) wrapped by ComplianceChangeEventHook
├── compliance/ComplianceService.java               # MODIFIED: addRequirement/removeRequirement wrapped by
│                                                   #   ComplianceChangeEventHook
└── organiser/eventdestination/EventDestinationController.java  # NEW: list/create/edit/enable/disable/delete

src/main/resources/
├── schema.sql                          # MODIFIED: append event_destinations +
│                                        #   event_destination_event_types DDL (idempotent)
└── templates/organiser/eventdestination/
    ├── list.html                       # NEW
    └── form.html                       # NEW (create + edit)

docs/events.md                          # NEW (FR-020c) — one consolidated Markdown document
                                         #   covering every Event Type's JSON payload structure and
                                         #   worked example, generated from
                                         #   contracts/event-payloads.md during implementation

src/test/java/net/fabcelhaft/hackathonorganiser/
├── event/
│   ├── EventDestinationServiceTest.java            # NEW (unit, Mockito)
│   ├── EventPublisherTest.java                     # NEW (unit, StepVerifier — asserts detached/non-blocking)
│   ├── HttpDestinationSenderTest.java               # NEW (JDK HttpServer)
│   ├── KafkaDestinationSenderIT.java              # NEW (Testcontainers Kafka)
│   └── EventPayloadFactoryTest.java                # MODIFIED (unit, StepVerifier + Mockito) — asserts the
│                                                   #   5 Participant builders' Mono<DomainEvent> includes
│                                                   #   `user` and `customFields` (FR-010c, FR-010d,
│                                                   #   including the unanswered-field and disabled-field
│                                                   #   edge cases from spec.md)
├── customfield/CustomFieldServiceTest.java         # MODIFIED: covers the relocated currentAnswers(...)
└── organiser/eventdestination/EventDestinationControllerTest.java  # NEW (WebTestClient)
```

**Structure Decision**: Single-module web application (unchanged from 001–006) — no new Maven
module, no frontend/backend split. The feature adds one new package (`event`) for the Destination
CRUD, publishing, and delivery concepts, colocates the new organiser controller under
`organiser/eventdestination` (matching how every other organiser area owns its own controller
subpackage), and appends to the existing single `schema.sql` rather than introducing a migration
tool. The one new runtime dependency (`kafka-clients`) and one new test dependency
(`org.testcontainers:kafka`) are additive, not structural — no existing module boundary changes.

## Post-Design Constitution Check

*Re-checked after Phase 1 (research.md, data-model.md, contracts/, quickstart.md).*

Nothing in the Phase 0/1 design introduces a blocking call, a non-Thymeleaf rendering path, a
non-Pico stylesheet, or an untested code path beyond what the initial gate above already covers.
The two new dependencies (`kafka-clients` runtime, `testcontainers-kafka` test-scope) were the only
open question at gate time and are both justified above (research.md §2, §8) as the
minimal-surface choice for a functional requirement (Kafka Destinations) the constitution does not
prohibit. No `NEEDS CLARIFICATION` remains in Technical Context. Gate: **PASS**, unchanged.

**Addendum (FR-010c/FR-010d)**: The Participant-event builders in `EventPayloadFactory` becoming
`Mono`-returning (research.md §10) stays within Principle II — both new lookups
(`UserRepository.findById`, `CustomFieldService.currentAnswers`) are reactive R2DBC calls, nothing
blocks, and `EventPublisher.publish(Mono<DomainEvent>)` is still a detached, un-awaited
subscription from every caller's perspective, so FR-020a-1/SC-008 are unaffected. Relocating the
Custom Field answer-assembly logic from `ParticipantService` to `CustomFieldService` (research.md
§10) is a same-module refactor with no new package boundary and no constitution implication. Gate:
**PASS**, unchanged.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

No violations — this section is intentionally empty.
