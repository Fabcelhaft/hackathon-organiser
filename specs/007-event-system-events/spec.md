# Feature Specification: Event Notification System

**Feature Branch**: `007-event-system-events`

**Created**: 2026-09-03

**Status**: Draft

**Input**: User description: "Event system. Events can be configured in the oraniser view. Multiple event destionations can be defined. Event destinations can be from type Kafka or from type HTTP POST. For each the destination and which Events are produced can be configured. Event is a json with an event type, and then the relevant json objects. For example when a participant registers for a topic, the topic json representationa and the \"

## Clarifications

### Session 2026-09-03

- Q: Which occurrences must be in the Event Type catalog at launch? → A: A fixed, thirteen-entry catalog covering the app's key lifecycle actions: Participant registered, Participant revoked, Participant not participated, User created, Topic proposed, Topic approved, Participant joined Topic, Participant left Topic, Organiser role added, Organiser role removed, Group formed, Group disbanded, and Group compliance changed.
- Q: Should a failed delivery to a Destination be retried, and does an Organiser get any visibility into failures? → A: Automatic retry with backoff on failure; no dedicated Organiser-facing delivery log or history — an Organiser is not expected to review individual delivery outcomes through this feature's UI.
- Q: Should each Event Type's JSON structure be documented, and where? → A: Yes — every Event Type's JSON payload structure, with a worked example, is documented as Markdown under the project's `docs/` folder, kept in step with the catalog.
- Q: Does the action that triggers an occurrence wait for Event delivery to complete? → A: No — delivery is asynchronous; the triggering action completes immediately regardless of Destination availability, and Event delivery (including retries) happens in the background.
- Q: When an Organiser changes the instance-wide Compliance Ruleset, does every existing Group whose evaluated status flips as a result each produce its own "Group compliance changed" Event? → A: Yes — a ruleset change re-evaluates every existing Group immediately, and each Group whose status actually flips produces its own Event, in addition to the Event Type already firing from a per-Group action (join, leave, or override).
- Q: Should each Event's JSON payload carry an explicit schema-version marker? → A: No — versioning is out of scope for this feature; the payload shape is whatever the product currently produces for each entity, kept in step by the `docs/` documentation (FR-020c).

### Session 2026-09-03 (addendum)

- Q: When a Participant appears in an Event's payload, should the associated User also be included? → A: Yes — every Event Type whose payload includes a Participant MUST also include the full User associated with that Participant, not merely its `userId` reference (FR-010c).
- Q: Should a Participant's Custom Field answers appear in Participant-related Events? → A: Yes — every Event Type whose payload includes a Participant MUST also include the current, Organiser-configured Custom Field definitions together with that Participant's answers to them, as of the moment the Event is built (FR-010d).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Organiser Creates an Event Destination (Priority: P1)

An Organiser, from the Organiser area, creates a new Event Destination by giving it a name, choosing its type — Kafka or HTTP POST — and providing the connection details that type requires (a Kafka topic name and broker location for Kafka; a target URL for HTTP POST). The new Destination is saved and can be enabled or disabled.

**Why this priority**: Without a way to define where events go, nothing else in this feature has anywhere to send data. This is the foundation every other story depends on.

**Independent Test**: Can be fully tested by an Organiser creating one Kafka Destination and one HTTP POST Destination, each with valid connection details, and confirming both are saved and appear in the Destination list with their configured type and connection details.

**Acceptance Scenarios**:

1. **Given** an Organiser is creating a new Event Destination, **When** they choose type "Kafka" and provide a broker location and topic name, **Then** the Destination is saved with those Kafka-specific details.
2. **Given** an Organiser is creating a new Event Destination, **When** they choose type "HTTP POST" and provide a target URL, **Then** the Destination is saved with that URL.
3. **Given** an Organiser is creating a Destination, **When** they omit a field required for the chosen type (e.g., no topic name for Kafka, no URL for HTTP POST), **Then** the system rejects the save and identifies the missing field.
4. **Given** an Organiser saves a new Destination, **When** the save completes, **Then** the Destination is created in a disabled state by default, requiring an explicit action to start receiving events.
5. **Given** an Organiser attempts to create a Destination whose name matches an existing Destination's name, **When** they save, **Then** the system rejects the save and explains that the name is already in use.

---

### User Story 2 - Organiser Selects Which Events a Destination Receives (Priority: P1)

