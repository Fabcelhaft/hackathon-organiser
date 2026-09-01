# Contract: Topic Details View (Story 9, Story 11 — FR-030–FR-032, FR-014a, FR-017, FR-036–FR-037e)

## GET /topics/{id} — new route on the existing `TopicSelfServiceController`

**New** (`topics.TopicSelfServiceController`, self-service package — sibling of that controller's existing
`GET /topics/{id}/edit` and `POST /topics/{id}`; not a new controller, research.md §13). Reachable from every
row on both the Home Page (`home-and-topic-overview.md`'s "View Details" link, FR-004a) and the Topic Overview
(FR-006b), and directly by URL.

- **200** — for any Topic visible to the caller, via `TopicDiscoveryService.findTopicDetail(id, viewerUserId,
  viewerIsOrganiser)` (data-model.md), reusing the exact `TopicService.isVisibleTo` rule `GET
  /topics/{id}/edit` and `GET /topics/overview` already apply (a Pending Topic is visible only to its author or
  an Organiser). Renders the Topic's Name as a heading, then two tables:
  - A left **"Topic Info" table** (one key/value row each) — Description, needed Skills, current participant
    count, and Compliance status:
    - Description (FR-030) — the first place it is shown outside the author-only edit form.
    - Needed Skills after `skillDisplayMode` (FR-017/FR-018) — **not** intersected with the viewer, matching
      the Topic Overview's FR-006 convention (this is a page about the Topic, not a personal match list).
    - Current participant count (`0` if no Group yet).
    - Compliance status — `Compliant` / `Not Compliant` / `Compliant (Organiser Override)` (text/icon, not
      color alone, FR-025), or a **blank** row value when the Topic has no Group yet (FR-014a; same convention
      as the Topic Overview, research.md §12).
  - A **"Joined Participants" table** (FR-031), one row per currently joined Participant, columns: display
    name, only the Custom Field values marked `public`, and — only when
    `OrganiserSettings.skillVisibilityEnabled` is currently `true` — Skills, exactly as
    `ParticipantService.findDetailForViewer` already resolves them for the Participants Directory's own detail
    page (research.md §10, reused verbatim, once per member). When no one has joined (no Group yet, or the last
    member just left), the table is replaced by a "no one has joined yet" message, not rendered with zero rows
    or omitted.
  - If the caller currently belongs to this Topic's Group (`TopicDetailView.isMember`, data-model.md — set from
    `GroupService.findActiveGroupForParticipant`): a "Leave" action (see the new POST route below, Story 11).
    Shown to nobody else.
  - If the caller is the Topic's author (`topic.createdByUserId == viewerUserId`): an additional link to the
    existing `GET /topics/{id}/edit` form (Story 9 Acceptance Scenario 5). No other user sees this link.
- **404** — unknown Topic id, or a Pending Topic the caller may not see (mirrors `GET /topics/{id}/edit`'s
  existing 404 for the same case — never a 403 that would leak the Topic's existence).
- Visibility here is governed **only** by the Topic's own visibility rule above — **not** by the separate
  Participants-Directory-audience setting (`OrganiserSettings.participantsDirectoryAudience`,
  `ParticipantsDirectoryAccessPolicy`). A user excluded from that audience (and thus unable to open
  `/participants`) can still open a Topic Details view for a Topic they can otherwise see, and still see who
  has joined it (FR-032, Story 9 Acceptance Scenario 6; spec Assumptions). The Directory's own `/participants`
  and `/participants/{id}` routes are unaffected by this feature.
- Never renders the word "Group," a Group id, or a Group-scoped URL (FR-036) — the page speaks only of the
  Topic, its participant count, and its Compliance status, mirroring the Home Page and Topic Overview.

## POST /topics/{id}/join — no change

Joining happens via the existing `POST /topics/{id}/join` (`join-action.md`) — this page does not duplicate
that action; a "Join" control on this page, if shown, posts to the same existing route under the same
eligibility rules as every other row that shows one.

## POST /topics/{id}/leave — new route (Story 11, FR-037–FR-037e)

**New** (`topics.TopicJoinController`, sibling `@PostMapping` to `join`). Single-click, no confirmation step
(FR-037a), shown only on this page, next to the "Joined Participants" table, and only to a viewer who currently
belongs to this Topic's Group. Delegates to `TopicJoinService.leave(topicId, requesterUserId)` (mirrors
`join`'s eligibility-gate-then-`GroupService`-core shape, research.md §14), which calls the new
`GroupService.leave(topicId, participantId)` core — itself composed entirely from the **already-existing**
`GroupService.removeMember(groupId, participantId)` and `GroupService.disband(groupId)` methods (no new SQL,
data-model.md "Group").

**Eligibility gate (`TopicJoinService.leave`):**

1. Requester has a Participant record whose current active Group's `topicId` matches the one being left
   (FR-037b) — resolved via the already-existing `GroupService.findActiveGroupForParticipant`. No record, no
   active Group, or an active Group for a *different* Topic → rejected server-side regardless of what the
   client rendered, mirroring the Join route's own not-trusting-the-client-state posture.
2. Unlike Join, this gate does **not** re-check `OrganiserSettings.topicJoiningEnabled` and does **not** require
   `ParticipantStatus == ACTIVE` (FR-037e) — that setting and status check gate only new joins; a non-`ACTIVE`
   Participant already has no active Group to leave, since self-revocation already removes it as a side effect.

**Core (`GroupService.leave`, research.md §14):**

- Acquires the *same* per-Topic `pg_advisory_xact_lock` `join` already acquires, inside the same
  `TransactionalOperator` transaction — not a second lock — so a concurrent join or leave for the same Topic
  is always serialized against this one.
- **303 → `/topics/{id}?flash=You+left+<Topic+name>.`** (redirects back to this Topic's own Details page — not
  Home like Join's redirect, since this page is Leave's only entry point) — `removeMember` flips the
  requester's membership row to `active = false`. If `activeMemberCount` is now `0`, `disband` is called in the
  same transaction (FR-037c): the Topic reverts to having no Group, becomes eligible for a fresh join (`POST
  /topics/{id}/join`), and no longer counts toward the Home Page's fullness table until it has members again —
  the exact same disbandment behavior already defined for Organiser-initiated disbandment, reused verbatim.
- After leaving, the requester no longer belongs to any active Group and may immediately join a different Topic
  (FR-037d, FR-010's one-active-Group-per-Participant rule no longer applies to them).
- Concurrent leaves by different members of the same Group (including a race where one is the last member) each
  succeed independently — the shared advisory lock serializes their remove-then-recount-then-maybe-disband
  sequences, so disbandment, when triggered, applies exactly once (Edge Cases, research.md §14).
- A Participant leaving their own authored Topic's Group is treated identically to any other member — no
  special-case branch (Edge Cases); the Topic's authorship record is unaffected.

## Success/error announcement (FR-037a, FR-024)

The redirect-carried flash message uses the same `?flash=` convention as Join, rendered inside the existing
`#flash-message` live region on the next page, so both a successful and a rejected Leave are announced to
assistive technology without relying on a full page reload alone.
