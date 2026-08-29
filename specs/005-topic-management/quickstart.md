# Quickstart: Validating Topic Management, Group Formation & Compliance

Prerequisites: same local setup as 002–004 — `docker-compose up -d` (Postgres), `mvn spring-boot:run`, an OIDC
login available per the existing devcontainer setup. Three logged-in sessions are needed for several scenarios
below (one Organiser, at least two plain/Participant users) — use separate browser profiles against the same
running instance.

## 1. Configure the Compliance Ruleset and toggles as an Organiser

1. Log in as an Organiser, go to **Organiser → Compliance**.

**Expected**: Maximum Group Members shows the seeded default `5`, Minimum is blank, the diversity-requirement
list is empty (FR-011c).

2. Set **Maximum Group Members** to `3`, **Minimum Group Members** to `2`, save.
3. Add a Custom Field diversity requirement referencing an existing "Country" field (or create one first under
   **Organiser → Custom Fields**) with a minimum of `2` distinct values, save.
4. Go to **Organiser → Settings**: confirm **Topic joining enabled** defaults to on, leave it; set **Skill
   Display Mode** to "Still needed only" (the default).

**Expected**: `/organiser/compliance` now shows Max `3`, Min `2`, and the one diversity requirement listed
(FR-011, Acceptance Scenario 3).

5. Try saving Minimum `5` with Maximum still `3`.

**Expected**: rejected — "minimum cannot exceed maximum" (FR-011a, Edge Cases).

6. Try clearing Maximum entirely and saving.

**Expected**: rejected — a Maximum value is required (FR-011b, Edge Cases).

7. Try adding a second diversity requirement with minimum distinct values `1`.

**Expected**: rejected — must be at least 2 (FR-011d, Edge Cases).

## 2. Propose a Topic with Skills (User Story 1)

1. Log in as a registered, Active Participant. From the homepage, click **Propose Topic**.
2. Give it a name, a description, select two Skills, save.

**Expected**: redirected home; the Topic appears on `GET /topics/overview` with those two Skills listed
(FR-001, SC-001).

3. Edit the Topic, remove one Skill, save.

**Expected**: the Skill list update is reflected on both the Home Page and Topic Overview immediately
(FR-002).

## 3. Join and form a Group (User Story 3)

1. As a different Active Participant (not the Topic's author, and not currently in any Group), open the
   homepage.

**Expected**: the proposed Topic appears in the table with participant count `0` and a **Join** action
(FR-003, FR-003a).

2. Click **Join**.

**Expected**: immediate success confirmation, no confirmation dialog (FR-007a); the Topic now shows count `1`;
`GET /topics/overview` shows the same Topic's Compliance status as **Not Compliant** (below the configured
Minimum of 2, FR-012).

3. As a second new Participant, join the same Topic.

**Expected**: count becomes `2`; Compliance status becomes **Compliant** if the two members' Country values
already differ, otherwise stays **Not Compliant** (the diversity requirement from step 1.3, FR-012a).

4. As a third Participant (bringing the Group to the configured Maximum of 3), join.

**Expected**: succeeds — count `3`, now "full" and no longer shown on the Home Page (FR-003b) but still visible
on the Topic Overview.

5. As a fourth Participant, attempt to join the same Topic.

**Expected**: rejected — "This Topic is full" (FR-013, SC-005); no Join action is even shown for this Topic on
the Home Page once full.

6. As one of the three current members, attempt to join a *different* open Topic.

**Expected**: rejected — already belongs to an active Group (FR-010).

*(Concurrency edge case — exercised by the automated integration test, not manually: two simultaneous `POST
/topics/{id}/join` submissions for the last open slot must result in exactly one success, the other rejected
with the same "full" message — see `contracts/join-action.md` and research.md §2.)*

## 4. Organiser override (User Story 7)

1. As the Organiser, open the full Group's detail view (`Organiser → Groups`).

**Expected**: Compliance badge shows **Not Compliant** or **Compliant** per step 3's outcome, never blank.

2. Click **Mark Compliant (Override)**.

**Expected**: badge immediately shows **Compliant (Organiser Override)** (FR-015).

3. As a fifth Participant, attempt to join the same (already-full) Topic again.

**Expected**: now succeeds — count `4`, beyond the configured Maximum of 3 (FR-015b, SC-006).

4. As the Organiser, remove the override.

**Expected**: badge reverts to the automatic evaluation, and the Group (now over capacity) is blocked from
further joins again until membership drops or the override is reapplied (FR-016, FR-013a).

## 5. Topic-joining-enabled toggle (User Story 4)

1. As the Organiser, go to **Organiser → Settings**, disable **Topic joining enabled**, save.
2. As any eligible Participant, open the homepage.

**Expected**: no **Join** action appears on any Topic, even one with open capacity (FR-020b, SC-009).

3. With a Topic page already open in another tab from before the toggle changed, attempt to submit a join.

**Expected**: rejected server-side despite the button having rendered before the change (FR-020c, Edge Cases).

4. Re-enable the toggle.

**Expected**: eligible Participants can join again immediately, no deployment (SC-009).

## 6. Skill Display Mode (User Story 8)

1. Propose a Topic needing two Skills; have one Participant with only one of those two Skills join it (forming
   its Group).
2. As any user, view the Home Page/Topic Overview with **Skill Display Mode** = "Still needed only" (the
   default from step 1.4).

**Expected**: only the one still-uncovered Skill is listed for that Topic (FR-017 Acceptance Scenario 1).

3. As the Organiser, switch the mode to "All associated Skills", save.
4. Reload the Home Page/Topic Overview.

**Expected**: both Skills are now listed for that Topic, regardless of coverage (FR-017 Acceptance Scenario 2),
with no deployment required (FR-018).

## 7. Non-Organiser denial (SC-007)

1. As a plain Participant, attempt to navigate directly to `/organiser/compliance` and to submit a
   compliance-override POST for a known Group id.

**Expected**: both denied (403/redirect-to-login per `SecurityConfig`'s existing `/organiser/**` rule) —
no compliance-settings or override UI is reachable by a non-Organiser at all.

## Automated checks

- Unit: `mvn test -Dtest=TopicServiceTest,TopicDiscoveryServiceTest,TopicJoinServiceTest,GroupServiceTest,ComplianceServiceTest,OrganiserSettingsServiceTest,CustomFieldServiceTest`
- Integration (`WebTestClient` + Testcontainers): `mvn verify` runs every `*ManagementIT` in `topics/`,
  `organiser/compliance/`, `organiser/settings/`, `organiser/group/`, including the join-race concurrent-
  submission test (research.md §2).
- Accessibility (Playwright + axe-core, per research.md §9): `mvn verify` also runs the extended
  `a11y.HomepageAccessibilityIT` plus new `a11y.TopicOverviewAccessibilityIT` and
  `a11y.ComplianceSettingsAccessibilityIT`, asserting zero critical/serious WCAG 2.1 AA violations on the Home
  Page topic table + Join action, the Topic proposal/edit Skill picker, the Topic Overview, and the Organiser's
  compliance-settings and override screens (SC-008).
