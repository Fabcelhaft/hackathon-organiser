# Phase 1 Data Model: Participant Registration Form, Profile Fields & Directory

Continues 002/003's conventions (see those features' own data-model.md files): UUIDv7 PKs via Postgres's
native `uuid PRIMARY KEY DEFAULT uuidv7()` for every entity table; `created_at`/`updated_at`
`timestamptz NOT NULL DEFAULT now()`; composite-key "pure association"/"payload" tables manipulated via
`DatabaseClient`, never a single-`@Id` `ReactiveCrudRepository`. `schema.sql` stays the single source of DDL,
`CREATE TABLE IF NOT EXISTS`/`ADD COLUMN IF NOT EXISTS` throughout, rerun on every startup via
`spring.sql.init.mode=always`. No new tables are introduced by this feature — every new concept extends an
existing 002/003 table (research.md §1–§5 explain why in each case).

## Modified Entities

### Custom Field Definition (002's `custom_field_definitions` — FR-011–FR-017; research.md §1, §3)

| Field | Type | Rules |
|---|---|---|
| `field_type` | enum: `FREE_TEXT` \| `MULTI_SELECT` \| `SINGLE_SELECT` \| `COUNTRY` | *(existing column, two new values)* `SINGLE_SELECT` behaves like `MULTI_SELECT` for options/locking but caps Participant selections to one (FR-012, research.md §2); `COUNTRY` is fixed — never created/deleted by an Organiser, at most one row ever exists (FR-013) |
| `public` | boolean | **NEW**, `NOT NULL DEFAULT false` (FR-016) — value visible to any other user, independent of `overview` |
| `overview` | boolean | **NEW**, `NOT NULL DEFAULT false` (FR-016) — column shown in the Participants overview table; without `public`, only Organisers and the owning Participant see it (FR-017) |
| `enabled` | boolean | **NEW**, `NOT NULL DEFAULT true` — meaningful only for the single `COUNTRY` row (FR-013, FR-015); toggled via `CustomFieldService.setCountryEnabled(boolean)`. For every other `field_type`, this column stays `true` and is never surfaced in the UI — a field's mere existence *is* "enabled" for those types (research.md §1) |

**New partial unique index**: `CREATE UNIQUE INDEX custom_field_definitions_country_key ON
custom_field_definitions (field_type) WHERE field_type = 'COUNTRY';` — guarantees at most one `COUNTRY`
definition can ever exist (FR-013), the same partial-unique-index-as-invariant pattern 002 uses for
`groups_topic_id_active_key`.

