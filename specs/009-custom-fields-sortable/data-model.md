# Phase 1 Data Model: Sortable Custom Fields

One existing entity gains one attribute and one derived ordering rule. No new tables, no new entities, no new
relationships, no state machine.

## Modified Entity

### Custom Field Definition (`custom_field_definitions`; FR-001–FR-007, FR-012)

| Attribute | Type | Constraints | Notes |
|---|---|---|---|
| `sort_index` (Java `sortIndex`) | `integer` | `NOT NULL DEFAULT 0`; any signed 32-bit value | **New.** Ascending display precedence; lower first. Backfilled to 0 for all pre-existing rows by the column default (FR-002). Seeded COUNTRY row lands at 0. |
| `label` | `text` | unchanged | Secondary ordering key, compared case-insensitively (FR-007). |
| `created_at` | `timestamptz` | unchanged | Tertiary ordering key, oldest first — the clarified tie-break for identical labels at the same index (FR-012). |
| all others (`id`, `field_type`, `required`, `public`, `overview`, `enabled`, `updated_at`) | — | unchanged | Visibility/behaviour flags are untouched by this feature (FR-013). |

**Validation rules** (enforced in `CustomFieldController` before the service is invoked; research.md §4):

- Form field `sort_index` absent or blank → `0` (spec Edge Cases, FR-004).
- Otherwise must parse as a whole number within `Integer.MIN_VALUE..Integer.MAX_VALUE`; anything else
  (letters, decimals, out of range) → rejected, form re-rendered with an error, nothing persisted (FR-005).
- No lock applies to this attribute: it is writable regardless of `field_type`, of whether Participant values
  exist, and on the COUNTRY row (FR-006).

**Derived ordering rule** — `CustomFieldDefinition.DISPLAY_ORDER` (research.md §2), the single definition
used by every listing:

1. `sortIndex` ascending
2. `label` ascending, `String.CASE_INSENSITIVE_ORDER`
3. `createdAt` ascending (`nullsLast`)

### Custom Field Option (`custom_field_options`; FR-015–FR-019, User Story 4 — added during implementation)

| Attribute | Type | Constraints | Notes |
|---|---|---|---|
| `sort_index` (Java `sortIndex`) | `integer` | `NOT NULL DEFAULT 0`; any signed 32-bit value | **New.** Ascending display precedence within the owning field. Backfilled to 0 for pre-existing options; initial options of a newly created field are stored at 0. |
| `label` | `text` | unchanged | Secondary key, case-insensitive. Not editable (as today). |
| `created_at` | `timestamptz` | unchanged | Tertiary key, oldest first. |

**Validation rules**: identical to the field's (`CustomFieldController`, research.md §7): blank → 0; otherwise a
whole number in `int` range, else rejected with an edit-page re-render and no write. `updateOptionSortIndex`
requires the option to belong to the definition in the URL; otherwise 404.

**Derived ordering rule** — `CustomFieldOption.DISPLAY_ORDER`: `sortIndex` asc → `label` case-insensitive →
`createdAt` asc (`nullsLast`). Independent of the owning definition's index.

## Service Surface Changes

### `CustomFieldService`

| Method | Change |
|---|---|
| `Flux<CustomFieldDefinition> findAll()` | Now emits in `DISPLAY_ORDER`. |
| `Flux<CustomFieldDefinition> registrationFields()` | Filters the ordered `findAll()` stream; order preserved. |
| `Mono<CustomFieldDefinition> create(String label, CustomFieldType fieldType, boolean required, List<String> optionLabels, boolean public_, boolean overview, int sortIndex)` | New trailing `sortIndex` parameter, persisted on the new definition. |
| `Mono<CustomFieldDefinition> update(UUID id, String label, boolean required, CustomFieldType requestedFieldType, Boolean public_, Boolean overview, int sortIndex)` | New trailing `sortIndex` parameter, applied unconditionally (outside the type-change guard and the COUNTRY type restriction). |
| `Flux<CustomFieldOption> findOptions(UUID definitionId)` | (US4) Now emits in `CustomFieldOption.DISPLAY_ORDER`. |
| `Mono<CustomFieldOption> addOption(UUID definitionId, String label, int sortIndex)` | (US4) New trailing `sortIndex` parameter. |
| `Mono<CustomFieldOption> updateOptionSortIndex(UUID definitionId, UUID optionId, int sortIndex)` | (US4) New. Empty unless the option exists and belongs to `definitionId`. |

### `ParticipantService` (behavioural only)

| Method | Change |
|---|---|
| `loadCustomFieldValueViews(UUID)` (private; feeds `findDetail` and `findDetailForViewer`) | Reads `customFieldService.findAll()` instead of `customFieldDefinitionRepository.findAll()`. |
| `findDirectoryListing()` | Same redirection; the `filter(isOverview)` and per-row `loadFieldViews` are unchanged, so overview columns inherit the order. |
| `loadFieldViews(...)`, `blankFieldViews(...)` (private; feed every rendered `CustomFieldValueView.options()`) | (US4) Read `customFieldService.findOptions(...)` instead of `customFieldOptionRepository.findByCustomFieldDefinitionId(...)`. The two validation-only option loads are unchanged. |

`CustomFieldDefinitionRepository` keeps its `findById` usages in `ParticipantService`; no repository method is
added or removed.

## Read Models (unchanged shapes, now ordered)

- `CustomFieldValueView` lists (registration/self-edit form, both detail views) — element order = `DISPLAY_ORDER`.
- `DirectoryRow.overviewValues` — element order = `DISPLAY_ORDER` restricted to `overview = true`; identical across
  rows, so the header derived from `rows[0]` aligns with every row's cells.
- `ComplianceController` `availableFields` — a stream filter over the ordered `findAll()`; order preserved.
