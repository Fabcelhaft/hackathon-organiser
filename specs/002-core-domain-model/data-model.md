# Phase 1 Data Model: Core Domain Model & Organiser Management

Identifier convention (FR-025, per [research.md](research.md) §1): every table below marked **UUIDv7 PK**
declares its `id` column as `uuid PRIMARY KEY DEFAULT uuidv7()`, using PostgreSQL 18's native `uuidv7()`
function — there is no application-level ID generation. Pure association/value tables use composite primary
keys instead (per spec Assumptions: "mapping tables ... may use composite or surrogate keys"). All tables
carry `created_at timestamptz NOT NULL DEFAULT now()`; tables an organiser can edit after creation also carry
`updated_at timestamptz NOT NULL DEFAULT now()`.

## Entities

### User — UUIDv7 PK (FR-002, FR-004, FR-005)

| Field | Type | Rules |
|---|---|---|
| `id` | UUID (v7) | PK, `DEFAULT uuidv7()` |
| `oidc_subject` | text | `NOT NULL`, `UNIQUE` — the IdP's stable subject identifier (edge case: match on this, never on mutable profile fields) |
| `display_name` | text | `NOT NULL`; refreshed from IdP claims on every login |
| `email` | text | nullable; refreshed from IdP claims on every login |
| `organiser` | boolean | `NOT NULL DEFAULT false`; sole source of the Organiser role (FR-005), toggled only via organiser views (FR-004) |
| `created_at`, `updated_at` | timestamptz | |

Relationships: one User → zero-or-one Participant (FR-006a); one User → many Topics (as creator). The
Standard role is not a stored field — it is implicit for every authenticated User (FR-003).

### Participant — UUIDv7 PK (FR-006a, FR-006b, FR-007)

| Field | Type | Rules |
|---|---|---|
| `id` | UUID (v7) | PK, `DEFAULT uuidv7()` |
| `user_id` | UUID | `NOT NULL`, `UNIQUE` FK → `users.id` — enforces "at most one Participant per User" (FR-006a) |
| `status` | enum: `ACTIVE` \| `NOT_PARTICIPATED` \| `REVOKED` | `NOT NULL DEFAULT 'ACTIVE'` (FR-006b sets this on creation; FR-007 restricts to exactly one of these three at a time) |
| `created_at`, `updated_at` | timestamptz | |

Relationships: many-to-many with Skill via `participant_skills`; one-to-many with Custom Field Value (via
`custom_field_values`); zero-or-one *active* Group membership at a time (FR-017, enforced on `group_members`,
not a column here).

Derived state (not stored): a Participant is **incomplete** (FR-027) when at least one
`custom_field_definitions` row has `required = true` and no corresponding `custom_field_values` row exists for
that Participant. Computed at read time so it can never go stale relative to field-definition changes.

State transitions: `status` can be set to any of the three values by an Organiser at any time (FR-007 defines
the closed set, not a restricted transition graph). Regardless of status — including the terminal `REVOKED`
state — existing Skill selections, Custom Field values, and Group memberships remain visible (edge case:
historical record-keeping, no cascading deletes on status change).

### Skill — UUIDv7 PK (FR-008, FR-008a)

| Field | Type | Rules |
|---|---|---|
| `id` | UUID (v7) | PK, `DEFAULT uuidv7()` |
| `name` | text | `NOT NULL`; unique index on `lower(name)` — FR-008a rejects a duplicate/near-duplicate (case-insensitive) name on create or rename |
| `created_at`, `updated_at` | timestamptz | |

Relationships: many-to-many with Participant (`participant_skills`) and Topic (`topic_skills`). Cannot be
removed while either association still references it (FR-023) — see Referential Guards below.

### Custom Field Definition — UUIDv7 PK (FR-011, FR-012, FR-012a, FR-026)

| Field | Type | Rules |
|---|---|---|
| `id` | UUID (v7) | PK, `DEFAULT uuidv7()` |
| `label` | text | `NOT NULL` |
| `field_type` | enum: `FREE_TEXT` \| `MULTI_SELECT` | `NOT NULL`; **locked** once any `custom_field_values`/`custom_field_value_options` row references this definition (FR-012a, service-layer check per [research.md](research.md) §4) |
| `required` | boolean | `NOT NULL DEFAULT false` (FR-026); drives the Participant "incomplete" computation (FR-027) |
| `created_at`, `updated_at` | timestamptz | |

Relationships: one-to-many with Custom Field Option (only populated/meaningful when `field_type =
MULTI_SELECT`, FR-012); one-to-many with Custom Field Value.

### Custom Field Option — UUIDv7 PK (FR-012, FR-012b)

| Field | Type | Rules |
|---|---|---|
| `id` | UUID (v7) | PK, `DEFAULT uuidv7()` |
| `custom_field_definition_id` | UUID | `NOT NULL` FK → `custom_field_definitions.id` |
| `label` | text | `NOT NULL`; unique per definition (`UNIQUE (custom_field_definition_id, lower(label))`) |
| `created_at`, `updated_at` | timestamptz | |

Only meaningful for `MULTI_SELECT` definitions. Cannot be removed while any `custom_field_value_options` row
still references it (FR-012b) — see Referential Guards below.