**Seed row** (idempotent, fires once): one `COUNTRY` definition, `label = 'Country'`, `required = false`,
`public = false`, `overview = false`, `enabled = false` (an Organiser must explicitly opt in, consistent with
the spec's private/off-by-default posture elsewhere).

Relationships unchanged from 002 (one-to-many with Custom Field Option, one-to-many with Custom Field Value) —
`COUNTRY` definitions have **no** `custom_field_options` rows; their option list is computed at read time from
`IsoCountryCatalog` (research.md §1), not stored.

### Custom Field Value / Custom Field Value Options (002's tables — no schema change; FR-012, FR-013)

No new columns. Behavioral extension only, in `ParticipantService`:

| Field type | Where the value lives |
|---|---|
| `FREE_TEXT` | `custom_field_values.free_text_value` *(unchanged)* |
| `SINGLE_SELECT` | `custom_field_value_options`, capped to **at most one** row per `(participant_id, custom_field_definition_id)` — enforced in `ParticipantService`, not schema (research.md §2), the same way `MULTI_SELECT`'s "at least one option on create" is a service-layer rule today |
| `MULTI_SELECT` | `custom_field_value_options`, zero or more rows *(unchanged)* |
| `COUNTRY` | `custom_field_values.free_text_value`, holding the selected ISO 3166-1 **alpha-2 code** (e.g. `"DE"`), validated against `IsoCountryCatalog.all()` (research.md §1) — reuses the `FREE_TEXT` storage shape rather than `custom_field_value_options`, since Country options are not persisted `custom_field_options` rows |

### Organiser Settings (003's `organiser_settings` — no new table; FR-007, FR-018, FR-021, FR-025)

| Field | Type | Rules |
|---|---|---|
| `max_registrations` | integer | **NEW**, nullable (`NULL` = no limit, FR-007's default "unconfigured"); `CHECK (max_registrations IS NULL OR max_registrations >= 1)` — database-level backstop for FR-007's "reject 0 or negative" rule, mirroring `OrganiserSettingsService`'s own pre-check |
| `self_edit_enabled` | boolean | **NEW**, `NOT NULL DEFAULT true` (FR-021; research.md §5) |
| `skill_visibility_enabled` | boolean | **NEW**, `NOT NULL DEFAULT false` (FR-018; spec Assumptions state this default explicitly) |
| `participants_directory_audience` | enum: `ORGANISERS_ONLY` \| `ORGANISERS_AND_PARTICIPANTS` \| `ALL_AUTHENTICATED` | **NEW**, `NOT NULL DEFAULT 'ORGANISERS_ONLY'` (FR-025; research.md §5) |

Read on every gated action (register, self-edit, directory access) with no in-memory caching — identical
freshness guarantee to 003's existing three toggles.

### Participant (002's `participants` table — no schema change; FR-001–FR-010, FR-021–FR-024)

Behavioral additions only, in `ParticipantService`:

- **`submitRegistration(UUID userId, ProfileFormSubmission submission)`** — supersedes 003's bare
  `selfRegister(UUID userId)` as the form's entry point (FR-001, FR-005). Inside one
  `TransactionalOperator`-wrapped transaction (research.md §4): acquires the advisory lock, rejects with
  `RegistrationCapacityReachedException` if `ACTIVE` count is already at `max_registrations` (FR-009),
  rejects with `ParticipantConflictException` if the target User's Participant record is currently
  `NOT_PARTICIPATED` (FR-006a) or if `selfRegistrationEnabled` is false, validates every submitted Custom
  Field value against its definition's type/required flag (FR-002, FR-003) with **zero** Skills required
  (FR-004), and only then creates a new `ACTIVE` record or reactivates an existing non-`ACTIVE` one
  (003's existing branch-on-existing-status logic, retained), writing Custom Field values and Skill
  selections in the same transaction. No partial record is ever visible on rejection (Edge Cases).
- **`submitSelfEdit(UUID participantId, ProfileFormSubmission submission)`** — FR-022; same validation as
  registration (FR-002, FR-003 reused), rejects with `ParticipantConflictException` if `selfEditEnabled` is
  currently false (re-read at submission time, not cached — FR-024) or the Participant's status is
  `NOT_PARTICIPATED` (FR-006a extends to self-edit: a dead-end status has no self-service paths at all).
- **`selfRevoke`** *(003's existing method)* — gains the same `NOT_PARTICIPATED` lockout guard (FR-006a);
  otherwise unchanged.
- **Visibility-resolved reads** — new read-model methods for the directory (`findDirectoryListing()`,
  ordered alphabetically ascending by `users.display_name`, FR-027a) and detail view
  (`findDetailForViewer(UUID participantId, UUID viewerUserId, boolean viewerIsOrganiser)`), each resolving
  every Custom Field value and the Skill list against `(definition.public, definition.overview,
  skillVisibilityEnabled, viewerIsOrganiser, viewerIsOwner)` per research.md §3/FR-017/FR-019, and marking
  each value "visible to others" or "private" for FR-020's self-view labeling.

`ParticipantStatus` (`ACTIVE`/`NOT_PARTICIPATED`/`REVOKED`) is unchanged — this feature only adds *guards*
around the existing enum, never new values.

## New value types (application-level only, no new tables)

- **`IsoCountryCatalog.Country(String code, String name)`** — one entry per ISO 3166-1 alpha-2 code, sourced
  from `java.util.Locale` (research.md §1); not persisted.
- **`ProfileFormSubmission`** — the parsed shape of one registration/self-edit form POST: a
  `Map<UUID customFieldDefinitionId, FieldAnswer>` (each `FieldAnswer` being either a free-text string, a set
  of option ids, or a country code, matching the definition's `field_type`) plus a `List<UUID> skillIds`.
  Purely a service-layer/controller-layer DTO, not a persisted entity.
- **`DirectoryAudience`** — enum, see Organiser Settings above.

## Schema additions (illustrative DDL — final statements land in `schema.sql`)

```sql
-- Custom Field Definition: single-select + Country support, visibility flags (research.md §1, §3)
ALTER TABLE custom_field_definitions ADD COLUMN IF NOT EXISTS public boolean NOT NULL DEFAULT false;
ALTER TABLE custom_field_definitions ADD COLUMN IF NOT EXISTS overview boolean NOT NULL DEFAULT false;
ALTER TABLE custom_field_definitions ADD COLUMN IF NOT EXISTS enabled boolean NOT NULL DEFAULT true;

CREATE UNIQUE INDEX IF NOT EXISTS custom_field_definitions_country_key
    ON custom_field_definitions (field_type) WHERE field_type = 'COUNTRY';

INSERT INTO custom_field_definitions (label, field_type, required, public, overview, enabled)
SELECT 'Country', 'COUNTRY', false, false, false, false
WHERE NOT EXISTS (SELECT 1 FROM custom_field_definitions WHERE field_type = 'COUNTRY');

-- Organiser Settings: registration cap, self-edit, skill visibility, directory audience (research.md §5)
ALTER TABLE organiser_settings ADD COLUMN IF NOT EXISTS max_registrations integer;
ALTER TABLE organiser_settings
    ADD CONSTRAINT organiser_settings_max_registrations_check
    CHECK (max_registrations IS NULL OR max_registrations >= 1);
ALTER TABLE organiser_settings ADD COLUMN IF NOT EXISTS self_edit_enabled boolean NOT NULL DEFAULT true;
ALTER TABLE organiser_settings ADD COLUMN IF NOT EXISTS skill_visibility_enabled boolean NOT NULL DEFAULT false;
ALTER TABLE organiser_settings
    ADD COLUMN IF NOT EXISTS participants_directory_audience text NOT NULL DEFAULT 'ORGANISERS_ONLY';
```

`ALTER TABLE ... ADD CONSTRAINT` has no `IF NOT EXISTS` form in PostgreSQL; `schema.sql`'s idempotent-rerun
statement for this line is guarded with a `DO $$ ... IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE
conname = 'organiser_settings_max_registrations_check') ... $$` block (same idempotency guarantee as every
other statement here, just PostgreSQL's more verbose syntax for a constraint that has no native
`IF NOT EXISTS`).

## Entity relationship summary

```text
User 1───0..1 Participant                    (002/003, unchanged)
CustomFieldDefinition 1───* CustomFieldOption (002, unchanged — COUNTRY has none)
CustomFieldDefinition 1───* CustomFieldValue  (002, unchanged)
CustomFieldValue *───* CustomFieldOption      (MULTI_SELECT: many; SINGLE_SELECT: ≤1 — research.md §2)
CustomFieldValue.free_text_value              (FREE_TEXT: any text; COUNTRY: one ISO alpha-2 code — research.md §1)
OrganiserSettings (singleton, extended)       — read by ParticipantService (capacity, self-edit gate) and
                                                 ParticipantsDirectoryAccessPolicy (audience, skill visibility)
```
