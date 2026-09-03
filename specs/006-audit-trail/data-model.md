# Phase 1 Data Model: Audit Trail for Topics and Participants

Continues 002–005's conventions: UUIDv7 PKs via Postgres's native `uuid PRIMARY KEY DEFAULT uuidv7()`;
`created_at`/`updated_at`-style timestamps as `timestamptz NOT NULL DEFAULT now()` (here, a single `occurred_at`
— an audit entry has no separate "last updated," since FR-010 makes it immutable); `schema.sql` stays the single
source of DDL, `CREATE ... IF NOT EXISTS`, rerun on every startup. One new table (`audit_entries`); every other
change is behavioral (new parameters on existing service methods), not a schema change to `topics`, `groups`,
or `participants`.

## New Entity

### Audit Entry (new `audit_entries` table; FR-001–FR-011a)

| Field | Type | Rules |
|---|---|---|
| `id` | uuid | PK, `DEFAULT uuidv7()` |
| `event_type` | text | `NOT NULL` — one of `CREATED`, `EDITED`, `STATUS_CHANGED`, `JOINED`, `LEFT`, `DISBANDED`, `DELETED` (`AuditEventType` enum, research.md discussion) |
| `actor_user_id` | uuid | `NOT NULL REFERENCES users (id)` — no `ON DELETE` behavior needed; `User` rows are never deleted anywhere in this codebase |
| `organiser` | boolean | `NOT NULL` — `true` when the action was taken in the Organiser capacity, `false` for standard-user/participant capacity (FR-002; research.md §2). Renamed from, and replaces, an earlier text `actor_capacity` enum column — the concept is binary, so a boolean is the whole story. |
| `occurred_at` | timestamptz | `NOT NULL DEFAULT now()` |
| `subject_type` | text | `NOT NULL` — `TOPIC` or `PARTICIPANT`, what kind of record `subject_id` identifies (research.md §3). There is **no `topic_id`/`group_id`/`participant_id` column** — every Group-affecting event is written with `subject_type = 'TOPIC'` and `subject_id` set to that Group's own Topic. |
| `subject_id` | uuid | `NOT NULL` — the id of the Topic or Participant this entry is about. **No foreign key** — deliberately generic, so it isn't typed to a single table (research.md §3); referential integrity for this column is not enforced by the database. |
| `subject_label` | text | `NOT NULL` — a denormalized snapshot (Topic name / Participant display name) captured at write time, so the entry stays legible even if the row `subject_id` points to is later deleted (e.g. a Participant) |
| `old_value` | text | nullable — populated only for the high-stakes changes named in FR-002a (Participant status, Topic/Group membership); `NULL` for every other `EDITED` entry |
| `new_value` | text | nullable — same rule as `old_value` |
| `action_id` | uuid | nullable — shared by the two `JOINED`/`LEFT` entries produced by one Topic-membership-change action (FR-004a, research.md §4); `NULL` for every other event |

`subject_type` + `subject_id` are both always populated — there is no "exactly one of N nullable columns" case
to guard, unlike the earlier FK-based draft of this table.

