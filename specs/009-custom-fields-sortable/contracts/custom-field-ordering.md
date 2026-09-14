# Contract: Custom Field Sort Index and Display Order

Extends `organiser/customfield/CustomFieldController` (`/organiser/custom-fields/**`, `ROLE_ORGANISER` only per
`SecurityConfig`) and the ordering guarantee of every page that lists Custom Fields. All routes and behaviour
not listed here are unchanged from 002/004.

## Display order (applies to every listing, FR-007–FR-009)

Wherever Custom Field Definitions are rendered as a list, columns, or choices, the order is:

1. `sort_index` ascending (negative before 0 before positive),
2. then `label` ascending, case-insensitive,
3. then `created_at` ascending (older first).

Ordering never changes which fields appear (FR-013). Views bound by this contract:

| View | Route | What is ordered |
|---|---|---|
| Custom Fields management list | `GET /organiser/custom-fields` | table rows |
| Organiser Participant detail | `GET /organiser/participants/{id}` | Custom Field value blocks |
| Compliance rule form | `GET /organiser/compliance` | "field" `<select>` options (`availableFields`) |
| Registration form | `GET /register` | profile field inputs |
| Self-edit form | `GET /profile/edit` | profile field inputs |
| Participants directory | `GET /participants` | overview `<th>` columns and each row's `<td>` cells, aligned |
| Participant detail (viewer) | `GET /participants/{id}` | visible field entries |

## GET /organiser/custom-fields

**Extended.** Each row additionally shows the field's `sort_index` (FR-010), in a column immediately after
`Label`. Rows are in display order.

## GET /organiser/custom-fields/new

**Extended.** The form gains `sort_index` (`<input type="number" step="1">`, label "Sort index"), pre-filled
with `0`.

## POST /organiser/custom-fields

**Extended.** New optional form field `sort_index`.

| Submitted `sort_index` | Result |
|---|---|
| absent or blank | stored as `0`; 303 → `/organiser/custom-fields` |
| whole number within signed 32-bit range (e.g. `-5`, `0`, `12`) | stored as given; 303 → `/organiser/custom-fields` |
| anything else (`abc`, `1.5`, `99999999999`) | **200**, form re-rendered with `error` = "Sort index must be a whole number"; label/type/required/options echoed back; **no row created** |

All other create validations (select types need ≥1 option, COUNTRY cannot be created) are unchanged and take
the same 200 re-render path.

## GET /organiser/custom-fields/{id}/edit

**Extended.** The form's `sort_index` input is pre-filled with the stored value. It is enabled for every field,
including one whose `field_type` control is disabled by the value-exists lock, and the COUNTRY row.

## POST /organiser/custom-fields/{id}

**Extended.** New form field `sort_index`, same acceptance table as create. On success the new value is stored
and the response is 303 → `/organiser/custom-fields`, where the edited row already sits in its new position
(FR-011). On a malformed value: 200 re-render with the error, nothing stored — including no change to label,
required, or flags submitted in the same request.

Setting `sort_index` is independent of the `field_type` lock: a request that changes only `sort_index` on a
field with recorded Participant values succeeds; a request that changes both `field_type` and `sort_index` on
such a field is rejected as a whole by the existing lock (nothing stored), exactly as today for the flags.

## Not in scope of this contract

- No new routes. No JSON/API surface — the app is server-rendered only.
- Option order inside a select-type field is unchanged.
- Sort index changes are not audited (spec Clarifications, FR-014).
