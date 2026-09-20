# Phase 1 Data Model: Event-Driven Task Rules

**Feature**: `011-event-driven-task-rules` | **Date**: 2026-09-19 | **Spec**: [spec.md](./spec.md)

All DDL is appended to the existing `src/main/resources/schema.sql`, which is an idempotent
`CREATE TABLE IF NOT EXISTS` script run at startup. There is no migration framework anywhere in this
project, and this feature does not introduce one.

---

## Task Rule — extends the existing `event_destinations` table

A Task Rule is **not a new entity**. It is a third `type` of the existing Event Destination
(research.md §1), which is what lets FR-032/FR-033/FR-034 reuse the existing list, form, and service
wholesale, and what makes FR-003's cross-type unique name a single existing index.

### Enum change

`EventDestinationType` gains a third constant:

```java
public enum EventDestinationType {
    KAFKA,
    HTTP_POST,
    TASK
}
```

Persisted as its `name()` in the existing `event_destinations.type` text column — Spring Data
R2DBC's `MappingR2dbcConverter` handles enum ↔ String natively, so no converter is registered, exactly
as for the two existing constants.

### New columns on `event_destinations`

| Column | Type | Null | Meaning | Requirement |
|---|---|---|---|---|
| `task_title_pattern` | `text` | yes | The title pattern, wildcards included, unresolved | FR-003, FR-009 |
| `task_default_assignee_user_id` | `uuid` | yes | Optional default assignee; FK to `users(id)` | FR-005 |

Both are nullable because a `KAFKA` or `HTTP_POST` row leaves them unset — the same shape the
existing `kafka_*` and `http_url` columns already have.

```sql
ALTER TABLE event_destinations ADD COLUMN IF NOT EXISTS task_title_pattern text;
ALTER TABLE event_destinations ADD COLUMN IF NOT EXISTS task_default_assignee_user_id uuid
    REFERENCES users (id);
```

### Widened CHECK constraint

The existing `event_destinations_type_fields_check` currently admits only two types, so a `TASK` row
would be rejected outright. It is dropped and recreated with a third branch — the same
drop-and-recreate the script already performs:

```sql
ALTER TABLE event_destinations DROP CONSTRAINT IF EXISTS event_destinations_type_fields_check;
ALTER TABLE event_destinations
    ADD CONSTRAINT event_destinations_type_fields_check
    CHECK (
        (type = 'KAFKA' AND kafka_bootstrap_servers IS NOT NULL AND kafka_topic IS NOT NULL)
        OR (type = 'HTTP_POST' AND http_url IS NOT NULL)
        OR (type = 'TASK' AND task_title_pattern IS NOT NULL)
    );
```

`task_default_assignee_user_id` is deliberately absent from the constraint — FR-005 makes the default
assignee optional.

### Validation (service layer, `EventDestinationService`)

Extends the existing `validateTypeFields` switch with a `TASK` branch. The CHECK constraint is the
structural guarantee; the service is the friendly-error guarantee, matching the existing split.

| Rule | Message shape | Requirement |
|---|---|---|
| Name present and unique across **all** handler types | reuses existing `rejectIfNameTaken` | FR-003 |
| Title pattern present and non-blank | "A task title pattern is required for a Task Rule" | FR-007 |
| Every wildcard well-formed (closed, non-empty path, no empty segment) | names the offending wildcard | FR-007 |
| Zero Event Types **permitted** | no validation — matches KAFKA/HTTP_POST | FR-003, FR-034 |
| New Rule starts disabled | reuses existing `create` behaviour | FR-006 |
| Stale-write guard on update | reuses existing `updatedAt` comparison | inherited from 007 FR-018 |

### Behaviour inherited unchanged

Create, update, enable, disable, the `updatedAt` stale-write guard, and Event Type subscription
replacement via `event_destination_event_types` all work as they already do. FR-034 is satisfied by
*not writing code*.

### Behaviour changed

`EventDestinationService.delete` gains one step for `TASK` rows: remove undone Tasks before deleting
the row (FR-002a, research.md §7). For `KAFKA` rows it still disposes the producer cache; for
`HTTP_POST` it is still a plain delete.

---

## Task — new `tasks` table

A unit of work produced by a Task Rule when an Event occurred. Created only by the system; never by
a person (Assumptions).

