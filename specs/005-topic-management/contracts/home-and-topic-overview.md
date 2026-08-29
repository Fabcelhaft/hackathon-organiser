# Contract: Home Page Topic Table & Topic Overview (Stories 2, 5 — FR-003–FR-006, FR-014, FR-017)

## GET / (home) — same path, replaced Topics section (FR-003–FR-004)

**Modified** (`HomeController`). The Topics section of the existing two-column layout (`#topics-section`)
drops its current `ownPending`/`ownApproved`/`others` `<ul>` grouping (`TopicService.findVisibleTopicsFor`) in
favor of one table, `TopicDiscoveryService.findOpenTopicsForHomePage(viewerParticipantIdOrNull, 10)`:

- **200** — at most 10 rows, each an Approved Topic whose active Group's member count is strictly below
  `OrganiserSettings.maxGroupMembers` (FR-003b's "full" threshold, `>=`, excluded) or that has no Group yet
  (treated as count `0` — FR-003a), ordered by member count descending (fullest first, SC-002). Each row shows
  Name, current participant count, and — after applying `skillDisplayMode` (FR-017) — the subset of the
  Topic's needed Skills the viewing user's own `participant_skills` include; an empty subset renders as an
  empty cell, not an omitted row (FR-004 Acceptance Scenario 5). A viewer with no Participant record sees every
  row with an empty Skills cell (never an error).
- Each row that is joinable for the current viewer (Story 3/4 eligibility — see `join-action.md`) shows a
  "Join" action; a full or already-a-member Topic, or Topic joining disabled instance-wide, shows none.
- "Propose Topic" link (unchanged from 003) remains, linking to `GET /topics/new`.

Pending Topics (any author) and a viewer's own Topics beyond the 10-row cap no longer appear on the Home Page
at all — the full roster, including those, moved to `GET /topics/overview` below (spec Assumptions).

## GET /topics/overview — new route (FR-005, FR-006)

**New** (`topics.TopicOverviewController`, self-service package — available to every authenticated user, no
Organiser check, no configurable audience unlike the Participants directory).

- **200** — every Topic visible to the caller (`TopicService`'s existing Pending-visibility rule, reused
  verbatim — a Pending Topic is included only for its author or an Organiser), via
  `TopicDiscoveryService.findTopicOverview(viewerUserId, viewerIsOrganiser)`. Each row shows Name, Author
  (display name), current participant count (`0` if no Group yet), needed Skills after `skillDisplayMode`
  (**not** intersected with the viewer — FR-006, unlike the Home Page's FR-004), and Compliance status: one of
  `Compliant` / `Not Compliant` / `Compliant (Organiser Override)` / `No Group Yet` (FR-014), each conveyed by
  text/icon, not color alone (FR-025). No pagination or cap — every visible Topic, full or not, Pending or not.
- Table is sortable/filterable only insofar as Pico CSS's plain `<table>` renders it; no client-side
  interactivity beyond what the layout already provides (Constitution III).

## Nav item (FR-005)

`fragments/layout.html` gains a static `<li><a th:href="@{/topics/overview}">Topic Overview</a></li>`,
unconditional for every authenticated user (`.anyExchange().authenticated()` already covers the route — no new
`SecurityConfig` entry, no access-policy class, unlike the configurable Participants directory).
