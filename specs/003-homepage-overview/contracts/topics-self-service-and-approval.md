# Contract: Topic Browse/Propose/Edit (Story 3) & Organiser Approval/Administration (Story 4)

Covers FR-009–FR-016. Extends, but does not replace, 002's
[topic-management.md](../../002-core-domain-model/contracts/topic-management.md) (organiser Topic CRUD stays
at `/organiser/topics/**`, unchanged in shape except where noted below). Participant-facing self-service routes
below sit outside `/organiser/**` and require only plain authentication; ordering/visibility rules apply to the
homepage's topic list specifically (`GET /`, contracts/registration-and-status.md), not to the pre-existing
`/organiser/topics` admin list.

| Method | Path | Purpose | Success | Failure |
|---|---|---|---|---|
| `GET` | `/` (topic-list portion) | Show topics visible to the current viewer, grouped/ordered per FR-009a: (1) Pending visible to viewer, (2) viewer's own Approved, (3) all other Approved — each group by creation date | 200, part of `home/index` view | — |
| `GET` | `/topics/new` | Propose-Topic form (`name`, `description`) — only reachable by a user with an Active Participant record | 200, `topics/form` view | Redirect/403 if the requester has no Participant record (Edge Cases: Standard users cannot propose) |
| `POST` | `/topics` | Create a Topic authored by the current Participant (FR-010); starts `PENDING` or `APPROVED` per the current `topic_approval_required` setting (FR-013) | Redirect 303 → `/` | 200 form re-rendered with error if `name`/`description` missing; 403 if requester has no Participant record |
| `GET` | `/topics/{id}/edit` | Edit form for a Topic the current user authored | 200, `topics/form` view | 404 if `id` unknown or the Topic is `PENDING` and the requester is neither its author nor an Organiser (FR-012a); 403 if the Topic exists, is visible, but was authored by someone else (FR-011) |
| `POST` | `/topics/{id}` | Update `name`/`description` of a Topic the current user authored (does not re-trigger approval — Assumptions) | Redirect 303 → `/` | Same 404/403 rules as the edit form; 200 form re-rendered with error if fields missing |
| `POST` | `/organiser/topics/{id}/approve` | Organiser approves a `PENDING` Topic (FR-014) | Redirect 303 → `/organiser/topics/{id}` | 404 if `id` unknown; no-op-with-redirect if already `APPROVED` |
| `POST` | `/organiser/topics/{id}` *(extends 002's existing route)* | Organiser edits any Topic's `name`/`description`/`skill_ids[]` **and now also** `created_by_user_id` (FR-015 supersedes 002's immutability — data-model.md) | Redirect 303 → `/organiser/topics/{id}` | 404 if the Topic `id` itself is unknown; 200 form re-rendered with a field-associated error (FR-037) if the new `created_by_user_id` is unknown — see Behavioral notes |

## Behavioral notes

- FR-012a (Pending visibility): a `PENDING` Topic is excluded from every read path except (a) its author's own
  homepage view and (b) any Organiser's view — enforced in `TopicService`'s read-model query, not just at the
  controller layer, so no route can accidentally leak a Pending Topic.
- FR-012b: wherever a Pending Topic is shown to an eligible viewer, it carries a "Pending approval" label that
  is not conveyed by color alone (FR-034) — rendered as visible text, e.g. a `<span>` badge with the word
  "Pending", not just a colored border.
- FR-016: disabling `topic_approval_required` is not retroactive — existing `PENDING` topics stay `PENDING`
  until explicitly approved; `TopicService` never bulk-updates `approval_status` when the setting changes.
- The organiser-facing `/organiser/topics` list and `/organiser/topics/{id}` detail views (002) already show
  every Topic regardless of status to an Organiser; this feature adds the Pending label and the approve action
  to those existing views rather than introducing a parallel organiser topic list.
- An unknown new `created_by_user_id` on `POST /organiser/topics/{id}` is a validation failure, not a
  routing failure (the `id` path segment itself is fine — only the *submitted form field* is bad): it follows
  the exact pattern 002's `TopicService.create()` already established for the identical "unknown user id"
  check — `reassignAuthor` raises a `TopicConflictException`, and `TopicController` catches it and re-renders
  the edit form with the error tied to the author field (200), the same way `create()`'s existing
  `onErrorResume(TopicConflictException.class, ...)` handler already works. A bare 404 here would give no
  field-associated text at all, failing FR-037.
