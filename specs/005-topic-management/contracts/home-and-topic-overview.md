# Contract: Home Page Topic Table & Topic Overview (Stories 2, 5, 9, 10 — FR-003–FR-006, FR-014, FR-014a, FR-017, FR-030–FR-036)

## GET / (home) — same path, replaced Topics section (FR-003–FR-004, FR-033, FR-035, FR-036)

**Modified** (`HomeController`). The Topics section of the existing two-column layout (`#topics-section`)
drops its current `ownPending`/`ownApproved`/`others` `<ul>` grouping (`TopicService.findVisibleTopicsFor`) in
favor of one table, `TopicDiscoveryService.findOpenTopicsForHomePage(viewerUserId, viewerParticipantIdOrNull,
10)` (data-model.md's "Modified read-model methods" — `viewerUserId` is a new leading parameter):

- **200** — at most 10 rows total. First, every Topic the viewer authored (`pinned = true` — FR-033),
  regardless of approval status or fullness, sorted fullest-first among themselves. Then, filling any remaining
  slots up to 10, the existing fullness-sorted list (Approved, member count strictly below
  `OrganiserSettings.maxGroupMembers` per FR-003b, or no Group yet treated as `0` per FR-003a), excluding any
  Topic already shown pinned. The viewer's own Topics are never pushed out to make room for others (FR-033);
  other Topics are pushed out of the visible 10 first if there is not enough room.
- Each row (pinned or not) shows Name, current participant count, and — after applying `skillDisplayMode`
  (FR-017) — the subset of the Topic's needed Skills the viewing user's own `participant_skills` include; an
  empty subset renders as an empty cell, not an omitted row (FR-004 Acceptance Scenario 5). A viewer with no
  Participant record sees every row with an empty Skills cell (never an error).
- Each row that is joinable for the current viewer (Story 3/4 eligibility — see `join-action.md`) shows a
  "Join" action; a full, Pending (FR-035), or already-a-member Topic, or Topic joining disabled instance-wide,
  shows none. Every row, joinable or not, additionally shows a "View Details" link to `GET /topics/{id}`
  (`topic-details.md`, FR-004a).
- "Propose Topic" link (unchanged from 003) remains, linking to `GET /topics/new`.
- Nothing on this page says "Group" to a non-Organiser viewer (FR-036) — the existing "Group topic: …" label in
  `#status-section` and the revoke-confirmation dialog's copy are reworded to Topic-centric language as part of
  this feature (e.g. "Your Topic:" / "removes you from your current Topic's team"); `assignedGroup`'s only
  remaining use is presence-checking (`!= null`), never rendering the word itself.

A viewer's own Pending Topic (visible only to its author, per the unchanged Pending-visibility rule) and own
full Topics — both previously excluded from this table entirely — now appear pinned (FR-033, Story 10). Every
other Pending Topic, and every other user's Topics beyond the fullness-sorted 10, still do not appear here —
the full roster, including those, is `GET /topics/overview` below (spec Assumptions).

## GET /topics/overview — new route (FR-005, FR-006, FR-006a, FR-006b, FR-014a, FR-034, FR-036)

**New** (`topics.TopicOverviewController`, self-service package — available to every authenticated user, no
Organiser check, no configurable audience unlike the Participants directory).

- **200** — every Topic visible to the caller (`TopicService`'s existing Pending-visibility rule, reused
  verbatim — a Pending Topic is included only for its author or an Organiser), via
  `TopicDiscoveryService.findTopicOverview(viewerUserId, viewerIsOrganiser)` (signature unchanged). The
  viewer's own Topics are pinned above all other rows (`pinned = true` — FR-034); since this route has no cap,
  pinning only reorders rows, never hides one. Each row shows Name, Author (display name), current participant
  count (`0` if no Group yet), needed Skills after `skillDisplayMode` (**not** intersected with the viewer —
  FR-006, unlike the Home Page's FR-004), and Compliance: one of `Compliant` / `Not Compliant` / `Compliant
  (Organiser Override)`, each conveyed by text/icon not color alone (FR-025) — or, when the Topic has no Group
  yet, a **blank cell** (FR-014a, superseding the earlier "No Group Yet" text/icon; `complianceStatus` is still
  `Optional.empty()` at the Java level, research.md §12 — only the template's rendering changed).
- Each row that is joinable for the current viewer shows the same self-service "Join" action as the Home Page
  (FR-006a; same eligibility rules, see `join-action.md`); every row additionally shows a "View Details" link
  to `GET /topics/{id}` (`topic-details.md`, FR-006b).
- Table is sortable/filterable only insofar as Pico CSS's plain `<table>` renders it; no client-side
  interactivity beyond what the layout already provides (Constitution III).
- Nothing on this page says "Group" to a non-Organiser viewer (FR-036); "Compliance" is presented as a property
  of the Topic, never attributed to a named "Group."

## Nav item (FR-005)

`fragments/layout.html` gains a static `<li><a th:href="@{/topics/overview}">Topic Overview</a></li>`,
unconditional for every authenticated user (`.anyExchange().authenticated()` already covers the route — no new
`SecurityConfig` entry, no access-policy class, unlike the configurable Participants directory).
