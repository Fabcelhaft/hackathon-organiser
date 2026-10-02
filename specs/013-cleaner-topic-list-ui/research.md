# Phase 0 Research: Cleaner Topic List UI

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Date**: 2026-10-01

No `NEEDS CLARIFICATION` markers entered this phase — two `/speckit-clarify` sessions resolved
seven open decisions before planning. This document records the design decisions those answers
still left open, and the codebase-specific traps that would otherwise be discovered during
implementation.

---

## §1 How the in-place update talks to the server

**Decision**: `POST /topics/{id}/upvote` and `POST /topics/{id}/unupvote` keep their current
behaviour by default (303 redirect to the allow-listed `redirect` form value) and gain a second
branch: when the request carries the header `X-Vote-Fragment: true`, they return
`200 OK` with the Thymeleaf-rendered vote-control fragment for that one Topic instead.

**Rationale**: Constitution Principle III requires dynamic content to be driven by Thymeleaf and
forbids client-side rendering. Returning a rendered fragment keeps every byte of HTML server-made;
the enhancement only reads values out of it. Returning JSON would have pushed markup assembly into
the browser — the precise thing the principle prohibits. Reusing the existing routes (rather than
adding new ones) keeps the gate logic — feature toggle, Topic visibility, 404 semantics — in one
place, which is where `TopicUpvoteController` already concentrates it.

**Alternatives considered**:

- *A JSON endpoint returning `{count, voted}`*. Smallest payload, but violates Principle III as
  read above, and would duplicate the control's state-to-markup mapping in two languages.
- *Fetching the whole page and diffing the row out of it*. No new endpoint at all, but transfers
  the entire document per vote and is fragile against unrelated markup changes.
- *Two new dedicated fragment routes*. Cleaner HTTP semantics, but duplicates the gate logic and
  doubles the surface that must stay in step with the redirect path.

---

## §2 Applying the server's answer without destroying focus

**Decision**: the enhancement **never replaces the button element**. It reads the returned fragment
into a detached `template`, then copies four things onto the live DOM: the count text, the
button's `aria-pressed`, the button's `aria-label`, and the form's `action`. The button node the
user is interacting with is never detached.

**Rationale**: FR-006a forbids moving keyboard focus. Replacing a focused element with
`innerHTML`/`replaceWith` destroys it and sends focus to `<body>` — a silent, easily-missed
regression that only keyboard and screen-reader users would feel. Copying attributes sidesteps the
problem entirely rather than fixing it up afterwards with a re-focus call that has its own timing
hazards.

**Alternatives considered**: swap the node and restore focus by id. Works, but depends on the id
surviving the swap and on the restore running before the browser paints; it is strictly more
machinery for strictly more risk.

---

## §3 One form per row, not two

**Decision**: each row renders **one** form whose `action` points at `/upvote` or `/unupvote`
depending on current state, with a single `<button aria-pressed="true|false">` inside it.

**Rationale**: today both templates render two mutually-exclusive forms via `th:if`. A toggle
cannot flip state in place if the element it must flip does not exist yet in the other branch.
One form with a swappable action is what makes §2's attribute-copying possible, and it is also the
correct accessible shape — `aria-pressed` is exactly "a button with an on/off state".

**Consequence for tests**: existing assertions such as `body.contains("Upvote")` and
`body.doesNotContain("Withdraw upvote")` (`HomeControllerIT`, `TopicOverviewManagementIT`) continue
to hold **only if the `aria-label` wording is preserved verbatim**. The visible label becomes an
icon plus a count, so `aria-label="Upvote <name>"` / `aria-label="Withdraw upvote for <name>"` is
now the sole carrier of those strings. Keep the wording; it is load-bearing for the existing suite.

---

## §4 Announcing to assistive technology

**Decision**: one page-level polite live region (`role="status" aria-live="polite"`, visually
hidden) per screen, written **only after the server confirms**. The optimistic visual change
(FR-006a) is deliberately *not* announced; the announcement waits for the real answer.

**Rationale**: this is how FR-006a (instant feedback) and FR-008d (never claim success before
confirmation) are satisfied at the same time — sighted users get the fast path, and nobody is told
a lie. One region rather than one per row, because multiple simultaneous live regions are
unreliably announced across screen readers.

**Precedent in this codebase**: `fragments/layout.html` already uses
`role="status" aria-live="polite"` for the flash banner, so this is the established vocabulary, not
a new one.

