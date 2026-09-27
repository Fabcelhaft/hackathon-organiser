# Feature Specification: Topic Improvements — Upvoting, Author Visibility & Reference IDs

**Feature Branch**: `012-topic-improvements-upvoting`

**Created**: 2026-09-26

**Status**: Draft

**Input**: User description: "Topic improvements.

1. Upvoting of topics. Each user can upvote a topic (only one upvote per topic) In the UI it is not possible to see who upvoted, just how many upvotes. Upvotes can be pulled back. Vissible in the overview. Feature can be enabled/disabled in admin UI
2. Topic overview also shows author/Creator on top
3. Once a topic is approved, it gets a numeric ID for reference, visible in the UI. Existing approved topics get after update to the new version a clear ID added. New topics only after approval"

## Clarifications

### Session 2026-09-26

- Q: Should a Topic's own author be allowed to upvote their own Topic? → A: Allowed — the author is treated exactly like any other user for upvoting.
- Q: Should upvote count influence the Home Page's Topic ordering? → A: Yes, but only as a tiebreaker — the Home Page keeps its existing fullest-first ordering from feature 005 as the primary sort; when two Topics have the same participant count, the one with more upvotes ranks higher.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Upvote a Topic to Signal Interest (Priority: P1)

Any authenticated user can cast a single upvote on a Topic to signal support for it, and can withdraw that upvote later if they change their mind. The UI only ever shows the total number of upvotes a Topic has received — never the identities of who cast them — and this total is visible wherever Topics are listed.

**Why this priority**: This is the largest and most user-facing piece of the request. It introduces a new interaction users take on every Topic-listing surface, and every other requirement in this story (one-per-user limit, anonymity, retraction, admin toggle) exists to make that single interaction safe and predictable.

**Independent Test**: Can be fully tested by having a user upvote a Topic, confirming the visible count increases by one and no upvoter identity is ever exposed, then having that same user retract their upvote and confirming the count decreases by one, and confirming a second upvote attempt by the same user on the same Topic has no additional effect.

**Acceptance Scenarios**:

1. **Given** an authenticated user viewing a Topic they have not upvoted, **When** they choose to upvote it, **Then** the Topic's upvote count increases by one and the UI now shows that this user has an active upvote on that Topic.
2. **Given** an authenticated user who has already upvoted a Topic, **When** they view that Topic anywhere it is listed, **Then** the UI indicates their upvote is active and offers a way to withdraw it, without ever revealing that fact to other users.
3. **Given** an authenticated user who has upvoted a Topic, **When** they withdraw their upvote, **Then** the Topic's upvote count decreases by one and they can upvote that same Topic again later.
4. **Given** a Topic with any number of upvotes, **When** any user (including an Organiser) views that Topic, **Then** only the total upvote count is visible — no list or count of individual upvoters is ever shown or exposed.
5. **Given** a Topic listed on the Home Page or the Topic Overview, **When** the row renders, **Then** the current upvote count is displayed alongside the Topic's other details.
6. **Given** two not-full Topics on the Home Page with the same current participant count but different upvote counts, **When** the Home Page table renders, **Then** the Topic with more upvotes is ordered above the other, with the fullest-first ordering from feature 005 otherwise unchanged as the primary sort.
7. **Given** an Organiser viewing the admin settings, **When** they disable the upvoting feature, **Then** upvote controls and counts stop appearing anywhere in the UI, existing upvotes are preserved (not deleted), and re-enabling the feature immediately restores the previously accumulated counts and each user's own active/inactive upvote state.
8. **Given** the upvoting feature is disabled in admin settings, **When** any user views a Topic listing, **Then** no upvote control or count is shown for any Topic.

---

### User Story 2 - Reference a Topic by a Stable Numeric ID (Priority: P2)

Once a Topic is approved, it is assigned a permanent, unique numeric reference ID that is shown wherever the Topic appears in the UI, so users and Organisers can refer to a specific Topic unambiguously (e.g., "Topic #42") in conversation or written communication. Topics that are still awaiting approval do not yet have one.

**Why this priority**: Depends only on the existing approval workflow, not on Story 1, and delivers standalone value (a stable way to talk about a specific Topic) independent of upvoting.

**Independent Test**: Can be fully tested by approving a previously-Pending Topic and confirming a numeric ID appears on it everywhere it is shown, confirming a still-Pending Topic shows no such ID, and confirming two different approved Topics never share the same ID.

