# Contract: Compliance Ruleset & Group Override (Stories 6, 7 — FR-011–FR-016, FR-019, FR-020)

All routes below require `ROLE_ORGANISER` (`/organiser/**`, `SecurityConfig`'s existing rule — FR-019: denied
to every other user with no additional check needed in the controller itself).

## GET /organiser/compliance — new route

**New** (`organiser.compliance.ComplianceController`). Shows the current `maxGroupMembers`/`minGroupMembers`
(from `OrganiserSettingsService.current()`) in an edit form, plus the current list of
`ComplianceDiversityRequirement` rows (each showing its Custom Field's label and configured minimum
distinct-value count) with a remove action per row, plus an add-requirement form (a dropdown of existing
Custom Field Definitions not already configured — the unique-per-field guard, research.md §3 — and a
minimum-distinct-value-count input, defaulting to 2).

- **200** — always; a fresh instance shows `maxGroupMembers = 5` (FR-011c's seeded default), no `minGroupMembers`,
  and an empty requirement list.

## POST /organiser/compliance — new route (Maximum/Minimum, FR-011, FR-011a, FR-011b)

- **303 → `/organiser/compliance?flash=...`** — valid: `maxGroupMembers >= 1`, and either `minGroupMembers`
  is blank (cleared, FR-011 "optional") or `1 <= minGroupMembers <= maxGroupMembers` (FR-011a). Takes effect
  for every subsequent compliance evaluation and join attempt immediately, no deployment required (FR-011,
  matching 003/004's toggle-freshness precedent).
- **200, form re-rendered with a field-associated error** — `maxGroupMembers` left blank (FR-011b, "the
  system rejects the save and requires a Maximum value to remain set") or `< 1`; `minGroupMembers` set higher
  than `maxGroupMembers` (FR-011a, Edge Cases) — the error text names which constraint failed (FR-026), not a
  bare rejection.

## POST /organiser/compliance/diversity-requirements — new route (FR-011, FR-011d)

- **303 → `/organiser/compliance?flash=...`** — a `customFieldDefinitionId` referencing an existing,
  not-already-configured Custom Field Definition, with `minimumDistinctValues >= 2` (defaults to `2` if the
  form field is left at its default): `ComplianceService.addRequirement(...)` adds the row.
- **200, form re-rendered with a field-associated error** — `minimumDistinctValues < 2` (FR-011d, Edge Cases:
  "the system MUST reject it, since a diversity requirement below 2 distinct values is meaningless"); an
  unknown or already-configured `customFieldDefinitionId`.

## POST /organiser/compliance/diversity-requirements/{id}/delete — new route

- **303 → `/organiser/compliance?flash=Requirement+removed.`** — always succeeds for an existing row id (no
  further guard needed — removing a diversity requirement never orphans anything, unlike deleting the
  underlying Custom Field Definition itself, which is guarded separately — see `organiser-toggles.md`'s Custom
  Field delete-guard note).

## Group compliance override — extends the existing Group detail view (Story 7, FR-015, FR-016)

**Modified** (`organiser.group.GroupController`, `organiser/groups/detail.html`). The existing Group detail
page gains a Compliance status badge (FR-014, text/icon not color alone — FR-025) and, immediately beside it,
a toggle control:

### POST /organiser/groups/{id}/compliance-override

- **303 → `/organiser/groups/{id}?flash=Compliance+override+set.`** — sets `Group.complianceOverride = true`
  (FR-015): the detail page's badge immediately shows `Compliant (Organiser Override)` regardless of the
  automatic evaluation, and — from this point on — `TopicJoinService`/`GroupService.join` no longer enforces
  `maxGroupMembers` for this specific Group (FR-015b, SC-006), reusing the exact `join(...)` code path new
  Participants already go through, just with the capacity branch skipped for this one Group.
- **303 → `/organiser/groups/{id}?flash=Compliance+override+removed.`** — clears the flag (FR-016): the badge
  and the Maximum cap both revert to the automatic evaluation/enforcement on the very next read (FR-016,
  Acceptance Scenario 3) — no cached prior state anywhere.
- **404** — unknown `groupId`.

Every non-Organiser attempt at any route in this contract is denied by `SecurityConfig`'s existing
`/organiser/**` rule before the request reaches a controller at all (SC-007).
