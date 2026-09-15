# Phase 1 Data Model: Event Notification System

## Entities introduced by this feature

### Event Destination

An Organiser-configured target for outbound Events (spec.md "Event Destination"; FR-001–FR-020c).

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | PK, `DEFAULT uuidv7()`, per this project's ID convention |
| `name` | text | Unique (FR-005); unique index `event_destinations_name_key` |
| `type` | text (`EventDestinationType` enum: `KAFKA`, `HTTP_POST`) | FR-001 |
| `enabled` | boolean | `DEFAULT false` (FR-006) |
| `kafkaBootstrapServers` | text, nullable | Required when `type = KAFKA` (FR-002) |
| `kafkaTopic` | text, nullable | Required when `type = KAFKA` (FR-002) |
| `httpUrl` | text, nullable | Required when `type = HTTP_POST` (FR-003) |
| `credential` | text, nullable | Optional bearer token/API key/broker password (FR-019); never re-read into an edit form's value (research.md §4) |
| `createdAt` | timestamptz | `DEFAULT now()` |
| `updatedAt` | timestamptz | `DEFAULT now()`; read back into the edit form as a hidden field and compared on save for optimistic concurrency (FR-018), exactly like `CustomFieldDefinition`/`OrganiserSettings`/`Group` |

**Validation rules**:
- `type = KAFKA` ⇒ `kafkaBootstrapServers` and `kafkaTopic` both non-null (FR-002, FR-004).
- `type = HTTP_POST` ⇒ `httpUrl` non-null (FR-003, FR-004).
- Enforced both in `EventDestinationService` (friendly error, FR-004) and as a database `CHECK`
  constraint (structural guarantee, matching this project's existing double-enforcement pattern —
  e.g. `organiser_settings_max_group_members_check`).
- `name` uniqueness enforced by both a pre-check (friendly error, FR-005) and a unique index.
- Changing `type` (edit) discards the fields that belonged to the old type (FR-020) — enforced in
  `EventDestinationService.update(...)`, not the database (a `CHECK` constraint cannot express
  "clear the other type's columns").

**Concurrency**: `EventDestinationConflictException` is thrown both for business-rule violations
(missing required field for the chosen type, duplicate name — same *shape* as
`CustomFieldConflictException`/`GroupConflictException`/`OrganiserSettingsConflictException`) and,
distinctly, for a genuine stale-write conflict (FR-018): the edit form carries the row's
`updatedAt` as a hidden field, and `EventDestinationService.update(...)` rejects the save if it no
longer matches the current row. **Note**: unlike what an earlier draft of this plan assumed, no
existing service in this codebase actually performs an `updatedAt`-comparison stale-write check —
the existing `*ConflictException` classes are business-rule validators only. This feature
introduces the project's first explicit optimistic-concurrency check; it reuses the existing
*exception-naming and friendly-message* convention, not an existing concurrency *mechanism*.

### Event Destination × Event Type (association)

Pure association table — no payload beyond the pairing itself (spec.md "Event Destination" carries
"the set of Event Types it is subscribed to"; FR-008, FR-009).

| Column | Type | Notes |
|---|---|---|
| `event_destination_id` | uuid | FK → `event_destinations(id)`, part of composite PK |
| `event_type` | text (`EventType` enum name) | Part of composite PK; no FK — `EventType` is a fixed Java enum, not a table (matches `AuditEventType`'s existing precedent, not `topic_skills`' FK-to-a-real-table shape) |

Manipulated directly via `DatabaseClient` (delete-then-bulk-insert on every save) exactly like
`topic_skills` in `TopicService` — a composite-key table with no independent UUID is excluded from
this project's `ReactiveCrudRepository` convention (research.md of feature 002, §4).

### Event Type (fixed catalog — Java enum, not a table)

```java
public enum EventType {
    PARTICIPANT_REGISTERED,
    PARTICIPANT_REVOKED,
    PARTICIPANT_NOT_PARTICIPATED,
    USER_CREATED,
    TOPIC_PROPOSED,
    TOPIC_APPROVED,
    PARTICIPANT_JOINED_TOPIC,
    PARTICIPANT_LEFT_TOPIC,
    ORGANISER_ROLE_ADDED,
    ORGANISER_ROLE_REMOVED,
    GROUP_FORMED,
    GROUP_DISBANDED,
    GROUP_COMPLIANCE_CHANGED
}
```

Exactly the 13 entries fixed in spec.md FR-007 (Clarifications session). Persisted as `Enum#name()`
text, matching `AuditEventType`'s existing convention — no custom R2DBC converter needed.

### Domain Event (in-memory only — never persisted, per FR-020b/research.md §1)

| Field | Type | Notes |
|---|---|---|
| `eventType` | `EventType` | |
| `payload` | `Map<String, Object>` | One entry per referenced entity, see "Event payload shapes" below; serialized to the wire JSON body's non-`eventType` keys |

Wire JSON envelope sent to every subscribed, enabled Destination:

```json
{
  "eventType": "PARTICIPANT_JOINED_TOPIC",
  "topic": { "...": "..." },
  "participant": { "...": "..." },
  "user": { "...": "..." },
  "customFields": [ { "...": "..." } ]
}
```