**Acceptance Scenarios**:

1. **Given** a Topic that is still Pending approval, **When** it is displayed anywhere in the UI, **Then** no numeric reference ID is shown for it.
2. **Given** a Pending Topic, **When** an Organiser approves it, **Then** the system assigns it a permanent numeric reference ID that was never previously assigned to any other Topic.
3. **Given** a Topic that was approved before this feature existed, **When** the system is upgraded to a version that includes this feature, **Then** that Topic is automatically assigned a numeric reference ID without requiring any manual action, and it appears in the UI from then on exactly as if it had been assigned at approval time.
4. **Given** two different approved Topics, **When** their reference IDs are compared, **Then** they are always different numbers.
5. **Given** an approved Topic with a reference ID, **When** it is shown on the Home Page, the Topic Overview, or the Topic Details view, **Then** its reference ID is displayed alongside its other details.

---

### User Story 3 - See Who Authored a Topic on Its Details View (Priority: P3)

A user viewing a single Topic's own Details view can immediately see who authored it, presented as one of the first, most prominent pieces of information on that page — not buried below other content.

**Why this priority**: The Topic Overview (the all-Topics list) already shows each Topic's author; this is the one place that doesn't yet — a user reading a single Topic's own page has no way to see who proposed it without leaving that page. Smallest and lowest-risk of the three stories, and independent of the other two.

**Independent Test**: Can be fully tested by opening any Topic's own Details view (the single-Topic page, not the all-Topics overview list) and confirming its author/creator is visible near the top of the page's Topic Info, without needing to consult the Topic Overview or any other page.

**Acceptance Scenarios**:

1. **Given** a user opens a Topic's own Details view, **When** the page renders, **Then** that Topic's author is visibly presented as one of the first pieces of information on the page, alongside its other key details.

---

### Edge Cases

- What happens when a user upvotes a Topic, the upvoting feature is then disabled, and later re-enabled? The user's upvote remains active and counted throughout — disabling only hides the UI, it never clears recorded upvotes.
- What happens if a user attempts to upvote a Topic they authored themselves? Self-upvoting is permitted (see Clarifications) — a Topic's author is treated the same as any other user for upvoting purposes.
- What happens to a Topic's upvote count and reference ID if the Topic is later edited (name, description, Skills)? Neither is affected by edits — upvotes and the reference ID are independent of the Topic's editable content.
- How are reference IDs assigned to Topics that were already Approved at the time of the upgrade? They are assigned in the order those Topics were originally approved (oldest approval first), so relative ordering among existing Topics is preserved.
- What happens if two Organisers approve two different Pending Topics at nearly the same time? Each approved Topic still receives its own unique reference ID; no two Topics ever end up with the same one.
- What happens when the upvoting feature is disabled — can a user still see whether they personally upvoted a Topic? No — while disabled, no upvote-related UI (count or personal state) appears anywhere.
- What determines Home Page ordering among equally-full Topics while the upvoting feature is disabled? Upvote counts are not used as a tiebreaker while the feature is disabled — ordering among equally-full Topics falls back to whatever tiebreak feature 005 already used before this feature existed.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST allow any authenticated user to cast at most one upvote per Topic.
- **FR-002**: System MUST allow a user to withdraw their own upvote from a Topic at any time, after which they may upvote that Topic again.
- **FR-003**: System MUST display, for every Topic shown in the UI, only the total count of active upvotes — never the identity of any individual upvoter, to any user regardless of role.
- **FR-004**: System MUST indicate to a user, on any Topic they view, whether they personally have an active upvote on it.
- **FR-005**: System MUST display each Topic's upvote count on the Home Page and the Topic Overview, and on the Topic Details view.
- **FR-005a**: System MUST order the Home Page's not-full Topics primarily by current participant count (fullest first, per feature 005), and MUST break ties between Topics with equal participant counts by ordering the Topic with more upvotes first, but only while the upvoting feature is enabled — while it is disabled, this tiebreak MUST NOT be applied.
- **FR-006**: System MUST provide an Organiser-only, instance-wide setting that enables or disables the upvoting feature.
- **FR-007**: System MUST hide all upvote controls and upvote counts from every user-facing view when the upvoting feature is disabled.
- **FR-008**: System MUST preserve all previously recorded upvotes while the upvoting feature is disabled and restore their visibility, counts, and each user's personal upvote state unchanged when the feature is re-enabled.
- **FR-009**: System MUST NOT assign a numeric reference ID to a Topic while it is Pending approval.
- **FR-010**: System MUST assign each Topic a permanent, unique numeric reference ID at the moment it is approved.
- **FR-011**: System MUST NOT reassign, reuse, or change a Topic's numeric reference ID once it has been assigned, even if the Topic is later edited.
- **FR-012**: System MUST display a Topic's numeric reference ID on the Home Page, the Topic Overview, and the Topic Details view whenever that Topic has one.
- **FR-013**: System MUST, as part of upgrading to the version that introduces this feature, assign a numeric reference ID to every Topic that was already Approved beforehand, ordered so that Topics approved earlier receive lower numbers than Topics approved later, without requiring any manual Organiser action.
- **FR-014**: System MUST display each Topic's author/creator on that Topic's own Details view (the single-Topic page), as one of the first pieces of information on the page — a field that view does not show today.