**Decision (failures)**: the row-anchored failure notice carries `role="alert"`, so making it
visible announces it automatically. No separate mechanism is needed for FR-008e, and an error is
the one case where interrupting is appropriate.

---

## §5 Making the notice overlay rather than reflow

**Decision**: the action cell gets `position: relative`; the notice is `position: absolute`,
rendered below the control and outside the table's layout flow.

**Rationale**: FR-006c2 forbids the notice changing row height or reflowing the table — the very
density problem this feature exists to fix. Absolute positioning is the only way a box can appear
without participating in layout. It is placed *below* rather than over the control so FR-006c3's
"must not obscure the control it describes" holds and the participant can retry immediately.

**Open risk**: an absolutely-positioned child of a `<td>` inside `overflow-x: auto` (the
`.table-scroll` wrapper) is clipped at the wrapper's edge. For the last row or a narrow viewport
the notice may be cut off. Mitigation is to anchor it to the left of the cell rather than the
right, and to verify in the Playwright smoke test rather than by inspection.

---

## §6 Removing the Dashboard's skill matching

**Decision**: delete `viewerOfferedSkills` from `OpenTopicRow`, delete the private
`viewerOfferedSkillIds(...)` helper entirely, and drop the now-unused `viewerParticipantIdOrNull`
parameter from `findOpenTopicsForHomePage(...)`.

**Evidence for FR-015b** (the spec requires confirming nothing else reads it — this is that
confirmation):

| Symbol | Read by | Verdict |
|---|---|---|
| `OpenTopicRow.viewerOfferedSkills` | `home/index.html` only | safe to delete |
| `viewerOfferedSkillIds(...)` | `findOpenTopicsForHomePage` only | safe to delete |
| `displayedNeededSkillIds(...)` | also overview + detail paths (4 other call sites) | **keep** |
| `loadSkills(List<UUID>)` | also overview + detail paths | **keep** |

**Consequence**: the per-row chain collapses from
`concatMap(displayedNeededSkillIds → viewerOfferedSkillIds → loadSkills → map)` to a plain `map`,
removing three database round trips per displayed row. This is the mechanism behind SC-011.

**Tests that must go** (they assert the removed behaviour and cannot be rescued):
`TopicDiscoveryServiceTest.findOpenTopicsForHomePageIntersectsNeededSkillsWithTheViewersOwnSkills`
and `...GivesAViewerWithNoParticipantRecordAnEmptySkillsListNeverAnError`; `HomeControllerIT`'s
skill-display-mode and skill-matching assertions. The equivalent coverage already exists for the
overview in `TopicOverviewManagementIT.skillDisplayModeChangesTheNeededSkillsColumnOnTheVeryNextView`,
so no coverage is actually lost — only duplicated coverage of a column that no longer exists.

---

## §7 Keeping rows to one line

**Decision**: introduce a **new** modifier class — `.actions-nowrap` applied alongside `.actions`
on the row action cells of these two screens only — rather than changing `.actions` itself.
`topics/overview.html`'s two tables also gain the `.table-scroll` wrapper the Dashboard tables
already have.

**Rationale**: `app.css` currently sets `.actions { flex-wrap: wrap }` and its own comment explains
the consequence — a shrink-to-fit cell makes the flex row wrap its buttons into a vertical stack.
That is the exact defect in the reported screenshots, and `nowrap` converts the overflow into
horizontal pressure that `.table-scroll` can absorb, which is the FR-012 contract (scroll, never
stack).

**Why a new class and not an edit to `.actions`**: `.actions` is shared with organiser-space
tables (participants, groups, tasks, skills, custom fields, event destinations, content pages).
Flipping it globally would change how every one of those renders, which FR-021 forbids and SC-008
explicitly checks. The modifier keeps the blast radius at exactly the two screens in scope. This
is the single most likely way to accidentally violate the scope boundary, so it is worth stating
plainly: **do not edit the shared `.actions` rule.**

**Note**: `overflow-x: auto` on the wrapper interacts with §5's absolute notice; see the open risk
there.

---

## §8 Codebase traps that will cost time if not known up front

These are established facts about this repository, not speculation. Each has bitten before.

1. **A `<script>` inside `<th:block>` is discarded.** Page JS must sit *after* the closing
   `</th:block>`, or it never reaches the rendered page. `home/index.html` already does this
   correctly with its revoke-dialog script — follow that placement exactly. Silent failure mode:
   the page renders fine and the enhancement simply never runs.

