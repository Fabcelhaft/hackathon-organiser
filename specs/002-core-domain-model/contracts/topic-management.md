# Contract: Topic Management (Story 4)

Covers FR-010, FR-015, FR-021. Server-rendered Thymeleaf views. All routes require `ROLE_ORGANISER` (FR-022).

| Method | Path | Purpose | Success | Failure |
|---|---|---|---|---|
| `GET` | `/organiser/topics` | List all Topics: name and whether an active Group currently exists for it | 200, list view | — |
| `GET` | `/organiser/topics/new` | New-Topic form (`name`, `description`, `created_by_user_id`, `skill_ids[]`) | 200, form view | — |
| `POST` | `/organiser/topics` | Create a Topic (FR-015) | Redirect 303 → `/organiser/topics/{id}` | 200 form re-rendered with error if `name`/`description`/`created_by_user_id` missing, or `created_by_user_id` unknown |
| `GET` | `/organiser/topics/{id}` | View one Topic: name, description, associated Skills, creator, current Group status | 200, detail view | 404 if `id` unknown |
| `GET` | `/organiser/topics/{id}/edit` | Edit-Topic form | 200, form view | 404 if `id` unknown |
| `POST` | `/organiser/topics/{id}` | Update `name`, `description`, `skill_ids[]` (creator is not re-assignable through this route — FR-015 records it once, at creation) | Redirect 303 → `/organiser/topics/{id}` | 200 form re-rendered with validation error; 404 if `id` unknown |

## Behavioral notes

- The creator reference is retained even if that User's access is later revoked (edge case, spec) — the
  Topic detail view still renders the creator's stored `display_name`.
- Skill associations reuse the same catalog as Participants (FR-010); removing a Skill from the catalog while
  a Topic still references it is blocked at the Skill-deletion route, not here (see
  [catalog-management.md](catalog-management.md)).
