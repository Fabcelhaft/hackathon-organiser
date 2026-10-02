# Quickstart & Validation: Cleaner Topic List UI

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Date**: 2026-10-01

How to run the feature and prove it satisfies the spec. Implementation belongs in `tasks.md`; this
is the run-and-verify guide.

## Prerequisites

Dev container (Java 25, Maven, Docker CLI) per the repository `README.md`.

```bash
docker compose up db -d
```

## Run the app

```bash
SPRING_R2DBC_URL="r2dbc:postgresql://$(ip route | awk '/default/ {print $3}'):5432/hackathon" \
SPRING_R2DBC_USERNAME=hackathon \
SPRING_R2DBC_PASSWORD=hackathon \
mvn spring-boot:run
```

For a realistic list to look at — several Topics, varied participant counts, some upvotes — load
the demo seed (hand-authored, idempotent, safe to re-run):

```bash
psql "postgresql://hackathon:hackathon@localhost:5432/hackathon" -f docs/demo-data.sql
```

Then sign in at <http://localhost:8080/oauth2/authorization/oidc> as `organiser@example.dev` /
`password`, and open the Dashboard (`/`) and the Topics overview (`/topics/overview`).

## Automated validation

```bash
# Everything
mvn verify

# Unit layer only (fast; covers the read-model change)
mvn test -Dtest=TopicDiscoveryServiceTest

# Integration + accessibility only, bypassing a red unit stage
mvn verify -Dtest=NoSuchUnitTest \
  -Dit.test='HomeControllerIT,TopicOverviewManagementIT,TopicUpvoteManagementIT,HomepageAccessibilityIT,TopicOverviewAccessibilityIT'
```

The `-Dtest=NoSuchUnitTest` trick is how this repo runs integration tests while the unit stage is
failing — without it, Surefire's failure stops the build before Failsafe runs.

## Scenario validation

Each scenario names the spec requirement it proves. Manual steps are given where an automated
assertion cannot reach (horizontal scroll, screen-reader output).

### 1. Rows are one line and the name is the link — US1, FR-001, FR-002, FR-013

1. Open `/topics/overview` at a window width of 1024px or more.
2. **Expect**: every Topic occupies a single row of normal height. No "View" or "View Details"
   control appears anywhere.
3. Click a Topic's name → lands on `/topics/{id}`.
4. Tab to a Topic name → a visible focus ring; Enter opens the detail page.
5. Narrow the window below 1024px → the table scrolls sideways. **It must never stack a control
   onto its own line** (FR-012).

### 2. Voting without a reload — US2, FR-006, FR-006a, SC-009

1. On `/topics/overview`, scroll to the **last** Topic in a long list.
2. Activate its vote control.
3. **Expect**: the count increments immediately, the control switches to its voted state, the page
   does not reload, and the scroll position does not move. This last point is the whole of SC-009 —
   before this feature, voting here returned you to the top of the page.
4. Activate again → the count decrements and the control returns to unvoted.

### 3. It still works with scripting off — FR-006b, SC-010

1. Disable JavaScript in the browser and reload `/topics/overview`.
2. Vote.
3. **Expect**: the page reloads (as it does today) and the vote is recorded. Navigating to a Topic
   and joining one must also still work.

This scenario is the one most likely to rot silently, because nothing in normal use exercises it.

### 4. A failed vote explains itself — FR-006c, FR-006c1, FR-006c2, SC-009a

1. With the app running, stop the database (`docker compose stop db`) — or use browser devtools to
   block the request.
2. Vote on a row far down the list.
3. **Expect**: the count snaps back to its previous value, and a brief notice appears **next to
   that control** saying the vote did not go through.
4. **Expect**: no row changed height and the table did not reflow while the notice was visible
   (FR-006c2). Compare row positions before and after.
5. **Expect**: the notice does not cover the control, so the vote can be retried immediately.

### 5. Screen-reader confirmation — FR-008a, FR-008b, FR-008d, SC-006a

With a screen reader running:

1. Tab to a vote control. **Expect**: announced with the Topic name, its pressed state, and the
   action it will perform.
2. Activate it. **Expect**: the state change is announced, then the new count together with the
   Topic it belongs to — without interrupting what was being read, and without focus moving.
3. Repeat scenario 4 with the screen reader on. **Expect**: the failure is announced, and at no
   point is success announced for a vote that did not land (FR-008d).

### 5a. Joining returns you where you were — US2a, FR-011a, FR-011b, SC-005a

1. Sign in as an Active participant who is **not** yet in any Group.
2. Open `/topics/overview` and join a Topic from a row partway down the list.
3. **Expect**: the page re-renders as `/topics/overview` — not the Dashboard — with the "You
   joined …" confirmation shown. Before this feature, this step landed on `/`.
4. Repeat from the Dashboard. **Expect**: returns to the Dashboard.
5. Tamper check: submit a join with `redirect=https://example.com/`. **Expect**: the value is
   rejected and a safe in-app page is shown instead.
6. **Expect**: Join sits on one line beside the vote control and does not visually dominate it.

Joining re-rendering the page is correct and intended here (FR-011d) — unlike voting, it changes
your status, the pinned section and every other row's joinability.

### 6. No skills on the Dashboard, skills intact elsewhere — US3, FR-015, FR-016, SC-011

1. Sign in as a participant whose skills match at least one listed Topic.
2. `/` → **expect** no skills column in either Dashboard table, and more width given to the Topic
   name.
3. `/topics/overview` → **expect** the "Needed Skills" column still present and populated.
4. `/topics/{id}` → **expect** unchanged.

### 7. Scope boundary held — FR-021, FR-021a, SC-008

```bash
git diff --stat main -- src/main/resources/templates/organiser/ src/main/resources/templates/topics/detail.html
```

**Expect**: empty. Any output here is a scope violation. Spot-check one organiser list page
(`/organiser/participants`) in the browser and confirm its row buttons look exactly as before —
this is what catches an accidental edit to the shared `.actions` rule
([research.md](./research.md) §7).

### 8. Accessibility gate — SC-006

```bash
mvn verify -Dtest=NoSuchUnitTest -Dit.test='HomepageAccessibilityIT,TopicOverviewAccessibilityIT'
```

**Expect**: zero critical or serious WCAG 2.1 AA violations, which is the bar these existing scans
already enforce for these two screens.

## Traps worth knowing before you debug

Full detail in [research.md](./research.md) §8. The four that will cost the most time:

1. **The enhancement silently never runs.** A `<script>` placed inside `<th:block>` is discarded by
   Thymeleaf — the page renders perfectly and the JS simply is not there. Page scripts go *after*
   the closing `</th:block>`, as `home/index.html` already does for its revoke dialog.
2. **A Playwright locator matches several rows.** `getByLabel` is substring matching and every
   row's control starts with `"Upvote "`. Use `setExact(true)` or scope to a row.
3. **An integration test 500s on the principal.** Both screens read
   `@AuthenticationPrincipal HackathonOidcUser`; `mockOidcLogin()` alone is not enough — bind a
   real persisted `User`.
4. **Focus vanishes after voting.** That means the button element was replaced rather than updated
   in place. Copy the four contract values onto the existing nodes instead
   ([contracts/vote-control-fragment.md](./contracts/vote-control-fragment.md)).
