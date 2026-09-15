# Phase 0 Research: Event Notification System

All items below were open technical decisions after `spec.md`'s clarifications; none remain as
`NEEDS CLARIFICATION`. Each follows the project's existing conventions (verified against features
002–006) rather than introducing new architectural patterns.

## 1. Internal fan-out: how does a triggering action stay decoupled from delivery?

**Decision**: Each triggering service method builds the `DomainEvent` (event type + payload) at
the point of the occurrence, calls `EventPublisher.publish(event)`, and — critically — does
**not** chain that call into its own `Mono`/`Flux` return value. `EventPublisher.publish(...)`
looks up subscribed, enabled Destinations, builds one delivery `Mono<Void>` per Destination
(`.retryWhen(Retry.backoff(...))`, terminal error swallowed into a log line), and calls
`.subscribe(v -> {}, this::logDeliveryFailure)` on each — a detached subscription that starts the
non-blocking I/O immediately but is never awaited by the caller. `publish(...)` itself returns
`void` and never blocks, satisfying FR-020a-1 (SC-008) by construction: the triggering method's
own reactive chain is unaffected by whether a Destination is reachable or how long delivery takes.

**Rationale**: This mirrors the "fire an independent Reactor pipeline and don't wait for it"
pattern already implicit in the Reactive-First constitution — `WebClient` and the Kafka sender
(§2) are both natively non-blocking, so a detached `.subscribe()` never occupies a Reactor thread
waiting on network I/O. It needs zero new shared infrastructure (no queue, no scheduler bean, no
lifecycle to start/stop).

**Alternatives considered**:
- A `Sinks.Many<DomainEvent>` in-memory multicast sink with one dedicated subscriber pipeline —
  rejected as unnecessary indirection at hackathon scale: it adds a bean with startup/shutdown
  lifecycle and a single global backpressure point for no behavioral benefit over per-event
  detached subscriptions, since FR-012 already requires independent per-Destination delivery.
- A durable outbox table polled by a scheduled job — rejected: no scheduler infrastructure exists
  in this codebase today, and the resolved clarification explicitly accepts best-effort,
  non-durable delivery (no Organiser-facing delivery history, FR-020b) — a durable outbox would
  solve a durability problem this feature does not have.

