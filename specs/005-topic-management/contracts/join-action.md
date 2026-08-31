# Contract: Join a Topic (Story 3, Story 4 enforcement — FR-007–FR-013a, FR-020a–d)

## POST /topics/{id}/join — new route

**New** (`topics.TopicJoinController`, self-service package). Single-click, no confirmation step (FR-007a). One
route, rendered as a "Join" action on every eligible row wherever a Topic is listed — the Home Page
(`home-and-topic-overview.md`) and, as of FR-006a, the Topic Overview too — with identical eligibility and
outcome regardless of which page the form was submitted from; neither page's controller has its own join logic.
Delegates to `TopicJoinService.join(topicId, requesterUserId)` (data-model.md), which gates eligibility before
calling the race-safe `GroupService.join(...)` core (research.md §2). The reverse action — a current member
voluntarily leaving — is `POST /topics/{id}/leave`, a sibling route on the same controller documented in
`topic-details.md` (Story 11, FR-037–FR-037e, research.md §14), not covered here.

**Eligibility gate, in order, each with a distinct rejection reason (FR-026):**

1. `OrganiserSettings.topicJoiningEnabled` — re-read fresh, never cached (FR-020c). `false` → rejected
   regardless of anything else (FR-020b), and the "Join" action itself is not rendered anywhere while this is
   `false` (FR-020b, Home Page contract).
2. Requester has a Participant record and its `status == ACTIVE` (FR-007b). No record, `NOT_PARTICIPATED`, or
   `REVOKED` → rejected; the "Join" action is never rendered for such a viewer either (Story 4 Acceptance
   Scenario 3).
3. Topic exists and `approvalStatus == APPROVED` (spec Assumptions: a Pending Topic has no Group and cannot be
   joined). Unknown/Pending Topic id → **404** (consistent with `findVisibleTo`'s existing 404-vs-403 pattern
   for Pending Topics elsewhere in this codebase).

**Race-safe core (`GroupService.join`, only reached once the gate above passes):**

- **303 → `/?flash=You+joined+<Topic+name>.`** — no Group existed yet for the Topic: one is created with the
  requester as its sole member (FR-008, Acceptance Scenario 1). A Group already existed and had capacity (or
  carried a compliance override, FR-015): the requester is added, participant count increases by one
  (FR-009, Acceptance Scenario 2).
- **200/303 with a field-associated rejection, not a bare error page** — the Group is at
  `maxGroupMembers` and carries no override: rejected, "This Topic is full" (FR-013, SC-005). Exactly one of
  two concurrent last-slot requests succeeds; the loser sees this same message (Edge Cases) — enforced by the
  advisory lock, not a best-effort check.
- Rejected — the requester already belongs to a different active Group: "You already belong to a Group"
  (FR-010, reusing `GroupService.addMember`'s existing guard/message shape).
- A Participant joining their own authored Topic is treated identically to any other eligible Participant — no
  special-case branch anywhere in this path (Edge Cases).

All server-side; a stale page (Topic-joining disabled, or capacity reached, after the page loaded but before
submission) is rejected exactly the same way a fresh page load would be (FR-020c, Edge Cases) — the response is
a redirect with an explanatory flash, not a silent no-op or a raw 500.

## Success/error announcement (FR-007a, FR-024)

The redirect-carried flash message (the same `?flash=` query-parameter convention 003/004 already use, no new
session-state mechanism) is rendered inside the existing `#flash-message` live region on the next page, so both
a successful join and a rejected one are announced to assistive technology without relying on a full page
reload alone.
