# Contract: Custom Field Management Extensions (Organiser)

Extends 002's `organiser/customfield/CustomFieldController` (`/organiser/custom-fields/**`,
`ROLE_ORGANISER` only per `SecurityConfig`). All routes/behavior not listed here are unchanged from 002.

## GET /organiser/custom-fields

**Extended.** The list now shows, per row: label, `field_type` (now one of `FREE_TEXT` | `SINGLE_SELECT` |
`MULTI_SELECT` | `COUNTRY`), `required`, `public`, `overview`. The `COUNTRY` row additionally shows its
`enabled` state and an Enable/Disable action instead of Edit's field-type control (its type/existence is
fixed — only its flags and enabled state are editable, research.md §1).

## GET/POST /organiser/custom-fields/new

**Extended.** `field_type` select gains `SINGLE_SELECT`. Choosing `SINGLE_SELECT` reveals the same
option-list input `MULTI_SELECT` already uses (FR-012: at least one option required, identical validation to
`MULTI_SELECT`'s existing rule). `COUNTRY` is **not** an option here — it cannot be created (there is always
exactly zero or one `COUNTRY` row, seeded once; see the Country section below).

- 200 + created — `SINGLE_SELECT` with ≥1 option, or `FREE_TEXT`/`MULTI_SELECT` (unchanged 002 behavior).
- 200 + form re-rendered with error — `SINGLE_SELECT` or `MULTI_SELECT` submitted with zero options
  (`CustomFieldConflictException`, same message pattern as today).

New form fields on both create and edit: `public` and `overview` checkboxes (FR-016), independently
toggleable, defaulting unchecked for a new field.

## GET/POST /organiser/custom-fields/{id}/edit

**Extended.**
- `public`/`overview` checkboxes always editable, regardless of `field_type` or whether values exist yet — no
  lock (FR-016 has no locking rule, unlike `field_type`).
- `field_type` change to/from `SINGLE_SELECT` follows the exact same lock 002 already enforces for
  `MULTI_SELECT`: rejected once any Participant value references this definition
  (`CustomFieldConflictException`, FR-012a's existing rule, unchanged wording).
- Attempting to change a `COUNTRY` row's `field_type`, or to delete it via `POST /organiser/custom-
  fields/{id}/delete`, is rejected with `CustomFieldConflictException` ("The Country field's type cannot be
  changed" / "The Country field cannot be deleted — use Enable/Disable instead").

## POST /organiser/custom-fields/{id}/country/enable, .../disable

**New** (FR-013, FR-015). Organiser-only, `{id}` must reference the one `COUNTRY`-typed definition (404 for
any other id, or if none exists — it is always seeded, so this is defensive only).

- **enable**: sets `enabled = true`. The field appears on the next-rendered registration/edit form as a
  searchable single-select of the full ISO 3166 list (FR-013).
- **disable**: sets `enabled = false`. The field disappears from registration/edit forms; any Participant
  values already recorded for it remain stored and are still shown on those Participants' existing records
  (FR-015) — this action never deletes `custom_field_values` rows.

Both redirect to `GET /organiser/custom-fields?flash=...` (FR-032 in-progress/success feedback pattern,
matching every other settings action in this codebase).

## Registration/edit/detail rendering contract (all field types, FR-002a)

Regardless of caller (registration form, self-edit form, directory detail view), a Custom Field —
`SINGLE_SELECT`, `COUNTRY`, or any pre-existing type — is rendered by its own `label` and a control/display
matching its type, with **no** "Custom Field" heading, tag, or badge (FR-002a). This applies to every
participant-facing template introduced by `contracts/registration-and-self-edit.md` and
`contracts/participants-directory.md`; it does **not** apply to this file's own
`/organiser/custom-fields/**` screens, where "Custom Field" terminology is expected and unchanged.

The Country control specifically: a searchable combobox (FR-013, FR-045) — a text input with
`role="combobox"`/`aria-expanded`/`aria-controls` wired to a `role="listbox"` populated from
`IsoCountryCatalog.all()` (research.md §1), filtered client-side by `country-select.js` as the user types, no
server round-trip. The chosen ISO alpha-2 code is submitted via a hidden input.
