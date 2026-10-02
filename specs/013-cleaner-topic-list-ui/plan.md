# Implementation Plan: Cleaner Topic List UI

**Branch**: `013-cleaner-topic-list-ui` | **Date**: 2026-10-01 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/013-cleaner-topic-list-ui/spec.md`

## Summary

Two participant-facing Topic lists — the Dashboard card and the Topics overview — end each row in
a cluster of full-size buttons that cannot fit side by side, so they stack and every row grows to
three or four lines. This feature removes the per-row "View" button (the Topic name becomes the
link), collapses the upvote count and action into one compact control, drops the Dashboard's
"Your Skills" column along with the work that computes it, and quiets the card's action pair.

The upvote control additionally becomes asynchronous: it updates optimistically on click, is
reconciled against a server-rendered fragment, announces itself to assistive technology, and shows
a row-anchored notice if the vote did not land — while remaining a plain form submission when
scripting is unavailable.

**Technical approach**: no new dependencies and no client framework. The existing
`POST /topics/{id}/upvote` and `/unupvote` routes learn to return a Thymeleaf-rendered fragment of
the vote control when the caller asks for one, and continue to return today's 303 redirect
otherwise. A small vanilla-JS progressive enhancement (following the existing `country-select.js`
conventions) intercepts the form submit, applies the optimistic change, and copies the
authoritative count and state out of the returned fragment into the live DOM — never replacing the
button element, so keyboard focus survives.

## Technical Context

**Language/Version**: Java 25

**Primary Dependencies**: Spring Boot 4.1.1 — WebFlux, Thymeleaf, Data R2DBC, OAuth2 Client,
Actuator. Pico CSS (vendored at `static/css/pico.min.css`). No new dependency is added.

**Storage**: PostgreSQL via R2DBC. No schema change — `topic_upvotes` already carries the composite
primary key `(topic_id, user_id)` this feature relies on for idempotency.

**Testing**: JUnit 5 + Mockito (unit), `WebTestClient` + Testcontainers (integration), Playwright +
`axe-core` (accessibility and smoke walkthroughs, under `src/test/java/.../a11y/`).

**Target Platform**: Server-rendered web application, modern evergreen browsers; must remain fully
functional with scripting disabled.

**Project Type**: Single-module Maven Spring Boot web application.

**Performance Goals**: Serving the Dashboard drops three per-row database round trips
(`displayedNeededSkillIds` → `viewerOfferedSkillIds` → `loadSkills`); it must be no slower than
today (SC-011). An upvote must feel instant, which the optimistic update guarantees independently
of server latency (FR-006a).

**Constraints**: Single-line Topic rows at ≥1024px (FR-013); horizontal scroll rather than stacking
at any narrower width (FR-012); zero critical/serious WCAG 2.1 AA violations (SC-006); every action
works without scripting (SC-010); organiser space and the Topic detail page byte-unchanged
(FR-021, FR-021a).

**Scale/Scope**: 2 templates rewritten, 1 new fragment, 1 new JS file, CSS additions, 2 controllers
touched and 1 service method simplified, ~5 test classes updated. Topic lists are tens of rows, not
thousands — no pagination or virtualisation concerns.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Principle | Verdict | Evidence |
|---|---|---|---|
| I | Spring Boot Native Only | **PASS** | No new dependency. The fragment route is an ordinary `@Controller` method returning `Mono<Rendering>`. |
| II | Reactive-First (WebFlux) | **PASS** | `TopicUpvoteController` already returns `Mono<Rendering>`; the fragment branch reuses the same reactive chain. No blocking call is introduced. |
| III | Thymeleaf Server-Side Rendering | **PASS, with a noted tension** | See Complexity Tracking. The authoritative HTML is still rendered by Thymeleaf server-side; the enhancement copies values from it and never renders content client-side. No client-side rendering framework. |
| IV | Pico CSS Styling | **PASS** | All additions are overrides in `app.css` for gaps Pico does not cover (an overlaying row-anchored notice, a compact toggle chip, non-wrapping action cells) — the same latitude the file already exercises. |
| V | Test-First Development | **PASS** | `/speckit-tasks` will order every behavioural change test-first. Existing `HomeControllerIT`, `TopicOverviewManagementIT`, `TopicUpvoteManagementIT`, `TopicDiscoveryServiceTest` and the two `a11y` ITs are updated before the templates change. |

**Post-Phase-1 re-check**: unchanged — the Phase 1 design introduced no new dependency, no blocking
call, no client-rendered content and no second CSS framework. See [research.md](./research.md) §1
for why fragment-return was chosen over a JSON endpoint specifically to keep Principle III intact.

## Project Structure

### Documentation (this feature)

```text
specs/013-cleaner-topic-list-ui/
├── plan.md              # This file
├── research.md          # Phase 0 output — design decisions and codebase pitfalls
├── data-model.md        # Phase 1 output — read-model changes (no schema change)
├── quickstart.md        # Phase 1 output — how to run and validate
├── contracts/
│   ├── vote-control-fragment.md   # The fragment contract the enhancement consumes
│   └── topic-row-ui.md            # The rendered-row UI contract for both screens
├── checklists/
│   └── requirements.md  # Spec quality checklist (already complete)
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

