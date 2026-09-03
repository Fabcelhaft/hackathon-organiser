# Contract: On-Demand Audit Retrieval (Story 2 — FR-005–FR-008, FR-011, FR-011a)

## GET /organiser/topics/{id}/audit — new route

**New** (`organiser.topic.TopicController`, sibling to the existing `GET /organiser/topics/{id}` detail route).
Renders a full server-rendered page (not a fragment injected into the detail page — research.md §8) listing
every `AuditEntry` for that Topic, via `AuditService.findForTopic(id)`, ordered most-recent-first (FR-011),
complete and unbounded — no pagination controls (resolved clarification). Each row shows: timestamp, event
type, the acting user's display name, their capacity for that action (Organiser/Standard User), and, only when
present, the old → new value pair (FR-002a). Unknown `id` → **404**, matching every other `{id}`-scoped
organiser route in this codebase. No recorded entries yet → the page renders with an empty, clearly-labeled
history (e.g., "No changes recorded yet"), not an error (Edge Cases).

## GET /organiser/participants/{id}/audit — new route

**New** (`organiser.participant.ParticipantController`). Same contract as above, scoped to
`AuditService.findForParticipant(id)`.

## No GET /organiser/groups/{id}/audit — Groups have no audit trail of their own

`GroupController` gains **no new route**. A Group's detail page still has an "Audit" action (Story 2,
Acceptance Scenario 3), but its `th:href` points directly at `/organiser/topics/{topicId}/audit` for that
Group's own `topicId` (`GroupDetail.group().getTopicId()`, already on the detail page's existing model) —
i.e., it is the exact same link the Group's Topic's own detail page would render, not a Group-scoped
equivalent (research.md §9; SC-006).

## Access control (FR-005, FR-006)

Both routes above live under `/organiser/**`, already restricted to `ROLE_ORGANISER` by `SecurityConfig`
(research.md §7) — a non-Organiser (or unauthenticated) request to either is rejected by the security filter
chain itself, before the controller method runs, regardless of how the request was constructed (FR-006:
enforcement does not depend on the "Audit" link/button being hidden). This matches every existing
`/organiser/**` route's contract in this codebase; no new test pattern is introduced beyond what
`WebTestClient`-based tests already exercise for e.g. `GroupController`. The Group detail page needs no
additional access-control test of its own beyond confirming its link's `href` — the request it points at is
covered by the Topic route's own test.

## Entry point on each existing detail page (FR-007, FR-008)

`organiser/topics/detail.html` and `organiser/participants/detail.html` each gain one new link/button —
`th:href="@{/organiser/{type}/{id}/audit(id=...)}"`, labelled "Audit" — alongside their other existing
per-record actions (Edit, Approve, etc.). `organiser/groups/detail.html` gains the same labelled "Audit"
link, but pointed at its Topic's route instead, per the section above. No audit data is added to the model any
of these three existing `GET` handlers already build (Story 2, Acceptance Scenario 1) — the link only
navigates; it does not pre-fetch anything.

## Side effect on every mutating route already documented in 002–005's contracts

Every existing mutating route this feature's `data-model.md` lists (Topic propose/edit/approve; Group
create/join/leave/add-member/remove-member/disband/compliance-override; Participant
register/status/skills/custom-fields/self-revoke/delete) gains exactly one new, invisible side effect: an
`AuditEntry` row is written in the same transaction as the mutation itself (research.md §1). None of those
routes' existing request/response contracts — status codes, redirect targets, flash messages, rejection
reasons — change in any way. This is intentionally not re-documented per-route here; it is a uniform addition
applied identically everywhere, tracked in `data-model.md`'s "Modified Entities" tables instead of duplicated
across six existing contract files.
