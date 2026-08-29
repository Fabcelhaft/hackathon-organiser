# Contract: Skill & Custom Field Catalog Management (Story 2)

Covers FR-008, FR-008a, FR-009, FR-010, FR-011, FR-012, FR-012a, FR-012b, FR-020, FR-023. Server-rendered
Thymeleaf views. All routes require `ROLE_ORGANISER` (FR-022).

## Skills

| Method | Path | Purpose | Success | Failure |
|---|---|---|---|---|
| `GET` | `/organiser/skills` | List all Skills | 200, list view | — |
| `GET` | `/organiser/skills/new` | New-Skill form | 200, form view | — |
| `POST` | `/organiser/skills` | Create a Skill (`name`) | Redirect 303 → `/organiser/skills` | 200 form re-rendered with error if `name` duplicates an existing Skill, case-insensitively (FR-008a) |
| `GET` | `/organiser/skills/{id}/edit` | Edit-Skill form | 200, form view | 404 if `id` unknown |
| `POST` | `/organiser/skills/{id}` | Rename a Skill | Redirect 303 → `/organiser/skills` | 200 form re-rendered with error on duplicate name (FR-008a); 404 if `id` unknown |
| `POST` | `/organiser/skills/{id}/delete` | Remove a Skill | Redirect 303 → `/organiser/skills` | 409-style: form/list re-rendered with an error naming which Participants/Topics still reference it (FR-023), if any do |

## Custom Field Definitions

| Method | Path | Purpose | Success | Failure |
|---|---|---|---|---|
| `GET` | `/organiser/custom-fields` | List all Custom Field Definitions, each showing type and required flag | 200, list view | — |
| `GET` | `/organiser/custom-fields/new` | New-field form (choose `field_type`; when `MULTI_SELECT`, collect an initial option list, FR-012) | 200, form view | — |
| `POST` | `/organiser/custom-fields` | Create a Custom Field Definition (`label`, `field_type`, `required`, `options[]` when `MULTI_SELECT`) | Redirect 303 → `/organiser/custom-fields` | 200 form re-rendered with error if `MULTI_SELECT` submitted with zero options (FR-012) |
| `GET` | `/organiser/custom-fields/{id}/edit` | Edit-field form | 200, form view; `field_type` control disabled/blocked once the definition has any recorded value (FR-012a) | 404 if `id` unknown |
| `POST` | `/organiser/custom-fields/{id}` | Update `label`/`required`, and `field_type` only if no Participant value exists yet | Redirect 303 → `/organiser/custom-fields` | 200 form re-rendered with error if the request attempts a `field_type` change while values exist (FR-012a); 404 if `id` unknown |
| `POST` | `/organiser/custom-fields/{id}/delete` | Remove a Custom Field Definition | Redirect 303 → `/organiser/custom-fields` | Form/list re-rendered with an error naming which Participants still have a value for it (FR-023), if any do |
| `POST` | `/organiser/custom-fields/{id}/options` | Add a selectable option (`label`) to a `MULTI_SELECT` definition | Redirect 303 → `/organiser/custom-fields/{id}/edit` | 200 re-rendered with error on duplicate option label within the definition |
| `POST` | `/organiser/custom-fields/{id}/options/{optionId}/delete` | Remove a selectable option | Redirect 303 → `/organiser/custom-fields/{id}/edit` | Re-rendered with an error naming referencing Participants, if any Participant value still selects that option (FR-012b) |

## Behavioral notes

- Renaming a Skill or a Custom Field's label is reflected everywhere it is referenced (Acceptance Scenario 4,
  Story 2) because Participant/Topic associations store the foreign key, not a copy of the name.
- "Available for selection" (Acceptance Scenarios 1–3, Story 2) means: immediately visible in the relevant
  picklist on the Participant (FR-019) and Topic (FR-021) forms with no deploy step (SC-003).
