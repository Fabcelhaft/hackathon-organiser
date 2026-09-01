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

**Expected**: succeeds — count `3`, now "full" and no longer shown on the Home Page for anyone *except* the
Topic's author, who still sees it pinned above the fullness-sorted rows (FR-003b, FR-033, Story 10) — but
always visible on the Topic Overview.

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

## 8. Topic Details and own-Topic pinning (Stories 9, 10)

1. As any authenticated user, on the Home Page or the Topic Overview, click **View Details** on the Topic from
   step 3 (now at its configured Maximum of 3 members).

**Expected**: a page opens showing the Topic's Name as a heading, a "Topic Info" table (Description, needed
Skills, participant count `3`, Compliance status — Compliant/Not Compliant per steps 1–3's configuration) and,
alongside it, a "Joined Participants" table listing all three joined Participants by display name (FR-030,
FR-031, SC-011). The word "Group" does not appear anywhere on this page (FR-036).

2. Compare what a Participant outside the joined Group sees in that list against what an Organiser (or one of
   the joined members viewing their own row) sees.

**Expected**: the non-member/non-organiser viewer sees each joined Participant's `public`-marked Custom Field
values and Skills only if **Skill visibility** is currently enabled in Organiser Settings; the Organiser (and
each member viewing themselves) sees every field and Skill regardless (FR-031, mirroring the Participants
Directory's own detail-page rule, research.md §10).

3. As a user who is outside the configured **Participants Directory audience** (Organiser → Settings) and thus
   gets denied at `/participants`, open this same Topic Details page.

**Expected**: still succeeds — the joined-Participants list still renders (FR-032, Story 9 Acceptance Scenario
6); only `/participants` itself is gated by that audience setting.

4. Propose a new Topic as a Participant (leave it unapproved, i.e. Pending) and, separately, note the
   already-full Topic from step 3 (also authored by you, or use a Topic you separately authored and filled to
   its Maximum).

**Expected**: on both the Home Page and the Topic Overview, your Pending Topic and your full Topic both appear
pinned above every other row (FR-033, FR-034); the Pending one shows **View Details** but no **Join** action
(FR-035); nobody else sees your Pending Topic at all, pinned or otherwise.

5. On the Home Page only, as a user who has authored more Topics than fit under the 10-row cap alongside the
   normal fullness-sorted rows.

**Expected**: every one of your own Topics is still shown; other Topics are pushed out of the visible 10 first
(FR-033, Story 10 Acceptance Scenario 3). On the Topic Overview, nothing is ever hidden this way — pinning only
reorders (FR-034).

## 9. Leave a joined Topic (Story 11)

1. As one of the three Participants who joined the Topic from step 3 (still at its configured Maximum of 3),
   open its Topic Details view.

**Expected**: alongside the Topic Info and Joined Participants tables, a **Leave** action is shown (FR-037) —
not shown to a viewer who is not a member of this Topic's Group.

2. Click **Leave**.

**Expected**: immediate success confirmation, no confirmation dialog (FR-037a); the page reloads showing
participant count `2` and this Participant no longer listed in the Joined Participants table; the Home
Page/Topic Overview now show count `2`, and the Topic is no longer "full" (FR-003b) since it has dropped below
the configured Maximum.

3. As the same Participant (who no longer belongs to any active Group), join a *different* open Topic.

**Expected**: succeeds immediately (FR-037d) — contrast with step 3.6 above, where a current member was
rejected.

4. Have the two remaining members leave one at a time, the second (last) one leaving.

**Expected**: after the first leaves, count is `1`, Compliance status re-evaluates against the remaining
member alone. After the last member leaves, the Group is disbanded (FR-037c): the Topic's Details view now
shows an empty Joined Participants table ("no one has joined yet") and a blank Compliance value, and the Topic
becomes joinable again from a fresh **Join** (FR-008) — reappearing on the Home Page with count `0`.

5. As a user with no Participant record (or one who is not currently a member of this Topic), attempt `POST
   /topics/{id}/leave` directly against a Topic they can view.

**Expected**: rejected server-side (FR-037b), consistent with step 3.6's `POST /topics/{id}/join` denial for an
ineligible requester.

*(Concurrency edge case — exercised by the automated integration test, not manually: two simultaneous `POST
/topics/{id}/leave` submissions where one is the Group's last remaining member must result in the Group being
disbanded exactly once, never zero or twice — see `contracts/topic-details.md` and research.md §14.)*

## Automated checks

- Unit: `mvn test -Dtest=TopicServiceTest,TopicDiscoveryServiceTest,TopicJoinServiceTest,GroupServiceTest,ComplianceServiceTest,OrganiserSettingsServiceTest,CustomFieldServiceTest`
  (`TopicJoinServiceTest`/`GroupServiceTest` now include `leave()` cases, research.md §14).
- Integration (`WebTestClient` + Testcontainers): `mvn verify` runs every `*ManagementIT` in `topics/`
  (including the new `TopicDetailManagementIT`), `organiser/compliance/`, `organiser/settings/`,
  `organiser/group/`, including the join-race concurrent-submission test (research.md §2) and its matching
  leave-race concurrent-submission test (research.md §14).
- Accessibility (Playwright + axe-core, per research.md §9): `mvn verify` also runs the extended
  `a11y.HomepageAccessibilityIT` and `a11y.TopicOverviewAccessibilityIT`, plus new `a11y.TopicDetailAccessibilityIT`
  and `a11y.ComplianceSettingsAccessibilityIT`, asserting zero critical/serious WCAG 2.1 AA violations on the
  Home Page topic table + Join/View Details actions, the Topic proposal/edit Skill picker, the Topic Overview
  (+ its Join/View Details actions), the Topic Details view (its Topic Info/Joined Participants tables and the
  Join/Leave actions), and the Organiser's compliance-settings and override screens (SC-008).