While creating or editing a Destination, an Organiser selects one or more Event Types from a fixed catalog of the system's significant domain occurrences (e.g., "Participant registered for a Topic"). Only the Event Types selected for a given Destination are ever sent to it; different Destinations may have different, overlapping, or non-overlapping selections.

**Why this priority**: Selecting which events go where is the core configuration act the request describes; without it, a Destination cannot usefully be enabled. It depends only on Story 1's Destination existing.

**Independent Test**: Can be fully tested by configuring two Destinations with different Event Type selections, triggering an occurrence matching only one of them, and confirming only the matching Destination receives it.

**Acceptance Scenarios**:

1. **Given** an Organiser is creating or editing a Destination, **When** they view the Event Type selection control, **Then** they see every Event Type currently in the catalog and can select any combination of them.
2. **Given** a Destination has one or more Event Types selected, **When** the Organiser saves, **Then** exactly those selections are persisted for that Destination.
3. **Given** an Organiser saves a Destination with no Event Types selected, **When** the save completes, **Then** the Destination is saved successfully but produces no Events until at least one Event Type is selected.
4. **Given** two Destinations are each configured with a different subset of Event Types, **When** an occurrence matching only one Destination's subset happens, **Then** only that Destination receives an Event for it.

---

### User Story 3 - System Publishes Events to Subscribed, Enabled Destinations (Priority: P1)

When a qualifying domain occurrence happens — for example, a Participant registering for a Topic — the system automatically builds a structured Event containing an event type identifier and the JSON representation of the object(s) involved, and sends it to every currently enabled Destination that is configured to receive that Event Type.

**Why this priority**: This is the mechanism that makes Stories 1 and 2's configuration actually do something; without it, Destinations and Event Type selections have no observable effect.

**Independent Test**: Can be fully tested by enabling a Destination subscribed to "Participant registered for a Topic," performing that registration, and confirming the Destination receives one Event whose payload carries that event type and the expected JSON object(s).

**Acceptance Scenarios**:

1. **Given** an enabled Destination is subscribed to a given Event Type, **When** a matching occurrence happens, **Then** the Destination receives an Event whose JSON payload includes an event type identifier and the JSON representation(s) of the object(s) involved in that occurrence.
2. **Given** a Destination is disabled, **When** a matching occurrence happens, **Then** that Destination receives nothing, even though it is configured for that Event Type.
3. **Given** a Destination is enabled but not subscribed to the Event Type of an occurrence, **When** that occurrence happens, **Then** that Destination receives nothing for it.
4. **Given** two or more enabled Destinations are each subscribed to the same Event Type, **When** a matching occurrence happens, **Then** every one of them receives its own copy of the Event.
5. **Given** a Participant registers for a Topic, **When** the corresponding Event is built, **Then** its payload includes the JSON representation of both the Topic and the Participant involved.
6. **Given** any occurrence that produces a Participant-related Event (Participant registered, revoked, or not participated; Participant joined or left a Topic), **When** the Event is built, **Then** its payload also includes the JSON representation of the User associated with that Participant, and the current Custom Field definitions together with that Participant's answers to them.

---

### User Story 4 - Organiser Manages Existing Destinations (Priority: P2)

An Organiser views a list of all configured Event Destinations, opens one to edit its connection details or Event Type selections, enables or disables it, or deletes it entirely when it is no longer needed.

**Why this priority**: Destinations are long-lived configuration that inevitably need adjustment (a changed URL, a broker migration, a subscription change); this depends on Story 1 existing but is independently valuable as ongoing maintenance capability.

**Independent Test**: Can be fully tested by editing an existing Destination's URL and confirming subsequent Events go to the new URL, then disabling it and confirming it stops receiving Events, then deleting a different Destination and confirming it no longer appears in the list or receives Events.

**Acceptance Scenarios**:

1. **Given** an Organiser opens the Destination list, **When** the page loads, **Then** every configured Destination is shown with its name, type, enabled/disabled state, and subscribed Event Types.
2. **Given** an Organiser edits a Destination's connection details or Event Type selections, **When** they save, **Then** subsequent Events reflect the updated configuration immediately, with no deployment required.
3. **Given** an Organiser disables an enabled Destination, **When** the change is saved, **Then** that Destination stops receiving Events immediately, while its configuration (connection details, Event Type selections) is retained unchanged for future re-enabling.
4. **Given** an Organiser deletes a Destination, **When** the deletion completes, **Then** it no longer appears in the Destination list and no further Events are sent to it.
5. **Given** a non-Organiser user attempts to view, create, edit, enable/disable, or delete an Event Destination, **When** the request is processed, **Then** the system denies it regardless of how it was reached.

