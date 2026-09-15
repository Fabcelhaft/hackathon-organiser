---

description: "Task list for Event Notification System"
---

# Tasks: Event Notification System

**Input**: Design documents from `/specs/007-event-system-events/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/delivery-transport.md,
contracts/event-payloads.md, quickstart.md

**Tests**: Included. The project constitution (`.specify/memory/constitution.md`, Principle V) makes
Test-First Development non-negotiable — every task below that changes behavior has a corresponding test task
sequenced before it. Unit tests use JUnit 5 + Mockito (`*ServiceTest.java`/`*Test.java`, matching
`GroupServiceTest.java`'s existing pattern, plus `StepVerifier` for reactive-chain assertions per the
constitution); integration tests use `WebTestClient` + Testcontainers + `mockOidcLogin()`
(`*ManagementIT.java`/`*IT.java`, matching `GroupManagementIT.java`'s existing pattern) — no new IT
infrastructure is introduced. **Important**: `GroupManagementIT`/`TopicManagementIT`/`ParticipantManagementIT`
already had to upgrade their shared `organiser()`/`standardUser()` login helpers to build a real `User` +
`HackathonOidcUser` via `mockOidcLogin().oidcUser(principal)` (feature 006) because a bare `mockOidcLogin()`
resolves `@AuthenticationPrincipal HackathonOidcUser` to `null`. Any new `*IT` class introduced by this feature
MUST reuse that same real-principal pattern from the start — never a bare `mockOidcLogin()`.

**Organization**: Tasks are grouped by user story (spec.md) to enable independent implementation and testing.
Real method/class names below were confirmed by reading the current codebase (`TopicService.replaceTopicSkills`
for the `DatabaseClient` association-table pattern, `CustomFieldController`/`OrganiserSettingsService` for the
controller/service shape, `ComplianceService.evaluate`/`ComplianceStatus` for compliance, `UserService.setOrganiser`,
`HackathonOidcUserService` for first-login, `organiser/fragments/layout.html` for the nav fragment).

**Note on FR-018 (optimistic concurrency)**: research.md/data-model.md flag that no existing service in this
codebase performs a real `updatedAt`-comparison stale-write check today — `CustomFieldConflictException` /
`GroupConflictException` / `OrganiserSettingsConflictException` are business-rule validators only. This
feature's `EventDestinationConflictException` and its stale-write check (Phase 6 / US4) are new, not a reuse of
an existing mechanism.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (US1, US2, US3, US4)
- Include exact file paths in descriptions

## Path Conventions

Single Spring Boot module (existing layout, no new top-level directories):
`src/main/java/net/fabcelhaft/hackathonorganiser/...`, `src/main/resources/...`,
`src/test/java/net/fabcelhaft/hackathonorganiser/...`, `docs/events.md` (FR-020c, repository root).

---

## Phase 1: Setup

**Purpose**: Establish a clean baseline and pull in this feature's two new dependencies before touching any
feature code.

- [X] T001 Run `mvn test` from the repository root and confirm the existing 002–006 test suite passes with zero
      failures, so any later failure is attributable to this feature's changes
- [X] T002 [P] Add the `org.apache.kafka:kafka-clients` runtime dependency to `pom.xml` (research.md §2 — producer
      only, no `spring-kafka` starter, no transitive `spring-webmvc`)
- [X] T003 [P] Add the `org.testcontainers:kafka` test-scope dependency to `pom.xml`, matching the existing
      `testcontainers-postgresql` pattern already present (research.md §8)

**Checkpoint**: Clean baseline confirmed; new dependencies resolve.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The `EventDestination`/`EventType` persistence shapes every user story builds on.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T004 [P] Append `event_destinations` and `event_destination_event_types` DDL to
      `src/main/resources/schema.sql` per data-model.md: `event_destinations(id uuid PRIMARY KEY DEFAULT
      uuidv7(), name text NOT NULL, type text NOT NULL, enabled boolean NOT NULL DEFAULT false,
      kafka_bootstrap_servers text, kafka_topic text, http_url text, credential text, created_at timestamptz
      NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now())` with a unique index on `name` and
      a `CHECK` constraint enforcing `(type = 'KAFKA' AND kafka_bootstrap_servers IS NOT NULL AND kafka_topic IS
      NOT NULL) OR (type = 'HTTP_POST' AND http_url IS NOT NULL)`; `event_destination_event_types
      (event_destination_id uuid NOT NULL REFERENCES event_destinations (id), event_type text NOT NULL, PRIMARY
      KEY (event_destination_id, event_type))` — no independent id, mirroring `topic_skills`
- [X] T005 [P] Create `EventDestinationType` enum (`KAFKA`, `HTTP_POST`) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/event/EventDestinationType.java`
- [X] T006 [P] Create `EventType` enum (the 13 fixed entries from spec.md FR-007:
      `PARTICIPANT_REGISTERED`, `PARTICIPANT_REVOKED`, `PARTICIPANT_NOT_PARTICIPATED`, `USER_CREATED`,
      `TOPIC_PROPOSED`, `TOPIC_APPROVED`, `PARTICIPANT_JOINED_TOPIC`, `PARTICIPANT_LEFT_TOPIC`,
      `ORGANISER_ROLE_ADDED`, `ORGANISER_ROLE_REMOVED`, `GROUP_FORMED`, `GROUP_DISBANDED`,
      `GROUP_COMPLIANCE_CHANGED`) in `src/main/java/net/fabcelhaft/hackathonorganiser/event/EventType.java`