### Key Entities

- **Topic Upvote**: Represents one user's active support for one Topic. Attributes: the Topic it belongs to, the user who cast it, when it was cast. At most one per (Topic, user) pair at any time; withdrawing it removes the record entirely (a later upvote by the same user on the same Topic is a new, independent cast).
- **Topic** *(existing entity, extended)*: Gains a numeric reference ID (present only once Approved, permanent, globally unique) and a derived upvote count (the number of active Topic Upvote records referencing it).
- **Organiser Settings** *(existing entity, extended)*: Gains a single instance-wide toggle controlling whether the upvoting feature is active.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A user can cast or withdraw an upvote on a Topic in a single action, with the updated count visible immediately without a page reload delay noticeable to the user.
- **SC-002**: Across any set of Topics and users, the recorded upvote count for every Topic always equals the number of users who currently have an active upvote on it — zero discrepancies.
- **SC-003**: No individual upvoter's identity is ever retrievable from any user-facing view, for 100% of Topics and users.
- **SC-004**: An Organiser can turn the upvoting feature on or off instance-wide with a single settings change, and the effect is visible across all Topic listings for all users immediately afterward.
- **SC-005**: 100% of Topics that become Approved (from this point forward, and among those already Approved before the upgrade) end up with exactly one numeric reference ID, with zero duplicates across the entire set of Topics at any time.
- **SC-006**: Immediately after upgrading to the version that introduces this feature, every already-Approved Topic shows a numeric reference ID in the UI with no manual Organiser intervention required.
- **SC-007**: A user viewing a Topic's own Details page can identify who authored it within a couple of seconds, without navigating away to the Topic Overview.
- **SC-008**: Among any set of equally-full Topics on the Home Page, they are always ordered with the highest upvote count first, with zero exceptions while the upvoting feature is enabled.

## Assumptions

- "Visible in the overview" is read broadly: the upvote count is shown on every existing Topic-listing surface (Home Page, Topic Overview) and also on Topic Details, for consistency with how other per-Topic facts (Skills, Compliance) are already surfaced across all three — author specifically is *not* already on Topic Details today, which is exactly the gap User Story 3 closes.
- Any authenticated user may upvote any Topic they can currently see, following the same Topic visibility rules already established (e.g., a still-Pending Topic is only visible to its author and Organisers, and only they could upvote it while it remains Pending).
- Numeric reference IDs are assigned sequentially in approval order and are never reused, changed, or removed once assigned, even if a Topic is later edited.
- The backfill of reference IDs for pre-existing Approved Topics happens automatically as part of the upgrade that ships this feature, ordered by each Topic's original approval time, with no separate Organiser-triggered step.
- Disabling the upvoting feature only hides its UI; it does not delete or otherwise alter previously recorded upvotes, which reappear unchanged when the feature is re-enabled.
- "Author/Creator on top" refers to the single-Topic Details view specifically (`/topics/{id}`), where it is a genuinely new data point — that view shows no author information today, unlike the Topic Overview (all-Topics list), which already has an author column from feature 005 and needs no change here. Placement mirrors the existing Organiser-facing Topic detail page, where "Creator" is already the first row of its info list — the same placement is applied to the participant-facing view for consistency.
