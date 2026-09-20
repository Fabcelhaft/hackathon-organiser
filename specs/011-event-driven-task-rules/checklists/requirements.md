# Specification Quality Checklist: Event-Driven Task Rules

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-09-19
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

### Validation iteration 2 — 2026-09-19 (all items pass)

All three clarifications answered (Q1: A, Q2: A, Q3: A) and folded into the requirements. The
Clarifications section records each answer with the options rejected and why, plus which
requirements carry it.

Changes made in this iteration:

- **Q1 (Option A — extend the existing Event handler screens, one new nav entry)**: added FR-032,
  FR-033, FR-034 under a new "Placement of the configuration UI" heading; tightened FR-020 to
  require exactly one new navigation entry and forbid a second one for rule configuration. The
  "Proposed UI Integration" section became "UI Integration" — it is now binding rather than a
  proposal, and its rejected alternatives are recorded as such.
- **Q2 (Option A — Organiser-role Users only)**: tightened FR-005 and FR-021 to name the Organiser
  role and require an explicit unassigned choice; narrowed the Assignee entity; extended the
  assignee-loses-Organiser-rights edge case and added one for a Rule whose default assignee stops
  being an Organiser; made User Story 2 scenario 1 select from the offered Organisers.
- **Q3 (Option A — always create, never de-duplicate)**: tightened FR-016 to forbid suppression,
  merging, and de-duplication; made SC-002 concrete ("exactly one Task from that Rule") now that it
  no longer has to stay neutral across policies; replaced the "depends on the duplicate policy" edge
  case with the decided behaviour; added User Story 1 scenario 4 covering an identical repeat title.

### Residual notes for planning

- **The "websocket" wording in the request is resolved, not open.** `EventDestinationType` holds
  exactly `KAFKA` and `HTTP_POST`; there is no WebSocket transport. The requester confirmed on
  2026-09-19 that HTTP POST was what they meant, so this feature adds one new handler type beside
  those two and no WebSocket work is in or out of scope — it simply does not arise. Recorded as the
  first entry in Assumptions.
- **Multi-Event-Type Rules share one title pattern.** Wildcards valid for one Event Type are usually
  absent from another. Intended practice is one Rule per Event Type; FR-012 makes the mixed case
  degrade to an empty substitution rather than fail. Accepted trade-off of the Q1 answer, noted in
  the UI Integration section.
- **Deliberate exclusions** (see Assumptions): no manual Task creation, no notifications, no
  audit-trail coverage for Task changes, no per-Topic or per-Group Rule scoping, no Task fields
  beyond assignee and done state, no automatic archival.

**Status**: Ready for `/speckit-plan`.

### Validation iteration 3 — 2026-09-19 (post-`/speckit-clarify`, still 16/16)

Five clarification questions asked and answered. No checkbox changed state — the spec passed all 16
before and after — but "Requirements are testable and unambiguous" went from passing on judgement to
passing on stated numbers, which is the substantive gain:

- Wildcard syntax pinned to `{{dotted.path}}` with numeric segments for list positions (FR-009,
  FR-010), so FR-007's "reject a malformed wildcard" and FR-008's help text are now testable.
- Title cap set at 500 characters with a visible ellipsis (FR-015), replacing an unstated
  "permitted length".
- Done-inclusive view capped at the 200 most recent (FR-028a); SC-006 rewritten against that cap.
- Task rows fixed at four columns with created/completed time as secondary text (FR-021, FR-021a),
  resolving a genuine contradiction between FR-021's four columns and User Story 3's completion-time
  requirement, and making identically-titled Tasks distinguishable.
- Deleting a Rule now removes its undone Tasks (FR-002a), closing an operational hole: previously a
  badly-written Rule's output could only be cleared one Done click at a time.

Two entries in "Residual notes for planning" above are now partly superseded: the websocket item is
confirmed resolved, and "no automatic archival or purge" still holds but Tasks are now removable via
Rule deletion. The multi-Event-Type trade-off note stands unchanged.

### Validation iteration 4 — 2026-09-19 (second `/speckit-clarify` pass, still 16/16)

Three further questions asked and answered; stopped short of the five-question quota because the
remaining gaps are genuinely low-impact. No checkbox changed state. One item, "No contradictory
earlier statement remains", was passing on a reading that turned out to be wrong — see below.

- **Value rendering pinned** (FR-011a, FR-012): a wildcard writes its value exactly as it appears in
  the Event data — text, number, `true`/`false`, ISO-8601 timestamp, full identifier — and an empty
  value now behaves identically to a missing path. Previously SC-007 promised titles contain "the
  Event's real values" with no statement of what those look like as text, so no acceptance test
  could assert an expected title string.
- **A real contradiction fixed** (FR-003, FR-032a, FR-034): FR-003 required "one or more" Event
  Types while FR-034 required identical behaviour across all three handler types — and
  `EventDestinationController.eventTypeValues` accepts none, with the list rendering "None selected".
  FR-003 now reads "zero or more", and FR-032a marks any *enabled* handler with no Event Types as
  inert. This is the one deliberate scope expansion beyond adding a handler type: FR-032a applies to
  Kafka and HTTP POST rows too. Recorded in Assumptions.
- **Personal data in titles decided** (Assumptions, new Edge Case): a title may quote a participant's
  email or custom-field answers and survives that participant's deletion unchanged. Accepted
  deliberately rather than mitigated, matching `ParticipantService`'s existing behaviour of
  snapshotting an audit label before deleting a participant. This closes the Security & Privacy item
  that iteration 3 left Outstanding.

Remaining Outstanding, all low-impact and none blocking: observability beyond FR-018's failure
logging; whether the assignee control stays editable on a done row; timestamp display format in
FR-021a's secondary text. The Integration item (the `event_destinations` type/fields CHECK
constraint needs a third branch for the new type) stays Deferred to planning.

**Status**: Ready for `/speckit-plan`.