**Tradeoff accepted**: an in-flight or queued Event is lost if the process restarts before
delivery succeeds. This is consistent with FR-020b/the Assumptions in spec.md and the project's
hackathon-scale, single-instance deployment (research.md of feature 006 makes the same "not a
high-volume production" scoping call).

## 2. Kafka producer: which client, and how does each Destination use its own broker?

**Decision**: Depend directly on `org.apache.kafka:kafka-clients` (the lean official Java
client) rather than the `spring-kafka` starter. A `KafkaDestinationSender` component maintains a
small in-memory `Map<UUID, KafkaProducer<String, String>>` keyed by Destination id, built lazily
from that Destination's `kafkaBootstrapServers` on first send and disposed (`producer.close()`)
whenever the Destination is edited or deleted. `producer.send(record, callback)` is bridged into
`Mono<Void>` via `Mono.create(sink -> producer.send(record, (metadata, exception) -> {...}))` —
the client's own I/O thread invokes the callback, so no Reactor thread blocks.

**Rationale**:
- `spring-kafka` auto-configures a single, application-wide `bootstrap-servers` and pulls in
  consumer-side machinery (`@EnableKafka`, listener containers) this feature never needs, since
  every Destination can point at a *different* Kafka cluster (FR-002) — a single Spring-managed
  `KafkaTemplate` bean cannot represent that per-row configuration.
- `kafka-clients` has no transitive dependency on `spring-webmvc` or any other framework
  (Constitution Principle II's explicit check) and is the minimal-surface choice consistent with
  Constitution Principle I ("minimises dependency surface").
- Bridging its callback-based async `send` into `Mono.create` keeps every Reactor-thread-visible
  operation non-blocking, satisfying Principle II without needing a reactive-native Kafka client.

**Alternatives considered**:
- `reactor-kafka` (`io.projectreactor.kafka:reactor-kafka`) — a genuinely reactive `KafkaSender`
  API, considered as the most idiomatic fit for a WebFlux app. Rejected only because it is a
  smaller, less mainstream dependency than the official client for what is a producer-only,
  low-volume use case; `kafka-clients` + `Mono.create` gets the same non-blocking guarantee with
  a dependency already implied by any Kafka integration.
- `spring-kafka` `KafkaTemplate` — rejected per-Destination-broker reasoning above.

## 3. HTTP destination delivery

**Decision**: Use `WebClient` — the class is already on the classpath via
`spring-boot-starter-webflux` (`reactor-netty-http` included), so no new dependency is needed.
**Correction during implementation**: unlike this section originally assumed, this project has no
`WebClient.Builder` bean auto-configured anywhere in its Spring context (no other component in this
codebase ever previously injected one), so `HttpDestinationSender` builds its own instance directly
(`WebClient.builder().build()`) in its no-arg constructor rather than accepting an injected
`WebClient.Builder` — depending on an absent bean would fail application startup. `HttpDestinationSender`
issues `webClient.post().uri(destination.httpUrl()).header(...).bodyValue(eventJson).retrieve()
.toBodilessEntity()`, treating any non-2xx response or transport error as a failure eligible for
the same `Retry.backoff(...)` used for Kafka (§1).

**Rationale**: Zero new dependency; `WebClient` is already the constitution-mandated HTTP client
for this project (Principle II), and building it directly avoids a fragile dependency on
Spring Boot autoconfiguration this project's context does not actually provide.

## 4. Credential storage on an Event Destination

**Decision**: Store the optional credential (HTTP bearer token / API key, or Kafka SASL password)
as a plain `text` column on `event_destinations`, sent as-is to the destination at delivery time.
FR-019's "MUST NOT redisplay a previously stored credential value in plaintext" is a
presentation-layer rule, not an at-rest-encryption rule: the edit form's credential field always
renders empty with placeholder text ("Leave blank to keep the current value"), and the service
only overwrites the stored value when the submitted field is non-blank.

**Rationale**: No encryption-at-rest pattern exists anywhere else in this codebase (OAuth2 client
secrets live in `application.yml`/environment config, not the database), and introducing one
(e.g., Jasypt, a KMS integration) for a single new column would be a disproportionate new
dependency for this feature. This matches the reasonable-default framing already recorded in
spec.md's Assumptions.

**Alternatives considered**: Application-level column encryption (e.g., a custom R2DBC converter
using `javax.crypto`) — rejected as out of proportion to this feature; noted as a possible future
hardening step, not a blocker.

## 5. Detecting a Group's compliance-status flip when the Compliance Ruleset changes (FR-010b)

**Decision**: `ComplianceStatus` is computed on demand by `ComplianceService.evaluate(...)` and is
**not** persisted on `Group` (confirmed: `Group` has no status column beyond its own lifecycle
`status`/`disbandedAt`/`complianceOverride`). To detect "a Group whose evaluated status actually
changed" when an Organiser saves a Ruleset change, the save-time hook:
1. Loads every currently active Group and evaluates each one's `ComplianceStatus` under the
   **old** ruleset (i.e., before the incoming settings/requirement change is persisted).
2. Persists the ruleset change (`OrganiserSettingsService.update(...)` for min/max, or
   `ComplianceService.addRequirement`/`removeRequirement` for diversity rules).
3. Re-evaluates every one of those same Groups under the **new** ruleset and diffs the two
   in-memory results; for each Group whose status differs, calls `EventPublisher.publish(...)`
   with a "Group compliance changed" `DomainEvent`.

**Rationale**: This requires no new persisted state and reuses `ComplianceService.evaluate(...)`
exactly as the Topic Overview / Group detail views already call it (feature 005). Both existing
save paths (`OrganiserSettingsService.update`, `ComplianceService.addRequirement`/
`removeRequirement`) get the same three-step wrapper via a small shared
`ComplianceChangeEventHook` component injected into both services, avoiding duplicated
before/after-diff logic.

**Accepted risk**: a Group whose own membership changes concurrently with the ruleset save could
theoretically produce a stale before/after comparison. Acceptable at hackathon scale and
consistent with this feature's already-accepted best-effort delivery semantics (FR-020b) — this
is a notification system, not an audit trail (feature 006 already owns strict correctness there).

## 6. Where each cataloged Event Type is raised

| # | Event Type | Raised from |
|---|---|---|
| 1 | Participant registered | `ParticipantService.selfRegister` / `.register` |
| 2 | Participant revoked | `ParticipantService.selfRevoke` / `.changeStatus(REVOKED)` |
| 3 | Participant not participated | `ParticipantService.changeStatus(NOT_PARTICIPATED)` |
| 4 | User created | `HackathonOidcUserService` (first-login user creation) |
| 5 | Topic proposed | `TopicService.propose` |
| 6 | Topic approved | `TopicService.approve` |
| 7 | Participant joined Topic | `GroupService.join` / `.addMember` |
| 8 | Participant left Topic | `GroupService.leave` / `.removeMember` |
| 9 | Organiser role added | `UserService.setOrganiser(id, true)` |
| 10 | Organiser role removed | `UserService.setOrganiser(id, false)` |
| 11 | Group formed | `GroupService.join` / `.create`, only on first-member creation (FR-010a) |
| 12 | Group disbanded | `GroupService.disband`, and the last-member-leaves path inside `.leave` |
| 13 | Group compliance changed | `GroupService.join`/`.leave`/`.setComplianceOverride`, and the ruleset-change hook (§5) |

**Rationale**: Every hook point is an existing, already-tested service method (features 002–006);
this feature only adds an `EventPublisher.publish(...)` call at each site plus (for #7/#11 and
#13's ruleset path) the small amount of "did this also cross a first-join / status-flip boundary"
logic already native to those methods (`GroupService.join` already knows whether it just created
the Group; `evaluate(...)` already computes the status being compared).

## 7. Retry policy parameters (FR-020a)

**Decision**: `Retry.backoff(3, Duration.ofSeconds(2))` (3 retries after the initial attempt, ~2s
initial backoff, Reactor's default jitter and exponential growth) applied uniformly to both
`HttpDestinationSender` and `KafkaDestinationSender`. On final exhaustion, log at `WARN` with the
Destination name, Event Type, and root cause — no further action (FR-020b).

**Rationale**: A small, fixed bound keeps a persistently-broken Destination from accumulating
unbounded in-flight retries under load; the specific numbers are a reasonable, adjustable default
(not exposed as a per-Destination setting — spec.md does not request one) and are called out here
precisely so `/speckit-tasks` does not have to invent them mid-implementation.

## 8. Test tooling for the two Destination types

**Decision**:
- **Kafka**: add `org.testcontainers:kafka` as a test-scope dependency, matching this project's
  existing Testcontainers-for-Postgres pattern (`testcontainers-postgresql`, already present).
- **HTTP**: no new dependency — stand up a throwaway endpoint per test using the JDK's built-in
  `com.sun.net.httpserver.HttpServer`, capturing the received request body/headers for assertions.

**Rationale**: Both choices add either zero or a same-family dependency, keeping with this
project's existing Testcontainers-first integration-test convention (feature 001's infrastructure
setup) rather than introducing a new mocking library (e.g., WireMock, MockWebServer) purely for
one feature.

## 9. Event payload JSON shape

**Decision**: Each `DomainEvent`'s payload is a plain `Map<String, Object>` keyed by a fixed set
of fields per referenced entity (Topic, Participant, Group, User), reusing exactly the field names
already defined on `Topic`, `Participant`, `Group`, and `User` (see `data-model.md`), serialized
via a Jackson `ObjectMapper`. **Correction during implementation, twice over**: (1) unlike this
section originally assumed, `jackson-databind` was *not* already a compile-scope dependency of
this Thymeleaf-only application (it only reached the local Maven cache as an incidental transitive
of a test dependency) — it is added explicitly in `pom.xml` for this feature; (2) this
application's minimal `@SpringBootApplication` context does not auto-configure an `ObjectMapper`
bean either (confirmed by a failed context load during implementation — no bean of that type was
available to inject), so `EventPublisher` builds its own instance directly in its constructor,
with `jackson-datatype-jsr310`'s `JavaTimeModule` registered and `WRITE_DATES_AS_TIMESTAMPS`
disabled so an `Instant` field serializes as an ISO-8601 string, matching
`contracts/event-payloads.md`'s worked examples — `jackson-datatype-jsr310` is therefore also a new
explicit dependency, not only `jackson-databind`.

**Rationale**: Reuses existing field names 1:1 (per spec.md's Assumptions), avoiding a second,
divergent "public API" shape for these entities; adding `jackson-databind` +
`jackson-datatype-jsr310` (not a full JSON framework/starter) is the minimal-surface way to get a
correctly-configured JSON serializer, consistent with Principle
I.

## 10. Enriching Participant-related Events with the User and Custom Field data (FR-010c, FR-010d)

**Constraint**: `participant` already depends on `event` (`ParticipantService` calls
`EventPublisher`/`EventPayloadFactory`), so `EventPayloadFactory` (package `event`) MUST NOT take a
constructor dependency on `ParticipantService` (package `participant`) — that would be a
bean-construction cycle (`ParticipantService` → `EventPayloadFactory` → `ParticipantService`),
exactly the kind of cycle this class's existing Javadoc already calls out avoiding for `compliance`
(`group`'s `complianceStatus` is passed in, not computed here, for the same reason).

**Decision**:
1. `EventPayloadFactory`'s five Participant-event builder methods (`participantRegistered`,
   `participantRevoked`, `participantNotParticipated`, `participantJoinedTopic`,
   `participantLeftTopic`) change return type from `DomainEvent` to `Mono<DomainEvent>`, and gain a
   `UserRepository` dependency: `EventPayloadFactory` already depends only on entity classes
   (`Topic`, `Participant`, `User`, `Group`), never their services, and `UserRepository` (a plain
   `ReactiveCrudRepository`, package `user`) has no dependency back on `event` or `participant`, so
   `event → user.UserRepository` introduces no cycle.
2. The Custom Field definition + answer assembly (currently `ParticipantService.loadFieldViews`,
   backed by a `DatabaseClient` query against `custom_field_values`) moves down into
   `CustomFieldService` (package `customfield`, which already has zero dependency on `event` or
   `participant`) as a new method, `Mono<List<CustomFieldAnswer>> currentAnswers(UUID
   participantId)`, returning the same fields as today's `ParticipantService.CustomFieldValueView`
   record (`definition`, `options`, `freeTextValue`, `selectedOptionIds`) — relocated to
   `customfield.CustomFieldAnswer` so both `EventPayloadFactory` and `ParticipantService` (which
   keeps its own read-model methods working, now delegating to `CustomFieldService.currentAnswers`
   instead of its private `loadFieldViews`) depend on the same type without either depending on the
   other's package.

`EventPublisher` gains an overload, `publish(Mono<DomainEvent> eventMono)`, alongside the existing
`publish(DomainEvent event)`. It flat-maps the enrichment `Mono` straight into the same
per-Destination delivery pipeline (§1) and calls the same detached `.subscribe(...)` — the caller
(`ParticipantService`, `GroupService`) still never chains this into its own returned `Mono`/`Flux`,
so FR-020a-1/SC-008 hold exactly as before: a slow or failing User/Custom-Field lookup delays only
the background enrichment-then-delivery pipeline, never the triggering action's own response.

**Rationale**: Keeps every dependency edge pointing the same direction it already points today
(`participant → customfield`, `participant → event`, `event → user` entity/repository only) instead
of introducing a new edge back into `participant`; `event → customfield` is a new edge but not a
cycle, since `customfield` depends on nothing this feature touches. Moving the shared assembly
logic down to `CustomFieldService` also removes a small duplication risk (two independent "build a
Custom Field answer list for a Participant" implementations would otherwise be one refactor away
from silently diverging).

**Alternatives considered**:
- Loading `user`/`customFields` at each call site (`ParticipantService`, `GroupService`) and
  passing them into `EventPayloadFactory` as extra parameters, keeping the factory itself
  synchronous — rejected for `GroupService`'s two call sites specifically:
  `participantJoinedTopic`/`participantLeftTopic` fire from `GroupService`, which has no existing
  reason to depend on `CustomFieldService` or load Custom Field data for any other purpose, so this
  would leak a Participant/Custom-Field concern into `GroupService` purely for Event payload
  assembly.
- Calling `ParticipantService.registrationFieldViewsForParticipant(...)` directly from
  `EventPayloadFactory` and accepting the cycle via `@Lazy` constructor injection on one side —
  rejected: `@Lazy`-breaking-a-cycle has no precedent anywhere else in this codebase, and the
  clean, no-cycle alternative (moving the shared logic to `CustomFieldService`) is no more
  expensive to implement.
- A denormalized `user`/Custom-Field snapshot cached on `Participant` itself — rejected as
  unnecessary duplication of data that is one indexed lookup away and already reactively
  accessible; no precedent for entity-level caching exists elsewhere in this codebase.