---

### Edge Cases

- What happens when an Organiser deletes a Destination while an Event to it is still being sent? The in-flight send MUST be allowed to complete or fail on its own; no further Events are queued for that Destination once deletion is saved.
- What happens when two Organisers edit the same Destination at the same time? The system MUST detect the conflict and reject the second save rather than silently overwriting the first, consistent with how other Organiser configuration screens in the product already protect against concurrent edits.
- What happens when an Organiser edits a Destination's type after it was created (e.g., from Kafka to HTTP POST)? The system MUST require and validate the full set of fields the new type needs, and MUST discard connection details that only applied to the old type.
- What happens when a single occurrence would qualify for more than one Event Type at once (e.g., an edit that is also a status change)? Each qualifying Event Type MUST be published as its own separate Event, so a Destination subscribed to only one of them still receives exactly that one.
- What happens when a Destination's connection details are invalid in a way only discoverable at send time (e.g., an unreachable broker or URL)? The system MUST retry the delivery automatically with backoff (FR-020a); if it still fails after retries are exhausted, the outcome is recorded only in a system-level log — this feature provides no dedicated Organiser-facing delivery history (per Clarifications).
- What happens when an Organiser changes the instance-wide Compliance Ruleset and hundreds of Groups exist? Every Group MUST be re-evaluated, and one "Group compliance changed" Event MUST be built and queued for asynchronous delivery per Group whose status actually flips; the Organiser's save action itself MUST NOT wait for any of that delivery to complete (per FR-020a-1).
- What happens when an Organiser revisits a previously saved Kafka or HTTP POST credential field? The system MUST NOT redisplay a previously entered secret (e.g., an API key or broker password) in plaintext; the field MUST show only that a value is set, requiring re-entry to change it.
- What happens when a Participant-related occurrence fires for a Participant who has not answered one or more currently-enabled Custom Fields? The Event MUST still list that Custom Field's definition, with a blank/absent answer for it, rather than omitting the definition entirely.
- What happens when a Custom Field is disabled at the moment a Participant-related occurrence fires? A disabled Custom Field's definition and any previously recorded answer for it MUST NOT appear in the Event's payload, consistent with disabled fields being excluded from the Participant-facing registration form elsewhere in the product.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST allow an Organiser to create an Event Destination with a unique name, a type (Kafka or HTTP POST), and the connection details required by that type.
- **FR-002**: For a Kafka-type Destination, System MUST require a broker location and a topic name.
- **FR-003**: For an HTTP-POST-type Destination, System MUST require a target URL.
- **FR-004**: System MUST reject the creation or editing of a Destination when a field required by its chosen type is missing, identifying which field is missing.
- **FR-005**: System MUST reject creating a Destination whose name duplicates an existing Destination's name.
- **FR-006**: System MUST create every new Destination in a disabled state by default; it MUST NOT receive Events until an Organiser explicitly enables it.
- **FR-007**: System MUST maintain a fixed catalog of exactly the following Event Types, each representing one significant domain occurrence the system can publish:
  1. Participant registered
  2. Participant revoked
  3. Participant not participated
  4. User created (fires after a user's first login)
  5. Topic proposed
  6. Topic approved
  7. Participant joined Topic
  8. Participant left Topic
  9. Organiser role added
  10. Organiser role removed
  11. Group formed (fires once, when a Topic's Group is created by its first joining Participant)
  12. Group disbanded
  13. Group compliance changed (fires whenever a Group's evaluated compliance status changes — whether caused by a join, a leave, an Organiser setting or clearing a compliance override on that Group, or an Organiser changing the instance-wide Compliance Ruleset)
- **FR-008**: System MUST allow an Organiser to select any combination of Event Types (including none) for a Destination, independently of any other Destination's selections.
- **FR-009**: System MUST persist a Destination's Event Type selections exactly as the Organiser last saved them.
- **FR-010**: When a domain occurrence matching a cataloged Event Type happens, System MUST build an Event consisting of that Event Type's identifier and the JSON representation of the object(s) involved in the occurrence.
- **FR-010a**: When a Participant's join creates a Topic's Group for the first time, System MUST build both a "Participant joined Topic" Event and a separate "Group formed" Event for that single occurrence, so a Destination subscribed to only one of them still receives exactly that one (consistent with the multi-qualifying-Event-Type edge case).
- **FR-010b**: When an Organiser saves a change to the instance-wide Compliance Ruleset, System MUST re-evaluate every existing Group's compliance status immediately and build one "Group compliance changed" Event for each Group whose evaluated status actually changes as a result; a Group whose status is unaffected by the ruleset change MUST NOT produce an Event.
- **FR-010c**: For every Event Type whose payload includes a Participant (Participant registered, Participant revoked, Participant not participated, Participant joined Topic, Participant left Topic), System MUST also include the full JSON representation of the User associated with that Participant, in addition to the Participant object itself.
- **FR-010d**: For every Event Type listed in FR-010c, System MUST also include the currently-enabled Custom Field definitions together with that Participant's current answer to each (blank/absent where unanswered), as of the moment the Event is built; a disabled Custom Field's definition and any recorded answer for it MUST NOT be included.
- **FR-011**: System MUST send each built Event to every Destination that is both currently enabled and currently subscribed to that Event's Event Type, and to no other Destination.
- **FR-012**: System MUST send an independent copy of a given Event to each subscribed, enabled Destination, so that one Destination's outcome (success or failure) does not affect delivery to any other.
- **FR-013**: System MUST allow an Organiser to enable or disable an existing Destination without discarding its connection details or Event Type selections.
- **FR-014**: System MUST allow an Organiser to edit an existing Destination's name, connection details, and Event Type selections, applying the updated configuration to all subsequent Events with no deployment required.
- **FR-015**: System MUST allow an Organiser to delete a Destination, after which it no longer receives Events and no longer appears in the Destination list.
- **FR-016**: System MUST present a list of all configured Destinations to an Organiser, showing each one's name, type, enabled/disabled state, and subscribed Event Types.
- **FR-017**: System MUST restrict creating, viewing, editing, enabling/disabling, and deleting Event Destinations to users holding the Organiser privilege, denying these actions to all other users.
- **FR-018**: System MUST detect a save based on stale Destination data (a concurrent edit by another Organiser) and reject it rather than silently overwriting the intervening change.
- **FR-019**: System MUST accept and store an optional authentication credential per Destination appropriate to its type (e.g., an API key or bearer token for HTTP POST, broker credentials for Kafka), and MUST NOT redisplay a previously stored credential value in plaintext after it has been saved.
- **FR-020**: System MUST discard connection-detail fields that do not apply to a Destination's currently selected type when that type is changed, and MUST require the fields the new type needs before the change can be saved.
- **FR-020a**: When sending an Event to a Destination fails (e.g., the broker or URL is unreachable), System MUST automatically retry that delivery with backoff a bounded number of times before giving up on that Event for that Destination.
- **FR-020a-1**: Event delivery to Destinations MUST be asynchronous with respect to the domain occurrence that triggered it — the triggering action (e.g., a Participant joining a Topic) MUST complete for its user immediately and independently of whether any subscribed Destination is reachable or how long delivery (including retries) takes.
- **FR-020b**: When a delivery to a Destination still fails after its retries are exhausted, System MUST record the outcome in a system-level log; this feature MUST NOT provide an Organiser-facing delivery history or per-Event delivery status.
- **FR-020c**: System MUST maintain, under the project's `docs/` folder, a Markdown document describing every cataloged Event Type's JSON payload structure and a worked example, kept up to date with the catalog in FR-007.

### Accessibility Requirements (WCAG 2.1 AA)

- **FR-021**: All UI introduced by this feature (the Destination list, the Destination create/edit form including the type selector and Event Type selection control, and the enable/disable and delete controls) MUST conform to WCAG 2.1 Level AA.
- **FR-022**: Every interactive element introduced by this feature MUST be operable using the keyboard alone, in a logical tab order, and MUST display a visible focus indicator when focused via keyboard.
- **FR-023**: Every form control introduced by this feature (name, type selector, connection-detail fields, credential fields, Event Type checkboxes, enable/disable control) MUST have a programmatically associated label that assistive technology can read.
- **FR-024**: A status change that occurs without a full page reload (a successful or rejected save, an enable/disable toggle, a deletion) MUST be announced to assistive technology via an appropriately scoped live region, not communicated by visual change alone.
- **FR-025**: A validation error introduced by this feature (a missing required field, a duplicate name, a stale-data conflict) MUST be presented as text associated with the relevant field or action, not indicated by color or icon alone.

### Key Entities *(include if feature involves data)*

- **Event Destination**: An Organiser-configured target for outbound Events. Carries a unique name, a type (Kafka or HTTP POST), the type-specific connection details that type requires, an optional authentication credential, an enabled/disabled state (disabled by default), and the set of Event Types it is subscribed to.
- **Event Type**: One entry in the fixed catalog of significant domain occurrences the system can publish (e.g., "Participant registered for a Topic"). Any number of Destinations may subscribe to the same Event Type.
- **Event**: A single published occurrence's payload — an Event Type identifier plus the JSON representation(s) of the domain object(s) involved — sent as its own independent copy to every enabled Destination subscribed to that Event Type. For a Participant-related Event, the domain object(s) involved always include that Participant's associated User plus the currently-enabled Custom Field definitions and the Participant's answers to them (FR-010c, FR-010d), alongside the Participant itself.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An Organiser can define a fully configured, working Event Destination of either type in a single session, with no assistance beyond the on-screen form.
- **SC-002**: 100% of domain occurrences matching a cataloged Event Type result in an Event sent to every enabled, subscribed Destination at the time of the occurrence.
- **SC-003**: A Destination that is disabled or not subscribed to a given Event Type never receives an Event for it.
- **SC-004**: Changes to a Destination's configuration (connection details, subscriptions, enabled state) take effect for all subsequent Events without requiring a deployment or restart.
- **SC-005**: 100% of attempts by non-Organiser users to view or manage Event Destinations are denied.
- **SC-006**: 0 previously stored Destination credentials are ever displayed in plaintext after initial entry.
- **SC-007**: An automated accessibility scan of the Destination list and the Destination create/edit form reports zero critical or serious WCAG 2.1 AA violations.
- **SC-008**: 100% of domain occurrences complete for their triggering user with no added delay attributable to Event delivery, even when every subscribed Destination is unreachable.

## Assumptions

- "Organiser view" refers to the existing Organiser-only area of the product (already used for settings, Topics, Participants, Skills, and Custom Fields), consistent with how this feature's screens are restricted (FR-017).
- The Event Type catalog (FR-007) is a fixed, thirteen-entry list maintained by the system, not something an Organiser can define or extend themselves; growing it further is a future enhancement, out of scope here.
- Each JSON object embedded in an Event's payload reuses the same representation the product already exposes for that entity elsewhere, so no separate schema needs to be designed per Event Type; the `docs/` documentation (FR-020c) records that shape per Event Type for integrators.
- A Destination's connection details are validated for completeness (required fields present) at save time; deeper validation such as confirming a broker or URL is actually reachable happens only when an Event is sent, with automatic retry-with-backoff on failure (FR-020a) and no dedicated Organiser-facing delivery history (FR-020b).
- Multiple Destinations of the same type (e.g., two separate HTTP POST endpoints) can coexist, each with independent Event Type selections.
- Deleting a Destination is immediate and does not require first disabling it.
- "User created" fires the first time a person authenticates, independent of whether they ever become a Participant or Organiser afterward.
- "Organiser role added"/"Organiser role removed" fire on a change to a user's Organiser privilege itself, not on any other profile change for that user.
- No explicit schema-version marker is included in an Event's JSON payload; consumers rely on the `docs/` documentation (FR-020c) reflecting the shape currently produced. Introducing versioning is a possible future enhancement, out of scope here.
- "The User associated with that Participant" (FR-010c) is the same User the Participant's `userId` already references; embedding it in full spares an external consumer a separate lookup and reuses the same `user` shape already produced for `USER_CREATED`/`ORGANISER_ROLE_ADDED`/`ORGANISER_ROLE_REMOVED`.
- "Currently-enabled Custom Field definitions" (FR-010d) means the same set an Organiser has configured and enabled elsewhere in the product for Participant registration; a field disabled at the moment of the occurrence is excluded from that occurrence's Event even if the Participant answered it while it was still enabled.