`eventType` is always the enum name as a string; every other top-level key is the lowercase entity
name(s) involved in that occurrence (see the per-Event-Type table in `contracts/event-payloads.md`).

## Event payload shapes (reused entity JSON, per spec.md Assumptions)

Field names below are copied 1:1 from the existing `@Table` entity classes — no new "public API"
shape is invented.

**`topic`** (from `Topic.java`):

```json
{ "id": "uuid", "name": "string", "description": "string", "createdByUserId": "uuid",
  "approvalStatus": "PENDING|APPROVED", "createdAt": "instant", "updatedAt": "instant" }
```

**`participant`** (from `Participant.java`):

```json
{ "id": "uuid", "userId": "uuid", "status": "ACTIVE|NOT_PARTICIPATED|REVOKED",
  "createdAt": "instant", "updatedAt": "instant" }
```

**`user`** (from `User.java`):

```json
{ "id": "uuid", "displayName": "string", "email": "string", "organiser": "boolean",
  "createdAt": "instant", "updatedAt": "instant" }
```

`oidcSubject` is excluded — it is an internal IdP correlation key with no meaning to an external
consumer and is never displayed elsewhere in the product either.

**`customFields`** (array; one entry per currently-enabled `CustomFieldDefinition`, from
`CustomFieldDefinition.java`/`CustomFieldOption.java`/`CustomFieldValue.java` — FR-010d; only
present on Participant-carrying Event Types, see composition table below):

```json
[
  {
    "definition": { "id": "uuid", "label": "string", "fieldType": "FREE_TEXT|MULTI_SELECT",
      "required": "boolean", "public": "boolean", "overview": "boolean" },
    "options": [ { "id": "uuid", "label": "string" } ],
    "freeTextValue": "string|null",
    "selectedOptionIds": ["uuid"]
  }
]
```

`options` is only ever non-empty for a `MULTI_SELECT` definition (every option currently defined
for it, so a consumer can resolve `selectedOptionIds` to labels without a separate lookup);
`freeTextValue`/`selectedOptionIds` are `null`/empty when the Participant has not answered that
field. A definition with `enabled = false` at the moment the Event is built is excluded entirely
(spec.md Edge Cases), even if the Participant has a previously recorded answer for it. Reuses the
exact fields already produced by `ParticipantService`'s existing `CustomFieldValueView` read model
(relocated to `customfield.CustomFieldAnswer` — research.md §10) — no new "public API" shape is
invented, consistent with spec.md's Assumptions.

**`group`** (from `Group.java` + its Topic's compliance status):

```json
{ "id": "uuid", "topicId": "uuid", "status": "ACTIVE|DISBANDED",
  "complianceOverride": "boolean", "complianceStatus": "COMPLIANT|NOT_COMPLIANT|COMPLIANT_OVERRIDE",
  "createdAt": "instant", "updatedAt": "instant" }
```

`complianceStatus` is computed via the existing `ComplianceService.evaluate(...)` at the moment the
Event is built (it is not a persisted `Group` column — research.md §5), so a "Group compliance
changed" Event always carries the Group's *new* status, not a stale one.

## Event Type → payload composition

| Event Type | Payload keys |
|---|---|
| `PARTICIPANT_REGISTERED` | `participant`, `user`, `customFields` |
| `PARTICIPANT_REVOKED` | `participant`, `user`, `customFields` |
| `PARTICIPANT_NOT_PARTICIPATED` | `participant`, `user`, `customFields` |
| `USER_CREATED` | `user` |
| `TOPIC_PROPOSED` | `topic` |
| `TOPIC_APPROVED` | `topic` |
| `PARTICIPANT_JOINED_TOPIC` | `topic`, `participant`, `user`, `customFields` |
| `PARTICIPANT_LEFT_TOPIC` | `topic`, `participant`, `user`, `customFields` |
| `ORGANISER_ROLE_ADDED` | `user` |
| `ORGANISER_ROLE_REMOVED` | `user` |
| `GROUP_FORMED` | `group`, `topic` |
| `GROUP_DISBANDED` | `group`, `topic` |
| `GROUP_COMPLIANCE_CHANGED` | `group`, `topic` |

Every Participant-carrying Event Type carries `user` and `customFields` alongside `participant`
(FR-010c, FR-010d) — `user` is the same User `participant.userId` already references, and
`customFields` is that Participant's currently-enabled Custom Field definitions and answers (see
"Event payload shapes" above). `USER_CREATED`/`ORGANISER_ROLE_ADDED`/`ORGANISER_ROLE_REMOVED` are
User-related but not Participant-related, so they are unaffected — they carry only `user`, as
before.

## State transitions

- **Event Destination**: `disabled → enabled → disabled` (FR-013, freely reversible, any number of
  times) and `(any) → deleted` (FR-015, terminal). `type` may change on edit (FR-020); every other
  field may change freely on edit (FR-014) subject to optimistic-concurrency (FR-018).
- **Event Type catalog**: fixed at 13 entries for this feature (spec.md Assumptions) — no
  create/edit/delete lifecycle.
- **Domain Event**: built → dispatched to N independent per-Destination delivery attempts (each
  with its own retry-then-log-and-drop lifecycle, research.md §1/§7) → discarded; never persisted,
  never re-queryable (FR-020b).
