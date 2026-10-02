# Specification Quality Checklist: Cleaner Topic List UI

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-10-01
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

All 16 items pass against the clarified spec (16/16, unchanged across both clarification sessions
and the post-planning Join amendment).

### Validation history

**Iteration 1 (during `/speckit-specify`)** — fixed FR-002/FR-009/FR-015, which were phrased
"Neither screen MUST render ..." and so stated the opposite of their intent, and SC-008, which
demanded organiser screens render "byte-identically".

**Iteration 2 (during the first `/speckit-clarify`)** — fixed SC-011 (measured internals rather
than an observable outcome), two acceptance scenarios numbered "3a."/"3b." (invalid list markers),
and SC-008's position in the sequence.

**Iteration 3 (during the second `/speckit-clarify`)** — merged the awkward identifier FR-006a1
into FR-006a and rewrote its three cross-references; normalized four competing names for the same
element ("vote chip", "compact chip", "compact control", "compact vote control") onto **compact
vote control**, now stated as the canonical term in FR-005.

### Scope growth — read this before planning

The feature as originally described was presentational: drop a button, drop a column, restyle two
others. Three clarification answers have since moved it well past that, and the spec has grown
from 308 to ~446 lines and from 23 to 38 functional requirements. The growth is deliberate and
each step was chosen by the requester, but the cumulative effect deserves a decision rather than
discovery during implementation:

- **In-place vote updating** (session 1, Q3) added behaviour to what had been a styling change:
  FR-006a-d, SC-009, SC-010.
- **Optimistic display** (session 2, Q2) added a reconciliation path and a window in which the
  on-screen count is a prediction: FR-006c, FR-006d, FR-008d.
- **Row-anchored failure notices** (session 2, Q3) added an overlay pattern this app does not
  currently have: FR-006c1-c3, FR-008e, SC-009a.
- **Screen-reader announcements** (session 2, Q1) added a toggle state and a live announcement
  region: FR-008a-c, SC-006a.

Roughly two-thirds of the current requirement count now concerns asynchronous vote submission
rather than the visual cleanup that prompted the feature. User Stories 1, 3 and 4 — the parts that
directly answer the original complaint — remain small and independently shippable. **Splitting the
asynchronous voting work into its own feature is worth considering at `/speckit-plan`**, which
would let the visual cleanup ship quickly while the behavioural change gets its own test coverage.

### Post-planning amendment (Join)

Raised after `/speckit-plan`: the Join control was covered visually by FR-011/FR-012 but nothing
specified its behaviour. Verification found a pre-existing defect — `TopicJoinController` hardcodes
a redirect to the Dashboard, so joining from the Topics overview moves the participant to a
different page. Shipping this feature unchanged would have made that conspicuous, since voting
would preserve the participant's place exactly.

Folded in as User Story 2a, FR-011a-d and SC-005a: Join gains the allow-listed return-to-screen
redirect the upvote routes already use, and is quieted to sit beside the compact vote control.
FR-011d records the decision that Join stays synchronous — joining changes the participant's
status, the pinned section, member counts and every other row's joinability, so an in-place update
would leave the rest of the screen stale.

Join's conflict messaging deliberately stays on the page-level flash banner rather than the new
row-anchored notice: that banner is the right shape for an outcome that re-renders the whole page.

### Decisions resolved without spending a question

- Whether unauthenticated visitors see these screens, and whether non-registered users may upvote:
  both screens require authentication; upvoting gates only on the feature toggle and Topic
  visibility.
- SC-006's accessibility bar: the project already runs automated WCAG 2.1 AA scans against both of
  these screens, failing on critical or serious violations.
- Repeated-activation safety: the upvote table's composite primary key makes casting idempotent and
  withdrawal a no-op, so no spec-level rule was needed beyond convergence.

### Left to `/speckit-plan` as design, not requirement

- The visual form of the compact vote control (icon choice, filled vs outlined voted state),
  constrained by FR-005 to FR-008 but not prescribed.
- How a row carrying both a Join control and a vote control stays on one line at 1024px (FR-012).
- Whether the remaining action column gains a visible header.
- How the failure notice is positioned and dismissed within the constraints of FR-006c2-c3.