```sql
CREATE TABLE IF NOT EXISTS tasks (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    event_destination_id uuid REFERENCES event_destinations (id) ON DELETE SET NULL,
    rule_name text NOT NULL,
    event_type text NOT NULL,
    title text NOT NULL,
    assignee_user_id uuid REFERENCES users (id),
    done boolean NOT NULL DEFAULT false,
    done_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS tasks_done_created_idx ON tasks (done, created_at DESC);
```

| Field | Meaning | Requirement |
|---|---|---|
| `id` | Assigned by Postgres via `DEFAULT uuidv7()` — no application-side ID generation exists anywhere in this codebase | — |
| `event_destination_id` | The Rule that produced it. Nullable, `ON DELETE SET NULL` | FR-017, FR-002a |
| `rule_name` | The Rule's name snapshotted at creation, so a done Task still names its Rule after the Rule is deleted (research.md §7) | FR-017 |
| `event_type` | The `EventType` name that produced it | FR-017 |
| `title` | The **resolved** title, frozen at creation; never recomputed | FR-013, FR-017 |
| `assignee_user_id` | Optional; FK to `users(id)` | FR-005, FR-017 |
| `done` / `done_at` | Completion state and moment; `done_at` null while undone | FR-017, FR-025 |
| `created_at` | Creation moment; drives ordering and the secondary text | FR-017, FR-021a, FR-028 |

**Why `rule_name` duplicates data**: it is a deliberate snapshot, not a normalisation mistake. `ON
DELETE SET NULL` is what keeps done Tasks alive past their Rule's deletion (FR-002a), and it would
otherwise erase the very association FR-017 requires them to record. `ParticipantService` sets the
same precedent with its audit `subject_label`.

**Why the index is `(done, created_at DESC)`**: every list query filters on `done` and orders by
`created_at` descending (FR-022, FR-028), so this covers both views with one index.

### State transitions

```text
                created by an Event
                        │
                        ▼
                    ┌────────┐   Done (FR-025)    ┌──────┐
                    │ undone │ ─────────────────▶ │ done │
                    │        │ ◀───────────────── │      │
                    └────────┘  Reopen (FR-026)   └──────┘
                        │
                        │ Rule deleted (FR-002a)
                        ▼
                     removed                      done Tasks survive,
                                                  event_destination_id → NULL
```

Both transitions are **conditional updates**, so repeating one is a no-op rather than an error
(FR-027, research.md §12):

- Done: `UPDATE tasks SET done = true, done_at = now() WHERE id = :id AND done = false`
- Reopen: `UPDATE tasks SET done = false, done_at = NULL WHERE id = :id AND done = true`
- Assign: `UPDATE tasks SET assignee_user_id = :uid WHERE id = :id` — last write wins, no guard

### Validation

The Task is machine-created, so there is no user-supplied field to validate. The title is
constrained at construction instead: resolved → trimmed → fallback if blank (FR-014) → truncated to
500 characters with an ellipsis (FR-015), in that order (research.md §5).

---

## Relationships

```text
users ──────┬──< event_destinations.task_default_assignee_user_id   (optional, FR-005)
            └──< tasks.assignee_user_id                             (optional, FR-005/FR-024)

event_destinations ──< tasks.event_destination_id   (nullable, ON DELETE SET NULL, FR-002a)
                   ──< event_destination_event_types (unchanged, inherited from feature 007)
```

A Task whose `assignee_user_id` points at a User who is no longer an Organiser renders as
unassigned, because the list query and the dropdown are both filtered on `organiser = true`
(research.md §9). The row is left untouched; nothing is rewritten on a role change.

---

## Query shapes

| Purpose | Shape | Requirement |
|---|---|---|
| Default list | `WHERE done = false ORDER BY created_at DESC` — no limit | FR-022, FR-028, FR-028a |
| Done-inclusive list | `ORDER BY created_at DESC LIMIT 201`, render 200, notice if 201 returned | FR-023, FR-028a |
| Assignee options | `findByOrganiserTrueOrderByDisplayNameAsc()` — derived query, loaded once per render | FR-021 |
| Undone count for a Rule | `SELECT count(*) FROM tasks WHERE event_destination_id = :id AND done = false` | FR-002a |
| Rule lookup on Event | existing `findEnabledDestinationsFor(eventType)` — unchanged | FR-016, FR-019 |

The last row matters: **no new query is needed to find which Rules fire.** The existing
`findEnabledDestinationsFor` already returns every enabled destination subscribed to an Event Type,
whatever its type, so FR-016 and FR-019 come for free — with the one caveat that the query's manual
row mapper must be extended to populate the two new columns.
