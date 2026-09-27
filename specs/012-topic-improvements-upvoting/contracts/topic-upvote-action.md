# Contract: Upvote / Withdraw an Upvote on a Topic (Story 1 — FR-001–FR-008, FR-005a)

## POST /topics/{id}/upvote — new route

**New** (`topics.TopicUpvoteController`, self-service package — sibling to
`TopicJoinController`). Single-click, no confirmation step, mirroring `join`/`leave`'s existing
immediacy convention. Rendered as an "Upvote" control on every row wherever a Topic is listed —
Home Page, Topic Overview, and Topic Details — with identical behavior regardless of which page
the form was submitted from; none of those pages' controllers gain their own upvote logic, exactly
as `TopicJoinController` already centralizes Join/Leave for all three.

Delegates to `TopicUpvoteService.upvote(topicId, requesterUserId)` (data-model.md). Both routes are
gated by `OrganiserSettings.topicUpvotingEnabled`, re-read fresh (never cached), matching
`topicJoiningEnabled`'s existing freshness rule:

1. `topicUpvotingEnabled == false` → **404** (the control is not rendered anywhere while the
   toggle is off, so any request here is either a stale page or unauthorized probing — the same
   "control that isn't rendered isn't a discoverable 200/400 either" posture this codebase already
   applies to other toggle-gated actions).
2. Unknown Topic id, or a Topic not visible to the requester (reusing `TopicService.isVisibleTo`
   verbatim, the same Pending-visibility rule Join/the Topic Overview already use) → **404**.
3. Otherwise: `TopicUpvoteService.upvote(...)` inserts `(topic_id, user_id)`; a duplicate cast
   (the requester already has an active upvote) is caught as a no-op success, not an error
   (idempotent, research.md §3).

**Response**: 303 → back to whichever page the form was submitted from (Home Page, Topic Overview,
or Topic Details — the same referrer-preserving redirect-with-flash shape `leave` already uses for
Topic Details, extended to also cover Home Page and Topic Overview). No flash message is required
for a successful upvote (unlike Join/Leave, this is a low-stakes, instantly-visible count change,
not an action whose success needs narrating) — the updated count on the reloaded page is
sufficient confirmation (SC-001).

## POST /topics/{id}/unupvote — new route, same controller

The reverse action (FR-002). Same gating (toggle-off → 404; unknown/invisible Topic → 404).
Delegates to `TopicUpvoteService.withdraw(topicId, requesterUserId)`, a no-op if the requester had
no active upvote. Same redirect-back-to-referrer response as `upvote`.

## Rendering contract (all three read models)

Every row produced by `TopicDiscoveryService.findOpenTopicsForHomePage` /
`findTopicOverview` / `findTopicDetail` carries `upvoteCount` (int) and `viewerHasUpvoted`
(boolean, FR-004). Templates:

- Render only the count, never a list or count of individual upvoters (FR-003) — no template
  anywhere may join `topic_upvotes.user_id` out to a display name or count-of-distinct-upvoters
  breakdown.
- Render the "Upvote" control (a button posting to `/topics/{id}/upvote`) when
  `!viewerHasUpvoted`, and an "Withdraw upvote" control (posting to `/topics/{id}/unupvote`) when
  `viewerHasUpvoted` — a single toggling control per row, not two simultaneously visible buttons.
- Render neither control, and no count, anywhere when `topicUpvotingEnabled` is `false` (FR-007) —
  checked directly from the passed-through `OrganiserSettings`, the same way
  `topic_joining_enabled` already gates the "Join" control's visibility.
- Self-upvoting a Topic the viewer authored is rendered identically to any other Topic (Story 1
  Edge Cases; spec Clarifications) — no special-case branch.

## Admin toggle (FR-006)

`organiser/settings/form.html` gains a `topic_upvoting_enabled` checkbox, identical markup pattern
to the existing `topic_joining_enabled` one (hidden `false` input + checkbox `true`, `th:checked`
bound to `settings.topicUpvotingEnabled`). `OrganiserSettingsController` passes it through the
existing `checkboxValue(form, "topic_upvoting_enabled")` helper into
`OrganiserSettingsService.update(...)`'s new parameter. Flipping it takes effect on the very next
render of any page (SC-004) — no cache to invalidate.
