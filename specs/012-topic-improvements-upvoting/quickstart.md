# Quickstart: Validating Topic Improvements — Upvoting, Author Visibility & Reference IDs

Prerequisites: same local setup as prior features — `docker-compose up -d` (Postgres),
`mvn spring-boot:run`, OIDC login per the devcontainer setup. Two logged-in sessions are needed for
several scenarios (at least two plain users, one of whom is also an Organiser) — separate browser
profiles against the same running instance. At least one pre-existing Approved Topic (from a prior
feature's data, or `docs/demo-data.sql`) is useful for validating the backfill in step 5.

## 1. Upvote and withdraw an upvote (User Story 1)

1. Log in as User A. On the Home Page, find any Topic and click **Upvote**.

**Expected**: the count increases by one immediately (redirect back to the same page); the control
now reads as "withdraw" for User A (contracts/topic-upvote-action.md).

2. Reload the page.

**Expected**: the count and User A's active-upvote state persist (SC-001, SC-002).

3. Click **Upvote** on the same Topic again (e.g. via a second tab/replay).

**Expected**: no change — the count does not increase a second time (FR-001, idempotent cast).

4. Log in as User B (a different user) in a second session and upvote the same Topic.

**Expected**: the count increases by one more; nowhere in the UI — including as an Organiser on
`/organiser/audit/list` — does anything show that User A or User B specifically cast a vote
(FR-003, SC-003).

5. As User A, click "withdraw" on that Topic.

**Expected**: the count decreases by one; User A can upvote it again afterward (FR-002).

6. As the Topic's own author, upvote your own Topic.

**Expected**: succeeds identically to any other user (Clarifications: self-upvoting allowed).

## 2. Admin toggle hides/restores upvoting instance-wide (User Story 1)

1. Log in as an Organiser, go to **Organiser → Settings**.

**Expected**: **Topic upvoting enabled** defaults to on (research.md §7).

2. Turn it off, save.

**Expected**: on the Home Page, Topic Overview, and any Topic Details page, no upvote control or
count appears for any Topic, for any user (FR-007, SC-004) — previously-cast upvotes are not
deleted (verify via step 3).

3. Turn it back on, save.

**Expected**: every previously-recorded count and every user's own active/inactive upvote state
reappears exactly as it was before disabling (FR-008).

## 3. Home Page ordering: upvotes as a tiebreaker (User Story 1, FR-005a)

1. Create or find two not-full Topics with the same current participant count.
2. Give one of them one more upvote than the other (any user, per section 1).

**Expected**: on the Home Page, the Topic with more upvotes now appears above the other one; a
Topic with a higher participant count still appears above both regardless of upvotes (SC-008).

3. Turn off **Topic upvoting enabled** (Organiser → Settings) and reload the Home Page.

**Expected**: ordering among equally-full Topics reverts to whatever it was before this feature
(no upvote-based tiebreak while disabled).

## 4. A newly-approved Topic gets a reference number (User Story 2)

1. Log in as a Participant, propose a new Topic (or use an existing Pending one, e.g. with
   `topic_approval_required` enabled under Organiser Settings).

**Expected**: nowhere in the UI does this Topic show a reference number yet (FR-009).

2. Log in as an Organiser and approve it.

**Expected**: the Topic now shows a reference number (e.g. "#1") on the Home Page, Topic Overview,
and Topic Details (FR-010, FR-012).

3. Approve a second Pending Topic.

**Expected**: it receives a different, higher reference number than the first (FR-011, SC-005).

## 5. Pre-existing Approved Topics get a reference number on upgrade (User Story 2)

1. Using a database that already has one or more Approved Topics from before this feature
   (e.g. restart the app against existing data, or check topics created by a prior feature's
   quickstart/demo data), start the application.

**Expected**: every one of those Topics now shows a reference number in the UI immediately, with
no manual step taken (FR-013, SC-006) — Topics approved earlier (per the Audit Trail) show lower
numbers than ones approved later (research.md §5).

2. Restart the application again.

**Expected**: no reference numbers change, and no error occurs — the backfill is a no-op the
second time (idempotency check).

## 6. A Topic's own Details view shows who authored it (User Story 3)

1. Log in as any user, open any Topic's own Details view (`/topics/{id}`) — not the Topic Overview.

**Expected**: the "Topic Info" table's first row now shows the Topic's author, near the top of the
page (FR-014, SC-007) — previously this view showed no author information at all. The Topic
Overview (`/topics/overview`) is unchanged; it already showed an author column before this feature.
