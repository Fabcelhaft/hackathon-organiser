# Quickstart: Validating the Audit Trail

Prerequisites: same local setup as 002–005 — `docker-compose up -d` (Postgres), `mvn spring-boot:run`, an OIDC
login available per the existing devcontainer setup. Two logged-in sessions are needed (one Organiser, one
plain/Participant user) — use separate browser profiles against the same running instance.

## 1. Recording: a standard user's own edit is captured (User Story 1)

1. Log in as a registered, Active Participant who has authored a Topic. Edit that Topic's description and save.
2. Log in as an Organiser (separate session). Go to **Organiser → Topics**, open that Topic, click **Audit**
   (`GET /organiser/topics/{id}/audit`).

**Expected**: one `EDITED` entry appears, attributed to the Participant's underlying User, capacity **Standard
User** (FR-002, Acceptance Scenario 1). No old/new values shown (Topic name/description edits are not
high-stakes per FR-002a).

## 2. Recording: an Organiser action on someone else's record is captured (User Story 1)

1. As the Organiser, open **Organiser → Participants**, pick a Participant, change their **Status**.
2. Reopen that Participant's detail page, click **Audit**.

**Expected**: one `STATUS_CHANGED` entry, capacity **Organiser**, with the old and new status values both shown
(FR-002a, Acceptance Scenario 2).

## 3. Recording is immutable (User Story 1, Acceptance Scenario 4)

Confirm there is no edit/delete affordance anywhere on the Audit page for any entry — by design, no route
exists to modify one (FR-010; there is nothing to click, not merely a disabled button).

## 4. On-demand loading and organiser-only visibility (User Story 2)

1. As the Organiser, open a Topic's, Group's, or Participant's detail page. Confirm no audit information is
   present anywhere on that page until you click **Audit** (Acceptance Scenario 1).
2. From the Topic's (or Participant's) detail page, click **Audit**; confirm that record's own full history
   loads (Acceptance Scenario 2).
3. From a Group's detail page (`/organiser/groups/{id}`), click **Audit**; confirm it navigates to
   `/organiser/topics/{topicId}/audit` for that Group's Topic — the same page and history you'd see clicking
   **Audit** from that Topic's own detail page, not a separate Group-scoped page (Acceptance Scenario 3, SC-006).
4. Log out; log in as a plain user (no Organiser privilege). Open the same detail pages (if reachable to them at
   all) — confirm no "Audit" link/button is present on any of them (Acceptance Scenario 4).
5. While still logged in as that plain user, attempt `GET /organiser/topics/{id}/audit` directly by URL.

**Expected**: rejected before any audit content renders (redirect to login, or a 403/404 per this app's
existing `/organiser/**` handling) — never the audit table (Acceptance Scenario 5, FR-006).

## 5. Topic membership changes record both sides, linked — self-service and organiser-driven alike (User Story 3)

1. As an Active Participant with no current Group, join an open Topic (`POST /topics/{id}/join`).
2. As the Organiser, open that Topic's Audit page (or the new Group's detail page, then click **Audit** — they
   land on the same page) — confirm a `JOINED` entry naming the Participant.
3. Open that same Participant's own Audit page — confirm a matching `JOINED` entry naming the Topic
   (Acceptance Scenario 1, contracts/audit-retrieval.md).
4. Have a second Active Participant join the same Topic (now with an existing Group) — repeat steps 2–3 for
   them (Acceptance Scenario 2).
5. Have the first Participant leave the Topic (`POST /topics/{id}/leave`) — repeat steps 2–3, expecting a `LEFT`
   pair instead of `JOINED` (Acceptance Scenario 3).
6. As the Organiser, open **Organiser → Groups**, open that Topic's Group, and directly add a third Participant
   via the add-member form (`POST /organiser/groups/{id}/members`) — repeat steps 2–3, confirming the exact same
   paired-entry shape as a self-service join, except the actor is now the Organiser, in the Organiser capacity
   (Acceptance Scenario 4). Then directly remove that same Participant via the organiser view and confirm the
   equivalent `LEFT` pair (Acceptance Scenario 5).

**Expected**: all four ways of changing membership (self-service join, self-service leave, organiser add,
organiser remove) produce the identical two-linked-entry shape — the only difference between them is who the
recorded actor is and their capacity.

**Expected**: each join produces exactly two entries sharing one `action_id` (verified at the data level per
data-model.md — not surfaced in the UI itself, but the two entries are never observed independently of each
other, satisfying SC-005).

## 6. Empty history and post-deletion legibility (Edge Cases)

1. As the Organiser, propose a brand-new Topic with no activity yet (or open a freshly-registered Participant
   with no other activity) and open its Audit page.

**Expected**: an empty, clearly-labeled history — no error (Edge Cases).

2. Delete a Participant who has some audit history (`POST /organiser/participants/{id}/delete`, after removing
   them from any Group).
3. As the Organiser, find that Participant's audit entries — e.g. via the Topic side of any join they were once
   part of.

**Expected**: their historical entries remain, each still showing the Participant's `subject_label` (their
display name at the time), even though `subject_id` on those rows now points to a Participant that no longer
exists — there is no foreign key to enforce or null out (FR-009, research.md §3).

## 7. A disbanded Group's history stays on its Topic, and a re-formed Group shares the same trail (Edge Cases)

1. As an Organiser, disband a Topic's Group (`POST /organiser/groups/{id}/disband`). Open that Topic's Audit
   page — confirm the `DISBANDED` entry (and every earlier entry for that Group) is still there.
2. Have a Participant join the same Topic again, forming a new Group. Open that new Group's detail page, click
   **Audit** — confirm it shows the *same* Topic audit trail, now including both the old Group's history and
   the new `JOINED` entry, with no need to know which specific Group each older entry belonged to.
