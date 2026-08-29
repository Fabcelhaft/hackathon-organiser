# Contract: Group Management (Story 5)

Covers FR-016, FR-016a, FR-016b, FR-017, FR-021. Server-rendered Thymeleaf views. All routes require
`ROLE_ORGANISER` (FR-022).

| Method | Path | Purpose | Success | Failure |
|---|---|---|---|---|
| `GET` | `/organiser/groups` | List all Groups (active and disbanded), each showing its Topic and status | 200, list view | — |
| `GET` | `/organiser/groups/new` | New-Group form — Topic picker restricted to Topics with no current active Group (FR-016a) | 200, form view | — |
| `POST` | `/organiser/groups` | Create a Group for a Topic (`topic_id`, optional initial `participant_ids[]`) | Redirect 303 → `/organiser/groups/{id}` | 200 form re-rendered with error "Topic already has a Group" if `topic_id` already has an active Group (FR-016a, Acceptance Scenario 4); 404 if `topic_id` unknown |
| `GET` | `/organiser/groups/{id}` | View one Group: its Topic and current + historical members (former members remain visible after a disband, FR-016b) | 200, detail view | 404 if `id` unknown |
| `POST` | `/organiser/groups/{id}/members` | Add a Participant to the Group (`participant_id`) | Redirect 303 → `/organiser/groups/{id}` | 200 re-rendered with error if the Group is `DISBANDED`, the Participant is unknown, or the Participant already belongs to a different active Group (FR-017); 404 if `id` unknown |
| `POST` | `/organiser/groups/{id}/members/{participantId}/remove` | Remove a Participant from an active Group | Redirect 303 → `/organiser/groups/{id}` | 404 if `id`/`participantId` unknown, or the membership isn't currently active |
| `POST` | `/organiser/groups/{id}/disband` | Disband the Group (FR-016b) | Redirect 303 → `/organiser/groups/{id}`, status now `DISBANDED`, all memberships flipped inactive, its Topic becomes eligible for a new Group | 404 if `id` unknown; no-op error if already `DISBANDED` |

## Behavioral notes

- Disbanding is one-way (see state transition in [../data-model.md](../data-model.md)): there is no
  "reactivate" route. A fresh `POST /organiser/groups` against the same `topic_id` after a disband creates a
  brand-new Group row (Acceptance Scenario 5, Story 5).
- "At most one active Group per Participant" (FR-017) is checked on the *add-member* route above; it is
  enforced structurally by a partial unique index (see [../research.md](../research.md) §4), so this check is
  a UX nicety, not the sole guarantee against race conditions.