- [X] T007 [P] Create `EventDestinationConflictException` (business-rule validation errors — missing
      type-required field, duplicate name — and the FR-018 stale-write conflict; see the Note above) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/event/EventDestinationConflictException.java`
- [X] T008 Create `DomainEvent` record (`EventType eventType, Map<String, Object> payload`) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/event/DomainEvent.java` (depends on: T006)
- [X] T009 Create the `EventDestination` R2DBC entity (`@Table("event_destinations")`, all columns from T004,
      `id`/`createdAt`/`updatedAt` defaulted like every other entity in this codebase, e.g. `Group.java`) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/event/EventDestination.java` (depends on: T005)
- [X] T010 Create `EventDestinationRepository extends ReactiveCrudRepository<EventDestination, UUID>` in
      `src/main/java/net/fabcelhaft/hackathonorganiser/event/EventDestinationRepository.java` (depends on: T009)

**Checkpoint**: Foundation ready — `EventDestination` persists and `EventType`/`DomainEvent` exist; user story
implementation can now begin.

---

## Phase 3: User Story 1 - Organiser Creates an Event Destination (Priority: P1) 🎯 MVP

**Goal**: An Organiser can create a Kafka or HTTP POST Event Destination with the connection details its type
requires, saved disabled by default, with duplicate names and missing required fields rejected.

**Independent Test**: Create one Kafka Destination and one HTTP POST Destination with valid connection details;
confirm both are saved, disabled, and appear in the Destination list with their configured type and connection
details (spec.md US1 Independent Test).

### Tests for User Story 1 ⚠️

> Write these tests FIRST; confirm they fail before starting the implementation tasks below.

- [X] T011 [P] [US1] Create `EventDestinationServiceTest` (unit, JUnit 5 + Mockito,
      `@ExtendWith(MockitoExtension.class)`, mocking `EventDestinationRepository`, matching
      `GroupServiceTest.java`'s style) in
      `src/test/java/net/fabcelhaft/hackathonorganiser/event/EventDestinationServiceTest.java`: `create(...)`
      persists a `KAFKA` Destination given `kafkaBootstrapServers`+`kafkaTopic`, and an `HTTP_POST` Destination
      given `httpUrl`; rejects `KAFKA` missing either Kafka field and `HTTP_POST` missing `httpUrl` with
      `EventDestinationConflictException` naming the missing field; rejects a duplicate `name` (case-sensitive,
      pre-check against `findAll()`/a dedicated existence query) with `EventDestinationConflictException`; every
      successful `create(...)` result has `enabled = false` regardless of input (FR-006)
- [X] T012 [US1] Create `EventDestinationManagementIT` (integration, `@SpringBootTest` + `@Testcontainers` +
      `WebTestClient` + `mockOidcLogin()`, matching `GroupManagementIT.java`'s existing setup — including its
      real-`User`-backed `organiser()`/`standardUser()` login helper pattern, not a bare `mockOidcLogin()`) in
      `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/eventdestination/EventDestinationManagementIT.java`:
      as an Organiser, `POST /organiser/event-destinations` with valid Kafka fields redirects and the row
      appears via `GET /organiser/event-destinations`; a missing required field re-renders the form with an
      error and no row created; a duplicate name re-renders the form with an error; as a standard user (no
      Organiser role), `GET /organiser/event-destinations` is denied (depends on: T004–T010 (Foundational
      complete))

### Implementation for User Story 1

- [X] T013 [US1] Implement `EventDestinationService` in
      `src/main/java/net/fabcelhaft/hackathonorganiser/event/EventDestinationService.java`: `create(String name,
      EventDestinationType type, String kafkaBootstrapServers, String kafkaTopic, String httpUrl, String
      credential, List<EventType> eventTypes)` (validates per-type required fields and name uniqueness before
      saving, forces `enabled = false`), `findAll()`, `findById(UUID)` — to make T011 pass (depends on: T011)
- [X] T014 [US1] Implement `EventDestinationController` (`@Controller`, `@RequestMapping("/organiser/event-destinations")`,
      matching `CustomFieldController.java`'s `Rendering` + `ServerWebExchange.getFormData()` pattern) in
      `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/eventdestination/EventDestinationController.java`:
      `GET` (list), `GET /new` (create form), `POST` (create, re-rendering the form with `error` on
      `EventDestinationConflictException`) — to make T012 pass (depends on: T012, T013)
- [X] T015 [P] [US1] Create `src/main/resources/templates/organiser/event-destinations/list.html`: a table of
      Destinations (name, type, enabled/disabled, connection summary) with a "New Destination" link, following
      this project's existing organiser list-page markup (e.g. `organiser/custom-fields/list.html`)
- [X] T016 [P] [US1] Create `src/main/resources/templates/organiser/event-destinations/form.html`: name, a
      type selector (`KAFKA`/`HTTP_POST`) that shows/hides the relevant connection fields (Kafka bootstrap
      servers + topic, or HTTP URL), a credential field, and an error message region; every field has a
      programmatically associated `<label>` (FR-023)
- [X] T017 [US1] Add an "Event Destinations" nav link to
      `src/main/resources/templates/organiser/fragments/layout.html`, pointing at
      `/organiser/event-destinations`, alongside the existing Custom Fields/Skills/Settings links

**Checkpoint**: At this point, User Story 1 is fully functional and testable independently — an Organiser can
create and list Destinations of either type.

---

## Phase 4: User Story 2 - Organiser Selects Which Events a Destination Receives (Priority: P1)

**Goal**: While creating or editing a Destination, an Organiser selects any combination of the 13 cataloged
Event Types; selections are persisted per Destination, independent of every other Destination's selections.

**Independent Test**: Configure two Destinations with different Event Type selections and confirm each
persists exactly its own selection, independent of the other (spec.md US2 Independent Test; full delivery
filtering is verified in US3).

### Tests for User Story 2 ⚠️

- [X] T018 [P] [US2] Extend `EventDestinationServiceTest`
      (`src/test/java/net/fabcelhaft/hackathonorganiser/event/EventDestinationServiceTest.java`): `create(...)`
      with a non-empty `eventTypes` list persists exactly those selections, retrievable via a new
      `findEventTypes(UUID destinationId)` method; `create(...)` with an empty list succeeds and persists no
      selections; two Destinations created with different `eventTypes` lists retain independent selections
- [X] T019 [US2] Extend `EventDestinationManagementIT`
      (`src/test/java/net/fabcelhaft/hackathonorganiser/organiser/eventdestination/EventDestinationManagementIT.java`):
      `POST /organiser/event-destinations` with two or three Event Type checkboxes selected persists exactly
      those, visible via the list page's "Subscribed Event Types" column and pre-checked on the edit form

### Implementation for User Story 2

- [X] T020 [US2] Implement `replaceEventTypeSelections(UUID destinationId, List<EventType> eventTypes)` in
      `EventDestinationService.java` via `DatabaseClient` against `event_destination_event_types`
      (delete-then-bulk-insert, mirroring `TopicService.replaceTopicSkills`/`loadSkills` exactly) and
      `findEventTypes(UUID destinationId)`; wire `create(...)` (T013) to call
      `replaceEventTypeSelections(...)` after the initial save — to make T018 pass (depends on: T018)
- [X] T021 [US2] Implement `findEnabledDestinationsFor(EventType eventType)` in `EventDestinationService.java`
      (a `DatabaseClient` join of `event_destinations`/`event_destination_event_types` filtered to
      `enabled = true`) — not yet called by anything until US3, but tested here as the query US2's own
      independent-selection guarantee depends on
- [X] T022 [P] [US2] Add a 13-checkbox Event Type selection group (one per `EventType` value, each with a
      human-readable label, e.g. "Participant registered" for `PARTICIPANT_REGISTERED`) to
      `src/main/resources/templates/organiser/event-destinations/form.html`, and a "Subscribed Event Types"
      column to `src/main/resources/templates/organiser/event-destinations/list.html` — to make T019 pass
      (depends on: T019, T020)

**Checkpoint**: At this point, User Stories 1 AND 2 both work independently — Destinations can be created with
independently configurable Event Type selections.

---

## Phase 5: User Story 3 - System Publishes Events to Subscribed, Enabled Destinations (Priority: P1)

**Goal**: A qualifying domain occurrence builds a JSON Event and delivers an independent copy to every
currently enabled, subscribed Destination, asynchronously (the triggering action never waits), with automatic
retry-with-backoff and no dedicated delivery history on exhaustion.

**Independent Test**: Enable a Destination subscribed to `PARTICIPANT_REGISTERED`, perform that registration,
and confirm the Destination receives one Event whose payload carries that event type and the expected JSON
object (spec.md US3 Independent Test).

### Tests for User Story 3 ⚠️

- [X] T023 [P] [US3] Create `EventPayloadFactoryTest` (unit) in
      `src/test/java/net/fabcelhaft/hackathonorganiser/event/EventPayloadFactoryTest.java`: for each of the 13
      Event Types, asserts the built payload's keys and nested field names exactly match data-model.md's "Event
      Type → payload composition" table and each entity's field-shape section (`topic`/`participant`/`user`/`group`,
      `group.complianceStatus` computed via a mocked `ComplianceService.evaluate(...)`)
- [X] T024 [P] [US3] Create `EventPublisherTest` (unit, `StepVerifier` + Mockito, mocking
      `EventDestinationService`, `HttpDestinationSender`, `KafkaDestinationSender`) in
      `src/test/java/net/fabcelhaft/hackathonorganiser/event/EventPublisherTest.java`: `publish(DomainEvent)`
      calls the correct sender once per enabled Destination subscribed to that Event Type and not at all for a
      disabled or unsubscribed Destination (FR-011); two subscribed Destinations each get their own independent
      send call, and a failure simulated on one sender's `Mono` does not prevent or delay the other's call
      (FR-012); `publish(...)` itself returns before either sender's `Mono` completes (a `TestPublisher` with a
      manually-completed `Mono` proves the caller is not blocked — FR-020a-1/SC-008)
- [X] T025 [P] [US3] Create `HttpDestinationSenderTest` in
      `src/test/java/net/fabcelhaft/hackathonorganiser/event/HttpDestinationSenderTest.java`, standing up a
      `com.sun.net.httpserver.HttpServer` per research.md §8: asserts a `POST` with `Content-Type:
      application/json` and the exact JSON body arrives at the stub server; a Destination with a non-blank
      credential adds `Authorization: Bearer <credential>`, one without omits it (contracts/delivery-transport.md);
      a stub server returning `500` (or refusing the connection) is retried up to the bound in research.md §7
      and the resulting `Mono` completes without error (failure is swallowed, not propagated — FR-020b)
- [X] T026 [P] [US3] Create `KafkaDestinationSenderIT` in
      `src/test/java/net/fabcelhaft/hackathonorganiser/event/KafkaDestinationSenderIT.java`, using
      `@Testcontainers` + the `org.testcontainers:kafka` module (research.md §8): asserts the JSON envelope is
      produced to the Destination's configured topic with a `null` key, readable back via a test consumer; an
      unreachable/misconfigured broker is retried up to the bound and the resulting `Mono` completes without
      error
- [X] T027 [US3] Create `EventDeliveryIT` (integration, `@SpringBootTest` + `@Testcontainers` + `WebTestClient` +
      `mockOidcLogin()`, reusing the real-principal login helper pattern) in
      `src/test/java/net/fabcelhaft/hackathonorganiser/event/EventDeliveryIT.java`: create and enable an
      `HTTP_POST` Destination pointed at a local `HttpServer` subscribed to `PARTICIPANT_JOINED_TOPIC` and
      `GROUP_FORMED`; as a Participant, join an open Topic with no existing Group via the existing join
      endpoint; assert the join's own HTTP response returns promptly (not blocked on delivery) and, polling with
      a short timeout, that the stub server received exactly two JSON messages — one per Event Type, per FR-010a
      (depends on: T013–T022 (US1/US2 complete))

### Implementation for User Story 3

- [X] T028 [US3] Implement `EventPayloadFactory` in
      `src/main/java/net/fabcelhaft/hackathonorganiser/event/EventPayloadFactory.java`: one method per entity
      (`topicPayload(Topic)`, `participantPayload(Participant)`, `userPayload(User)`,
      `groupPayload(Group, ComplianceStatus)`) plus one method per Event Type composing the right combination
      per data-model.md's table — to make T023 pass (depends on: T023)
- [X] T029 [US3] Implement `HttpDestinationSender` in
      `src/main/java/net/fabcelhaft/hackathonorganiser/event/HttpDestinationSender.java`: injects the existing
      `WebClient` bean, `send(EventDestination, String jsonBody)` returns
      `Mono<Void>` per contracts/delivery-transport.md, wrapped in `Retry.backoff(3, Duration.ofSeconds(2))`
      (research.md §7) with the terminal error mapped to a logged, swallowed completion — to make T025 pass
      (depends on: T025)
- [X] T030 [US3] Implement `KafkaDestinationSender` in
      `src/main/java/net/fabcelhaft/hackathonorganiser/event/KafkaDestinationSender.java`: a
      `Map<UUID, KafkaProducer<String, String>>` cache keyed by Destination id, built lazily from
      `kafkaBootstrapServers` on first use; `send(EventDestination, String jsonBody)` bridges
      `producer.send(record, callback)` into `Mono.create(...)`, wrapped in the same
      `Retry.backoff(3, Duration.ofSeconds(2))`; a `disposeCacheFor(UUID destinationId)` method closes and
      evicts a cached producer (used later by US4's edit/delete) — to make T026 pass (depends on: T026)
- [X] T031 [US3] Implement `EventPublisher` in
      `src/main/java/net/fabcelhaft/hackathonorganiser/event/EventPublisher.java`: `publish(DomainEvent event)`
      (returns `void`, never blocks) calls `eventDestinationService.findEnabledDestinationsFor(event.eventType())`,
      and for each Destination builds the JSON body via Jackson, dispatches to `HttpDestinationSender` or
      `KafkaDestinationSender` by type, and calls `.subscribe(v -> {}, ex -> log.warn(...))` on the resulting
      `Mono<Void>` (research.md §1) — to make T024 pass (depends on: T024, T028, T029, T030)
- [X] T032 [US3] Modify `src/main/java/net/fabcelhaft/hackathonorganiser/participant/ParticipantService.java`:
      inject `EventPublisher`; call `publish(new DomainEvent(PARTICIPANT_REGISTERED,
      eventPayloadFactory.participantPayload(...)))` at the end of `selfRegister`/`register` (after the save
      succeeds, not chained into the returned `Mono`); call `PARTICIPANT_REVOKED` from `selfRevoke` and from
      `changeStatus` when the new status is `REVOKED`; call `PARTICIPANT_NOT_PARTICIPATED` from `changeStatus`
      when the new status is `NOT_PARTICIPATED` (research.md §6)
- [X] T033 [US3] Modify `src/main/java/net/fabcelhaft/hackathonorganiser/topic/TopicService.java`: inject
      `EventPublisher`; call `publish(TOPIC_PROPOSED, ...)` from `propose` and `publish(TOPIC_APPROVED, ...)`
      from `approve`, after each save succeeds (research.md §6)
- [X] T034 [US3] Modify `src/main/java/net/fabcelhaft/hackathonorganiser/group/GroupService.java`: inject
      `EventPublisher`+`EventPayloadFactory`; `createGroupRow` (shared by `join`'s first-joiner path and the
      Organiser's direct `create`) publishes `GROUP_FORMED` (`group`+`topic`) once, after any initial members are
      added (FR-010a); the shared `addMemberChain` (`join`/`addMember`) publishes `PARTICIPANT_JOINED_TOPIC`
      always and additionally `GROUP_COMPLIANCE_CHANGED` whenever the join itself flips the Group's evaluated
      Compliance status (spec.md Clarifications — the Event Type fires on *any* compliance-status change, not
      only an explicit override, which the original task text under-scoped); the shared `removeMemberChain`
      (`leave`/`removeMember`) publishes `PARTICIPANT_LEFT_TOPIC` always and the same compliance-flip check,
      skipped when the Group has just dropped to zero active members (compliance no longer applies then); the
      shared `disbandGroupRow` (`disband`, and `leave`'s automatic last-member disbandment) publishes
      `GROUP_DISBANDED` with `complianceStatus: null`; `setComplianceOverride` publishes
      `GROUP_COMPLIANCE_CHANGED` with the Group's newly evaluated `ComplianceStatus` (research.md §6)
- [X] T035 [US3] Modify `src/main/java/net/fabcelhaft/hackathonorganiser/user/UserService.java`: inject
      `EventPublisher`; `setOrganiser(id, true)` publishes `ORGANISER_ROLE_ADDED`, `setOrganiser(id, false)`
      publishes `ORGANISER_ROLE_REMOVED`, both with the updated `user` payload (research.md §6)
- [X] T036 [US3] Modify `src/main/java/net/fabcelhaft/hackathonorganiser/security/HackathonOidcUserService.java`:
      inject `EventPublisher`; publish `USER_CREATED` with the new `user` payload exactly when a new `User` row
      is created on first login (not on a returning-user login) (research.md §6)
- [X] T037 [US3] Implement `ComplianceChangeEventHook` in
      `src/main/java/net/fabcelhaft/hackathonorganiser/event/ComplianceChangeEventHook.java`
      (`Mono<T> wrapRulesetChange(Mono<T> save)` — loads every active Group + evaluates
      `ComplianceStatus` before `save`, runs `save`, re-evaluates the same Groups after, and calls
      `EventPublisher.publish(GROUP_COMPLIANCE_CHANGED, ...)` for each Group whose status differs, per
      research.md §5/FR-010b). **Deviation from the original task text**: wired into
      `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/compliance/ComplianceController.java`'s three
      route handlers (`updateRuleset`, `addRequirement`, `removeRequirement` — the sole entry points for all
      ruleset changes; `OrganiserSettingsController` never touches Maximum/Minimum Group Members), not injected
      directly into `OrganiserSettingsService`/`ComplianceService` as first planned — that would have created a
      circular Spring bean dependency, since `ComplianceChangeEventHook` itself depends on `ComplianceService`.
      Unit-tested by `src/test/java/net/fabcelhaft/hackathonorganiser/event/ComplianceChangeEventHookTest.java`
      (added during implementation; not originally listed as its own task)

**Checkpoint**: All three P1 stories are now complete — the MVP is fully functional: Destinations can be
created, subscribed to Event Types, and receive live Events asynchronously and independently.

---

## Phase 6: User Story 4 - Organiser Manages Existing Destinations (Priority: P2)

**Goal**: An Organiser can edit a Destination's connection details or Event Type selections, enable/disable it,
or delete it — with concurrent edits detected and rejected, and a stored credential never redisplayed.

**Independent Test**: Edit an existing Destination's URL and confirm subsequent Events go to the new URL; then
disable it and confirm it stops receiving Events; then delete a different Destination and confirm it no longer
appears or receives Events (spec.md US4 Independent Test).

### Tests for User Story 4 ⚠️

- [X] T038 [P] [US4] Extend `EventDestinationServiceTest`
      (`src/test/java/net/fabcelhaft/hackathonorganiser/event/EventDestinationServiceTest.java`): `update(...)`
      with the correct current `updatedAt` applies the change and bumps `updatedAt`; `update(...)` with a stale
      `updatedAt` (simulating a concurrent edit) throws `EventDestinationConflictException` and applies no
      change (FR-018); `update(...)` changing `type` from `KAFKA` to `HTTP_POST` clears
      `kafkaBootstrapServers`/`kafkaTopic` and requires `httpUrl` (FR-020); `update(...)` with a blank
      `credential` leaves the previously stored value untouched, a non-blank one overwrites it (FR-019);
      `enable(id)`/`disable(id)` change only `enabled`, leaving every other field untouched; `delete(id)`
      removes the row and calls `KafkaDestinationSender.disposeCacheFor(id)` for a `KAFKA` Destination
- [X] T039 [US4] Extend `EventDestinationManagementIT`
      (`src/test/java/net/fabcelhaft/hackathonorganiser/organiser/eventdestination/EventDestinationManagementIT.java`):
      `POST /organiser/event-destinations/{id}/edit` updates the URL, confirmed via the list page; a second edit
      submitted with a stale hidden `updatedAt` value is rejected with an error and does not overwrite the first
      edit; `POST /organiser/event-destinations/{id}/enable` and `/disable` toggle `enabled` without touching
      other fields; `POST /organiser/event-destinations/{id}/delete` removes it from the list; `GET
      /organiser/event-destinations/{id}/edit` never renders the stored credential's value in the form's HTML

### Implementation for User Story 4

- [X] T040 [US4] Implement `update(UUID id, Instant expectedUpdatedAt, String name, EventDestinationType type,
      String kafkaBootstrapServers, String kafkaTopic, String httpUrl, String credential, List<EventType>
      eventTypes)` in `EventDestinationService.java`: loads the current row, throws
      `EventDestinationConflictException` if its `updatedAt` does not equal `expectedUpdatedAt` (FR-018),
      re-validates per-type required fields (discarding the other type's fields on a type change, FR-020),
      applies `credential` only when non-blank (FR-019), calls `replaceEventTypeSelections(...)` (T020), and
      bumps `updatedAt`; implement `enable(UUID id)`/`disable(UUID id)` (FR-013) and `delete(UUID id)` (calling
      `kafkaDestinationSender.disposeCacheFor(id)` first when the Destination's type is `KAFKA`) — to make T038
      pass (depends on: T038)
- [X] T041 [US4] Implement `editForm(@PathVariable UUID id)`, `update(@PathVariable UUID id, ServerWebExchange)`,
      `enable(@PathVariable UUID id)`, `disable(@PathVariable UUID id)`, and `delete(@PathVariable UUID id)` in
      `EventDestinationController.java`, matching `CustomFieldController.java`'s `editForm`/error-rendering
      pattern — to make T039 pass (depends on: T039, T040)
- [X] T042 [P] [US4] Update `src/main/resources/templates/organiser/event-destinations/form.html` for edit mode:
      a hidden `updatedAt` field, the credential field always rendered blank with placeholder text "Leave blank
      to keep the current value" (FR-019); add enable/disable/delete controls to
      `src/main/resources/templates/organiser/event-destinations/list.html`

**Checkpoint**: All four user stories are independently functional — full CRUD lifecycle plus live,
asynchronous, filtered delivery.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Documentation deliverable (FR-020c), accessibility validation (FR-021–FR-025/SC-007), and final
regression check.

- [X] T043 [P] Create `docs/events.md` — one consolidated Markdown document covering all 13
      `EventType` entries, each with its JSON shape and worked example copied from
      `specs/007-event-system-events/contracts/event-payloads.md` (FR-020c)
- [X] T044 [P] Create `EventDestinationAccessibilityIT` (Playwright + axe-core, matching
      `src/test/java/net/fabcelhaft/hackathonorganiser/a11y/ComplianceSettingsAccessibilityIT.java`'s existing
      structure) in `src/test/java/net/fabcelhaft/hackathonorganiser/a11y/EventDestinationAccessibilityIT.java`:
      scans the Destination list and the create/edit form, asserting zero critical/serious WCAG 2.1 AA
      violations (SC-007)
- [ ] T045 Run `specs/007-event-system-events/quickstart.md` end-to-end manually (a local HTTP capture endpoint
      and a local single-broker Kafka), confirming every numbered step's expected outcome — not yet done as a
      literal manual click-through; its scenarios are covered by automated tests instead (steps 1-2/5/7 by
      `EventDestinationManagementIT`, steps 3-4 by `EventDeliveryIT`, step 6 by `ComplianceChangeEventHookTest`)
- [X] T046 Run `mvn test` from the repository root and confirm the full 001–007 suite passes with zero
      regressions — `mvn -q clean test` passed with exit code 0 (all unit tests, features 001-007)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately.
- **Foundational (Phase 2)**: Depends on Setup completion — BLOCKS all user stories.
- **User Story 1 (Phase 3)**: Depends on Foundational only.
- **User Story 2 (Phase 4)**: Depends on Foundational; its implementation tasks modify files US1 created
  (`EventDestinationService`, `form.html`, `list.html`), so in practice follows US1, though its own tests
  (T018) only require Foundational.
- **User Story 3 (Phase 5)**: Depends on Foundational; T027 (`EventDeliveryIT`) additionally depends on US1+US2
  being complete (needs a real, subscribable Destination to enable). The unit-level tests (T023–T026) and most
  implementation (T028–T031) have no US1/US2 dependency and could proceed in parallel with them; the seven
  service-wiring tasks (T032–T037) depend on T031 (`EventPublisher`) existing.
- **User Story 4 (Phase 6)**: Depends on US1 (`EventDestinationService.create`/entity) and US3's
  `KafkaDestinationSender.disposeCacheFor` (T030).
- **Polish (Phase 7)**: Depends on all four user stories being complete.

### Parallel Opportunities

- T002/T003 (Setup) in parallel.
- T004–T007 (Foundational: schema, both enums, exception) in parallel; T008–T010 depend on T005/T006/T009
  respectively and follow.
- T011 (US1 unit test) can start as soon as Foundational is done; T015/T016 (templates) can proceed in parallel
  with T013/T014 (service/controller) once T012 is written, since they touch different files.
- T023–T026 (US3's four independent unit/component tests) can all run in parallel — different files, no shared
  dependency beyond Foundational.
- T038 (US4 unit test) and T044 (accessibility) can proceed in parallel with each other once their prerequisite
  phases are done.

---

## Parallel Example: User Story 3 (test tasks)

```bash
# Launch all four independent US3 test-authoring tasks together:
Task: "Create EventPayloadFactoryTest in src/test/java/net/fabcelhaft/hackathonorganiser/event/EventPayloadFactoryTest.java"
Task: "Create EventPublisherTest in src/test/java/net/fabcelhaft/hackathonorganiser/event/EventPublisherTest.java"
Task: "Create HttpDestinationSenderTest in src/test/java/net/fabcelhaft/hackathonorganiser/event/HttpDestinationSenderTest.java"
Task: "Create KafkaDestinationSenderIT in src/test/java/net/fabcelhaft/hackathonorganiser/event/KafkaDestinationSenderIT.java"
```

---

## Implementation Strategy

### MVP First (User Stories 1–3)

1. Complete Phase 1: Setup.
2. Complete Phase 2: Foundational (CRITICAL — blocks all stories).
3. Complete Phase 3: User Story 1 (create Destinations).
4. Complete Phase 4: User Story 2 (select Event Types).
5. Complete Phase 5: User Story 3 (live, asynchronous delivery).
6. **STOP and VALIDATE**: run `quickstart.md` steps 1–4 — the MVP already delivers the feature's core value
   (spec.md's own three P1 stories).
7. Deploy/demo if ready.

### Incremental Delivery

1. Setup + Foundational → foundation ready.
2. US1 → Destinations can be created (not yet useful alone, but independently testable and reviewable).
3. US2 → Destinations can be scoped to specific Event Types.
4. US3 → Events actually flow — MVP complete, demoable end-to-end (quickstart.md steps 1–4).
5. US4 → ongoing maintenance (edit/enable/disable/delete) — quickstart.md step 5.
6. Polish → docs/events.md, accessibility scan, full regression run.

### Parallel Team Strategy

With multiple developers: complete Setup + Foundational together; then Developer A can take US1+US2 (they
share `EventDestinationService`/`EventDestinationController`/templates) while Developer B starts US3's
transport-and-publisher layer (T023–T031, which has no US1/US2 dependency) in parallel, converging only at
T027 (`EventDeliveryIT`) and the seven service-wiring tasks (T032–T037).

---

## Phase 8: Convergence

**Purpose**: Close the gap between spec.md's FR-010c/FR-010d (Participant-related Events must also
carry the associated User and the Participant's Custom Field definitions/answers) and plan.md's
research.md §10 design, and the current code — none of which exists yet.

### Tests for Phase 8 ⚠️

> Write these tests FIRST; confirm they fail before starting the implementation tasks below.

- [X] T047 [P] Extend `CustomFieldServiceTest`
      (`src/test/java/net/fabcelhaft/hackathonorganiser/customfield/CustomFieldServiceTest.java`): a new
      `currentAnswers(UUID participantId)` returns one `CustomFieldAnswer` per currently-`enabled` Custom Field
      definition (matching `registrationFields()`'s existing enabled-only filtering); a field the Participant has
      not answered comes back with a blank/absent `freeTextValue`/`selectedOptionIds` rather than being omitted; a
      definition with `enabled = false` is excluded even if the Participant has a previously recorded answer for
      it (spec.md Edge Cases) per FR-010d. **Deviation from the original task text**: the enabled/disabled-field and
      blank-answer behavior is asserted via zero-field/disabled-only cases requiring no `DatabaseClient` mocking
      (`currentAnswersIsEmptyWhenNoFieldsAreDefined`, `currentAnswersExcludesADisabledCountryDefinition`,
      `blankAnswersReturnsAnEmptyAnswerPerDefinitionWithNoDatabaseLookup`) rather than mocking per-field
      `custom_field_values`/`custom_field_value_options` row-mapping — matching this codebase's own established
      convention of deferring that kind of assembly correctness to a real-Postgres IT (see
      `ParticipantServiceTest`'s `findDirectoryListing` tests and their comment); the real per-answer value is
      instead proven end-to-end by T050's `EventDeliveryIT` extension.
- [X] T048 [P] Extend `EventPayloadFactoryTest`
      (`src/test/java/net/fabcelhaft/hackathonorganiser/event/EventPayloadFactoryTest.java`, mocking
      `UserRepository` + `CustomFieldService`): the 5 Participant builders (`participantRegistered`,
      `participantRevoked`, `participantNotParticipated`, `participantJoinedTopic`, `participantLeftTopic`) now
      return `Mono<DomainEvent>` whose resolved payload includes a `user` key matching data-model.md's `user`
      shape for the Participant's `userId`, and a `customFields` key matching data-model.md's `customFields`
      shape (sourced from `CustomFieldService.currentAnswers`) per FR-010c, FR-010d — including a blank-answer
      case (`participantRegisteredReturnsABlankAnswerForAnUnansweredCustomField`).
- [X] T049 [P] Extend `EventPublisherTest`
      (`src/test/java/net/fabcelhaft/hackathonorganiser/event/EventPublisherTest.java`): a new
      `publish(Mono<DomainEvent>)` overload subscribes to the supplied `Mono` and, once it resolves, dispatches to
      the same per-Destination delivery pipeline as `publish(DomainEvent)` (FR-011, FR-012); the overload itself
      returns before the enrichment `Mono` completes, proving a slow enrichment lookup cannot delay the caller
      (FR-020a-1/SC-008) per plan: research.md §10.
- [X] T050 [US3] Extend `EventDeliveryIT`
      (`src/test/java/net/fabcelhaft/hackathonorganiser/event/EventDeliveryIT.java`): the `PARTICIPANT_JOINED_TOPIC`
      message the stub HTTP server receives includes a `user` object matching the joining Participant's account
      and a `customFields` array, end-to-end through the real `EventPublisher`/`EventPayloadFactory` wiring per
      FR-010c, FR-010d, US3/AC6. Extended beyond the original task text to also create a real
      `CustomFieldDefinition` and set the joining Participant's actual answer via
      `ParticipantService.setCustomFieldValue(...)` before joining, then assert the delivered JSON contains that
      field's label and stored value — the authoritative real-database proof for the per-answer assembly logic
      T047 deliberately left to this test (see T047's deviation note).

### Implementation for Phase 8

- [X] T051 Create `customfield.CustomFieldAnswer` record
      (`src/main/java/net/fabcelhaft/hackathonorganiser/customfield/CustomFieldAnswer.java`):
      `record CustomFieldAnswer(CustomFieldDefinition definition, List<CustomFieldOption> options, String
      freeTextValue, List<UUID> selectedOptionIds)` — same fields as
      `participant.ParticipantService.CustomFieldValueView`, relocated to `customfield` so both `ParticipantService`
      and `EventPayloadFactory` can depend on it without either depending on the other's package (plan: research.md
      §10).
- [X] T052 Add `currentAnswers(UUID participantId)` to `CustomFieldService`
      (`src/main/java/net/fabcelhaft/hackathonorganiser/customfield/CustomFieldService.java`), moving the
      assembly logic previously in `ParticipantService.loadFieldViews`/`loadFreeTextValue`/`loadSelectedOptionIds`
      down into `CustomFieldService` as `currentAnswers`/`answersFor`/`blankAnswers`, returning
      `Mono<List<CustomFieldAnswer>>`; updated `ParticipantService` (removed the local `CustomFieldValueView`
      record and the three private query methods; `registrationFieldViewsForParticipant`, `loadCustomFieldValueViews`,
      and `findDirectoryListing` now delegate to the new `CustomFieldService` methods) and its 3 other referencing
      files (`participants/ProfileController.java`, `participants/RegistrationController.java`,
      `templates/fragments/profile-fields-form.html`) to use `customfield.CustomFieldAnswer` instead. Also updated
      `ParticipantServiceTest`'s `findDirectoryListing`/`findDetailForViewer` tests, which previously stubbed
      `DatabaseClient` directly for this logic, to instead stub the new `customFieldService.answersFor(...)`
      (`stubCustomFieldAnswers` helper, replacing the removed `stubNoStoredFieldValues`).
- [X] T053 Modify `EventPayloadFactory`
      (`src/main/java/net/fabcelhaft/hackathonorganiser/event/EventPayloadFactory.java`): inject `UserRepository`
      and `CustomFieldService`; add `customFieldAnswerPayload(CustomFieldAnswer)` per data-model.md's
      `customFields` shape; change `participantRegistered`, `participantRevoked`, `participantNotParticipated`,
      `participantJoinedTopic`, `participantLeftTopic` to return `Mono<DomainEvent>` via a shared
      `enrichedParticipantPayload(Participant)` helper that `Mono.zip`s `userRepository.findById(...)` and
      `customFieldService.currentAnswers(...)` into the `participant`(+`topic`) map.
- [X] T054 Modify `EventPublisher`
      (`src/main/java/net/fabcelhaft/hackathonorganiser/event/EventPublisher.java`): added `publish(Mono<DomainEvent>
      eventMono)`, which subscribes to the given `Mono` and, on resolution, calls the existing `publish(DomainEvent)`
      — reusing its `serialize`/`findEnabledDestinationsFor`/`dispatch` pipeline verbatim, still a detached
      `.subscribe(...)`.
- [X] T055 [US3] Modify `ParticipantService`
      (`src/main/java/net/fabcelhaft/hackathonorganiser/participant/ParticipantService.java`): no source change
      needed at the `PARTICIPANT_REGISTERED`/`PARTICIPANT_REVOKED`/`PARTICIPANT_NOT_PARTICIPATED` call sites —
      `eventPublisher.publish(eventPayloadFactory.participantRegistered(...))` etc. already resolves to the new
      `publish(Mono<DomainEvent>)` overload (T054) via normal Java overload resolution once
      `EventPayloadFactory`'s return type changed (T053), confirmed by `mvn compile` succeeding unchanged.
- [X] T056 [US3] Modify `GroupService`
      (`src/main/java/net/fabcelhaft/hackathonorganiser/group/GroupService.java`): same result as T055 — the
      `participantJoinedTopic`/`participantLeftTopic` call sites needed no source change, per the same overload
      resolution.
- [X] T057 [P] Updated `docs/events.md` entries #1 `PARTICIPANT_REGISTERED` (with #2/#3 now pointing at it as "same
      shape"), and #7 `PARTICIPANT_JOINED_TOPIC` (with #8 pointing at it), to include `user` and `customFields`,
      copied from `contracts/event-payloads.md`'s updated worked examples (FR-020c).

**Checkpoint**: Every Participant-carrying Event Type's payload matches spec.md FR-010c/FR-010d and
data-model.md exactly; a follow-up `/speckit-converge` run should find no remaining items here.

---

## Notes

- [P] tasks = different files, no dependencies.
- [Story] label maps task to specific user story for traceability.
- Verify tests fail before implementing.
- Commit after each task or logical group.
- Stop at any checkpoint to validate a story independently.
- Avoid: vague tasks, same-file conflicts, cross-story dependencies that break independence beyond what is
  explicitly noted above (US2 building on US1's files; US3's `EventDeliveryIT` needing US1+US2; US4 needing
  US1+US3).