2. **Never pass a model attribute named `title`.** The layout fragment takes `title` as a fragment
   parameter and renders it as the page `<h1>`; a model attribute of the same name shadows it.

3. **Playwright's `getByLabel` is substring matching.** Every row's vote control shares the prefix
   `"Upvote "`, so an unqualified `getByLabel("Upvote X")` will match several rows. Use
   `setExact(true)`, or scope the locator to a row, in the a11y and smoke tests.

4. **`mockOidcLogin()` alone breaks `@AuthenticationPrincipal HackathonOidcUser` routes.** Both
   screens and both upvote routes read the principal, so integration tests must bind a real
   persisted `User`, as the existing ITs already do.

5. **CSRF is disabled project-wide** (`SecurityConfig` — `ServerHttpSecurity.CsrfSpec::disable`,
   because organiser forms carry no token field). The `fetch` call therefore needs no CSRF header.
   This is a pre-existing decision being relied on, not changed by this feature; if CSRF is ever
   re-enabled, `topic-vote.js` is one of the places that must learn to send the token.

6. **JS conventions**: no build step. Plain IIFE with `'use strict'`, `var`, ES5-compatible syntax,
   served from `static/js/`, loaded with `th:src="@{/js/...}"`. `country-select.js` is the model to
   follow.

---

## §8a Join: why the redirect changes but the mechanism does not

**Decision**: `TopicJoinController.join(...)` stops hardcoding `redirectHomeWithFlash(...)` and
instead reads a `redirect` form field validated against the same allow-list
`TopicUpvoteController` already uses (`/`, `/topics/overview`, `/topics/{uuid}`). Both templates
add the hidden field. Joining remains a full `POST → 303 → GET`.

**Rationale (the redirect)**: today joining from `/topics/overview` lands the participant on `/`,
because the controller has no idea which screen the form came from. The upvote routes solved this
exact problem with an allow-listed `redirect` field; Join simply never received it. Left alone,
this feature would make the gap conspicuous — voting would hold the participant's scroll position
exactly while joining threw them onto a different page.

**Rationale (keeping it synchronous)**: joining is not a row-local change. It alters the
participant's own status card, moves the Topic into the pinned "Your Topics" section, increments a
member count, and — because a participant may belong to only one Group — flips `canJoinTopics` to
false for *every other row on the page*. An in-place swap of the one cell acted on would leave the
rest of the screen asserting things that are no longer true. A re-render is the honest outcome, and
FR-011d records that as a decision rather than an omission.

**Alternatives considered**:

- *Infer the return screen from the `Referer` header*. No template change needed, but `Referer` is
  routinely stripped by privacy settings and proxies, so the behaviour would be unreliable in
  exactly the environments least likely to report it.
- *Give Join the full asynchronous treatment for consistency*. Rejected on the staleness argument
  above; consistency of mechanism would have bought inconsistency of truth.

**Security note**: the allow-list is the reason this is not an open-redirect regression. Reuse
`TopicUpvoteController.isAllowedRedirect(...)`'s logic rather than writing a second copy — two
divergent allow-lists is how one of them eventually gets it wrong.

**Test impact**: no existing test asserts Join's redirect destination
(`TopicOverviewManagementIT` only asserts the form's `action` contains `/join`), so this is a
low-risk change with new coverage to add rather than old coverage to rewrite.

---

## §9 What is deliberately not researched

- **The chip's icon and voted-state treatment.** Constrained by FR-005–FR-008 but left to
  implementation; any glyph that reads as "vote" and distinguishes states by more than colour
  satisfies the spec.
- **Pagination, sorting, filtering.** Explicitly out of scope (spec Assumptions).
- **Join's conflict messaging.** `TopicJoinConflictException` and `GroupConflictException` continue
  to surface through the existing page-level flash banner. That banner is the right shape for an
  outcome that re-renders the whole page, unlike a vote failure which changes nothing but one cell.
  Routing Join through the new row-anchored notice was considered and set aside.
- **Organiser-space tables.** Out of scope by FR-021 despite sharing the same `.actions` defect.
  The fix is deliberately delivered as a modifier class (§7) so organiser rendering is untouched;
  SC-008 verifies that. Retrofitting organiser tables later is then a one-line change per cell.