```text
src/main/java/net/fabcelhaft/hackathonorganiser/
├── home/
│   └── HomeController.java                 # drop the participant-id argument (FR-015a)
├── topic/
│   └── TopicDiscoveryService.java          # OpenTopicRow loses viewerOfferedSkills;
│                                           # viewerOfferedSkillIds() deleted outright
└── topics/
    ├── TopicUpvoteController.java          # fragment branch alongside today's redirect
    └── TopicJoinController.java            # allow-listed return-to-screen redirect (FR-011a/b)

src/main/resources/
├── templates/
│   ├── fragments/
│   │   └── topic-vote.html                 # NEW — the single source of the vote control
│   ├── home/index.html                     # name-as-link, no skills column, quieter card actions
│   └── topics/overview.html                # name-as-link, scroll wrapper, shared vote fragment
└── static/
    ├── css/app.css                         # chip, overlay notice, non-wrapping action cells
    └── js/topic-vote.js                    # NEW — the progressive enhancement

src/test/java/net/fabcelhaft/hackathonorganiser/
├── a11y/
│   ├── HomepageAccessibilityIT.java        # announcement + toggle-state coverage
│   └── TopicOverviewAccessibilityIT.java   # idem
├── home/HomeControllerIT.java              # skills-column tests deleted; link test renamed
├── topic/TopicDiscoveryServiceTest.java    # two viewer-skill tests deleted; signature updated
└── topics/
    ├── TopicOverviewManagementIT.java      # row shape assertions
    └── TopicUpvoteManagementIT.java        # fragment contract + redirect fallback
```

**Structure Decision**: Single-module Maven layout, unchanged. The feature touches the existing
`home`, `topic` and `topics` packages and the `templates`/`static` resource trees; no new module,
package or source root is introduced. The one new Java-side element is a branch inside the existing
`TopicUpvoteController`, deliberately placed there rather than in a new controller so the gate
logic (feature toggle, Topic visibility) stays in exactly one place.

## Delivery phasing

The spec's user stories are independently shippable, and the clarification sessions made User
Story 2 substantially larger than the rest. The recommended order lets the original complaint ship
before the asynchronous work lands:

| Stage | Stories | Delivers | Risk |
|---|---|---|---|
| 1 | US1, US3, US4 | Name-as-link, no View button, no Dashboard skills column, quieter card actions, single-line rows | Low — templates, CSS and one service simplification |
| 1b | US2a | Join returns to the screen it was clicked from; Join quieted to match | Low — mirrors an existing, proven pattern |
| 2 | US2 (sync part) | The compact vote control, still a plain form submit | Low — presentation only |
| 3 | US2 (async part) | Optimistic update, fragment reconcile, announcements, failure notice | Moderate — the only new behaviour in the feature |

Stage 3 is the only stage that can leave the app in a worse place than it started, and FR-006b's
no-scripting fallback is precisely the safety net: if the enhancement is removed, the feature
degrades to Stage 2 rather than breaking.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Client-side scripting on a server-rendered page (Principle III tension) | FR-006a requires the count to update without a page reload, which no purely server-rendered flow can do. The requester chose this explicitly over a reload (Clarifications, session 1 Q3). | A full reload per vote was offered and declined. The principle forbids *client-side rendering frameworks*; this adds ~100 lines of plain JS that renders nothing — the server still produces every byte of HTML via Thymeleaf. The codebase already ships `country-select.js` and an inline focus script under the same reading. |
| A JSON endpoint was **not** used | — | Returning JSON would have made the client assemble markup, which is the thing Principle III actually prohibits. Returning a Thymeleaf fragment keeps rendering on the server; see [research.md](./research.md) §1. |
| A new row-anchored overlay notice pattern | FR-006c1/c2 require a failure explanation beside the control that does not alter row height. | The existing page-level `.flash-message` banner is server-rendered on redirect and sits at the top of the page — on a long list it would explain a row-20 failure entirely off-screen, which the requester rejected (Clarifications, session 2 Q3). |
