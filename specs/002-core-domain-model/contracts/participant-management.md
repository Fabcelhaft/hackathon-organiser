# Contract: Participant Management (Story 3)

Covers FR-006b, FR-007, FR-009, FR-013, FR-014, FR-019, FR-027. Server-rendered Thymeleaf views. All routes
require `ROLE_ORGANISER` (FR-022).

| Method | Path | Purpose | Success | Failure |
|---|---|---|---|---|
| `GET` | `/organiser/participants` | List all Participants: User display name, status, and an "incomplete" indicator when any required Custom Field is unmet (FR-027, SC-007) | 200, list view | — |
| `GET` | `/organiser/participants/new` | Registration form: pick an existing User with no Participant record yet (FR-006a) | 200, form view | — |
| `POST` | `/organiser/participants` | Register a User as a Participant (`user_id`) — creates the Participant with `status = ACTIVE` (FR-006b) | Redirect 303 → `/organiser/participants/{id}` | 200 form re-rendered with error if the User already has a Participant record (FR-006a), or if `user_id` is unknown |
| `GET` | `/organiser/participants/{id}` | View one Participant: status, Skill selections, Custom Field values, incomplete indicator (FR-027) | 200, detail view | 404 if `id` unknown |
| `POST` | `/organiser/participants/{id}/status` | Change status (`status` ∈ `ACTIVE`\|`NOT_PARTICIPATED`\|`REVOKED`, FR-007) | Redirect 303 → `/organiser/participants/{id}`, list reflects the new status (Acceptance Scenario 2) | 404 if `id` unknown |
| `POST` | `/organiser/participants/{id}/skills` | Replace the Participant's Skill selection set (`skill_ids[]`, drawn from the FR-008 catalog) | Redirect 303 → `/organiser/participants/{id}` | 404 if `id` unknown or any `skill_id` unknown |
| `POST` | `/organiser/participants/{id}/custom-fields/{fieldId}` | Set/update one Custom Field value — `value` (free text) or `option_ids[]` (multi-select), matching the field's `field_type` (FR-013, FR-014) | Redirect 303 → `/organiser/participants/{id}` | 200 detail/form re-rendered with a validation error if the submitted shape doesn't match the field's type, or an `option_ids` entry isn't one of the field's defined options (FR-014); 404 if `id`/`fieldId` unknown |

## Behavioral notes

- A Participant record is never deleted by this feature — status transitions (including `REVOKED`) preserve
  all Skill selections, Custom Field values, and Group membership history (edge case, spec).
- The "incomplete" indicator (FR-027) is computed at read time per [../data-model.md](../data-model.md) —
  never a stored flag that could drift when a required Custom Field is added/removed later.