### Custom Field Value — composite key, no independent UUID (FR-013, FR-014)

One row per (Participant, Custom Field Definition) pair — this is the "association carrying a payload" case
the spec's Assumptions describe for mapping tables, so it is intentionally *not* in FR-025's explicit UUIDv7
list.

| Field | Type | Rules |
|---|---|---|
| `participant_id` | UUID | FK → `participants.id`; part of composite PK |
| `custom_field_definition_id` | UUID | FK → `custom_field_definitions.id`; part of composite PK |
| `free_text_value` | text | populated only when the definition's `field_type = FREE_TEXT`; `NULL` for `MULTI_SELECT` rows (FR-014: value must conform to the field's configured type) |
| `created_at`, `updated_at` | timestamptz | |

**PK**: `(participant_id, custom_field_definition_id)`.

For `MULTI_SELECT` definitions, the selected options are recorded in a child table:

**`custom_field_value_options`** — composite key
| Field | Type | Rules |
|---|---|---|
| `participant_id` | UUID | part of composite PK; composite FK → `custom_field_values(participant_id, custom_field_definition_id)` |
| `custom_field_definition_id` | UUID | part of composite PK; composite FK as above |
| `custom_field_option_id` | UUID | FK → `custom_field_options.id`; part of composite PK; service layer verifies the option belongs to the same `custom_field_definition_id` (FR-014) |

**PK**: `(participant_id, custom_field_definition_id, custom_field_option_id)`.

### Topic — UUIDv7 PK (FR-015)

| Field | Type | Rules |
|---|---|---|
| `id` | UUID (v7) | PK, `DEFAULT uuidv7()` |
| `name` | text | `NOT NULL` |
| `description` | text | `NOT NULL` |
| `created_by_user_id` | UUID | `NOT NULL` FK → `users.id`; retained even if that User's access is later revoked (edge case: historical creator reference never cleared) |
| `created_at`, `updated_at` | timestamptz | |

Relationships: many-to-many with Skill via `topic_skills`; zero-or-one *active* Group at a time (FR-016a).

### Group — UUIDv7 PK (FR-016, FR-016a, FR-016b)

| Field | Type | Rules |
|---|---|---|
| `id` | UUID (v7) | PK, `DEFAULT uuidv7()` |
| `topic_id` | UUID | `NOT NULL` FK → `topics.id` |
| `status` | enum: `ACTIVE` \| `DISBANDED` | `NOT NULL DEFAULT 'ACTIVE'` |
| `disbanded_at` | timestamptz | nullable; set when `status` transitions to `DISBANDED` |
| `created_at`, `updated_at` | timestamptz | |

**Partial unique index**: `UNIQUE (topic_id) WHERE status = 'ACTIVE'` — enforces "at most one Group per Topic
at a time" (FR-016a) while allowing a new Group row for the same Topic after the prior one is disbanded
(FR-016b), and keeping the disbanded row queryable for history.

State transitions: `ACTIVE → DISBANDED` only (one-way/terminal; "disbanding" always creates the option of a
*new* Group row later, never an "un-disband").

### Group Member — composite key, no independent UUID (FR-017, FR-016b)

Pure association table (Group ↔ Participant) with one extra flag needed to preserve history across a
disband.

| Field | Type | Rules |
|---|---|---|
| `group_id` | UUID | FK → `groups.id`; part of composite PK |
| `participant_id` | UUID | FK → `participants.id`; part of composite PK |
| `active` | boolean | `NOT NULL DEFAULT true`; flipped to `false` for every row when the parent Group is disbanded |
| `joined_at` | timestamptz | `NOT NULL DEFAULT now()` |

**PK**: `(group_id, participant_id)`. **Partial unique index**: `UNIQUE (participant_id) WHERE active` —
enforces "at most one *active* Group per Participant" (FR-017) while a disbanded Group's former members remain
listed under it (FR-016b).

## Pure association tables (no payload, composite PK, excluded from FR-025 by the spec's own Assumptions)

| Table | Columns (composite PK) | Purpose |
|---|---|---|
| `participant_skills` | `participant_id`, `skill_id` | Participant ↔ Skill (FR-009) |
| `topic_skills` | `topic_id`, `skill_id` | Topic ↔ Skill (FR-010) |

## Referential guards (FR-023, FR-012b)

Before deleting a `Skill`, the service layer checks `participant_skills` and `topic_skills` for any row
referencing it; before deleting a `Custom Field Definition`, it checks `custom_field_values` /
`custom_field_value_options`; before deleting a `Custom Field Option`, it checks
`custom_field_value_options`. Any match blocks the delete with a message naming what still references it
(FR-023). All corresponding foreign keys additionally use the database's default `NO ACTION` behavior as a
defence-in-depth backstop (see [research.md](research.md) §4).

## Entity relationship summary

```text
User 1───0..1 Participant           User 1───* Topic (creator)
Participant *───* Skill             Topic *───* Skill
Participant 1───* CustomFieldValue  CustomFieldDefinition 1───* CustomFieldOption
CustomFieldValue *───* CustomFieldOption (MULTI_SELECT only, via custom_field_value_options)
Topic 1───0..1 Group (active)       Group *───* Participant (via group_members, active flag)
```