**Indexes**: one composite index on `(subject_type, subject_id, occurred_at DESC)`, matching every read query's
exact `WHERE`/`ORDER BY` shape (FR-011's "most-recent-first" rule) with no reliance on client-side sorting.

```sql
CREATE TABLE IF NOT EXISTS audit_entries (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    event_type text NOT NULL,
    actor_user_id uuid NOT NULL REFERENCES users (id),
    organiser boolean NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    subject_type text NOT NULL,
    subject_id uuid NOT NULL,
    subject_label text NOT NULL,
    old_value text,
    new_value text,
    action_id uuid
);

CREATE INDEX IF NOT EXISTS audit_entries_subject_idx
    ON audit_entries (subject_type, subject_id, occurred_at DESC);
```

## Supporting Types (new, in the `audit` package)

- **`AuditEventType`** (enum): `CREATED`, `EDITED`, `STATUS_CHANGED`, `JOINED`, `LEFT`, `DISBANDED`, `DELETED`.
- **`AuditSubjectType`** (enum): `TOPIC`, `PARTICIPANT` — mirrors the `subject_type` column exactly
  (research.md §3). There is no `GROUP` value; Group-affecting events use `TOPIC`.
- **`AuditActor`** (record): `(UUID userId, boolean organiser)` — resolved once per request by each controller
  (organiser controllers → `true`; self-service controllers → `false`) and passed as an explicit parameter into
  the relevant service method, the same way `TopicJoinController` already passes `requesterUserId` into
  `TopicJoinService.join(...)` today. No enum type backs this — the field is a plain boolean matching the
  column (research.md §2).
- **`AuditEntryView`** (record, read-model): the fields above resolved for display — `occurred_at`,
  `eventType`, `actorDisplayName` (joined from `users` the same way `GroupService.participantDisplayName`
  already resolves display names elsewhere), `organiser`, `subjectLabel`, `oldValue`, `newValue`, `actionId`.
  Returned by `AuditService.findForTopic/findForParticipant`, ordered `occurred_at DESC`, unbounded (per the
  resolved clarification — no page size or cursor parameter). There is no `findForGroup` — a Group's history
  *is* its Topic's history (research.md §9).

## `AuditService` (new)

| Method | Behavior |
|---|---|
| `record(AuditEventType type, AuditActor actor, AuditSubjectType subjectType, UUID subjectId, String subjectLabel, String oldValue, String newValue, UUID actionId)` | Inserts one `audit_entries` row — `subjectType`/`subjectId` are always both required (no "exactly one of N" validation needed, research.md §3). For any Group-affecting event, callers pass `AuditSubjectType.TOPIC` and that Group's own `topicId` — there is no separate group parameter or subject type. Returns `Mono<AuditEntry>` so callers can chain it into their existing pipeline. |
| `findForTopic(UUID topicId)` | `Flux<AuditEntryView>`, `WHERE subject_type = 'TOPIC' AND subject_id = :id ORDER BY occurred_at DESC` — also what a Group's detail page's "Audit" link resolves to, via that Group's `topicId` |
| `findForParticipant(UUID participantId)` | `Flux<AuditEntryView>`, `WHERE subject_type = 'PARTICIPANT' AND subject_id = :id ORDER BY occurred_at DESC` |

No update or delete method is exposed (FR-010; research.md §6).

## Modified Entities (behavioral only — no column changes to these tables)

### Topic (`TopicService`; FR-001, FR-002a)

| Method | Change |
|---|---|
| `propose(UUID authorUserId, ..., AuditActor actor)` | gains `actor`; on success, `auditService.record(CREATED, actor, AuditSubjectType.TOPIC, new.id, subjectLabel=name, old=null, new=null, actionId=null)` |
| `updateAsAuthor(UUID id, ..., AuditActor actor)` | gains `actor`; on success, records `EDITED` against `subjectType=TOPIC, subjectId=id` — no field-level values (name/description edits are not in FR-002a's high-stakes list) |
| `approve(UUID id, AuditActor actor)` | gains `actor`; records `STATUS_CHANGED` with `old="PENDING"`, `new="APPROVED"` (approval status is Topic-membership-adjacent state the spec's FR-002a hybrid rule treats as high-stakes) |

### Group (`GroupService`; FR-001, FR-004, FR-004a, FR-002a) — every entry below is filed as `subjectType=TOPIC`, never a Group reference (research.md §3, §9)

Every method below resolves the Group's `topicId` (already in hand — either passed in directly, as in
`join`/`leave`, or read off the loaded `Group` row) and passes `AuditSubjectType.TOPIC` + that `topicId` as
`AuditService.record(...)`'s subject. None of them ever produce a Group-typed entry, because there is no
`GROUP` value in `AuditSubjectType` at all.

The paired `JOINED`/`LEFT` audit write (FR-004, FR-004a) lives in `addMember`/`removeMember` themselves, **not**
in `join`/`leave` — `join`/`leave` call `addMember`/`removeMember` internally, so writing the pair there once
means every caller of `addMember`/`removeMember` (self-service join/leave *and* the organiser's direct
add/remove-member route) gets the identical two-entry shape for free, exactly as the resolved clarification
requires, with no duplicated audit-writing code and no risk of a caller accidentally producing four entries
instead of two.

| Method | Change |
|---|---|
| `create(UUID topicId, List<UUID> participantIds, AuditActor actor)` | gains `actor`; records one `CREATED` entry (`TOPIC`, `topicId`) for the organiser-initiated direct-create path only — not reached by the self-service join flow below |
| `join(UUID topicId, UUID participantId, AuditActor actor)` | gains `actor`; **now also acquires a `participant-join:<participantId>` advisory lock, after the existing `topic-join:<topicId>` one** (research.md §5 — closes a latent cross-topic race), then delegates to `addMember` (below) for both the membership write and its audit pair — no separate audit call of its own |
| `leave(UUID topicId, UUID participantId, AuditActor actor)` | gains `actor`; **acquires both locks in the same fixed order as `join`** (research.md §5), then delegates to `removeMember` (below) the same way |
| `addMember(UUID groupId, UUID participantId, AuditActor actor)` | gains `actor`; **acquires its own `participant-join:<participantId>` advisory lock** inside the shared `TransactionalOperator` before touching `group_members` (research.md §5) — participates in the caller's existing transaction (and re-acquires the same, already-held lock as a no-op) when called from `join`, and is the *only* guard when called directly from the organiser's manual add-member route, which previously took no lock at all; on success, loads the `Group` to get its `topicId`, generates a fresh `actionId`, and records **two** `JOINED` entries sharing it — one (`TOPIC`, `topicId`) with `new=<participant display name>`, one (`PARTICIPANT`, `participantId`) with `new=<topic name>` — identically whether reached via `join` or the organiser's direct form (FR-004, FR-004a; only `actor`/`organiser` differ) |
| `removeMember(UUID groupId, UUID participantId, AuditActor actor)` | gains `actor`; same lock treatment as `addMember`; on success, records the same shape as `addMember` with event type `LEFT`, identically whether reached via `leave` or the organiser's direct remove-member form |
| `disband(UUID groupId, AuditActor actor)` | gains `actor`; loads the `Group` to get its `topicId` and records `DISBANDED` (`TOPIC`, `topicId`) |
| `setComplianceOverride(UUID groupId, boolean override, AuditActor actor)` | gains `actor`; loads the `Group` to get its `topicId` and records `EDITED` (`TOPIC`, `topicId`), `old`/`new` = override flag before/after |

### Participant (`ParticipantService`; FR-001, FR-002a)

| Method | Change |
|---|---|
| `register(UUID userId, AuditActor actor)` | gains `actor`; records `CREATED` (`PARTICIPANT`, new `participantId`) |
| `setStatus(UUID id, ParticipantStatus newStatus, AuditActor actor)` | gains `actor`; records `STATUS_CHANGED` (`PARTICIPANT`, `id`), `old`/`new` = status values (FR-002a high-stakes field) |
| `updateSkills(UUID id, List<UUID> skillIds, AuditActor actor)` | gains `actor`; records `EDITED` (`PARTICIPANT`, `id`), no field-level values (Skills are not in FR-002a's high-stakes list) |
| `updateCustomFields(UUID id, ..., AuditActor actor)` | gains `actor`; records `EDITED` (`PARTICIPANT`, `id`), no field-level values |
| `selfRevoke(UUID id, AuditActor actor)` | gains `actor`; records `STATUS_CHANGED` (same shape as `setStatus`, since self-revocation is itself a status transition) |
| `delete(UUID id, AuditActor actor)` | gains `actor`; records `DELETED` (`PARTICIPANT`, `id`) **before** issuing the `DELETE FROM participants` statement, in the same transaction. Since `subject_id` carries no foreign key (research.md §3), nothing needs to react to the delete at all — every entry ever written about this Participant (including this new `DELETED` one) simply keeps pointing at an id that no longer exists in `participants`, with `subject_label` preserving their name for legibility. |

## Access Control Surface (no new entity, listed for completeness)

`GET /organiser/topics/{id}/audit` and `GET /organiser/participants/{id}/audit` — both already covered by
`SecurityConfig`'s existing `/organiser/**` → `ROLE_ORGANISER` rule (research.md §7); no new authorization code.
There is no `GET /organiser/groups/{id}/audit` — the Group detail page's "Audit" link points directly at the
Topic route above, for that Group's own `topicId` (research.md §9).
