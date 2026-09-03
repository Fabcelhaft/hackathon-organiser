# Feature Specification: Audit Trail for Topics and Participants

**Feature Branch**: `006-audit-tables-each`

**Created**: 2026-09-03

**Status**: Draft

**Input**: User description: "Audit tables. each topic, group and participant has an audit, registering changes which are done by a user or an organiser. Organisers can see it when watching an entry. One shared audit table, with the information what happened (e.g. registered for a topic), who did it (User ID) what objects are affected. In case of topic joining it creates an audit entry for the user and one for the particpant. Audit table also has the type and if it was an organiser or a normal user (depending on the view). The audit events are not visible to non organisers. To only load them when needed, add an audit button to the detail view, loading audit for a certain element. only reachable by an organiser."

## Clarifications

### Session 2026-09-03

- Q: Should an audit entry for an edit record the specific old and new values of the changed field(s), or only that an edit of a given type happened? → A: Hybrid — field-level before/after values are recorded only for high-stakes fields (Participant status, Group/Topic membership changes); all other edits (e.g., Topic name/description, Custom Field values) record only the event type and affected record, without field-level values.
- Q: Should the two linked audit entries from a Topic join (Topic/Group side and Participant side) explicitly reference each other via a shared action identifier, or is independent correct attribution enough with no stored link? → A: Shared action identifier — both entries store a common identifier tying them to the same join action, so the pairing is verifiable rather than merely inferred.
- Q: When an Organiser opens a record's Audit trail, should the system show its complete unbounded history in one view, or a bounded/paginated list? → A: Complete unbounded history — every recorded entry for that record is shown in one view.
- Q: Should a Group have its own audit trail separate from its Topic? → A: No — only Topics and Participants have an audit trail. Every event that affects a Group (formation, membership changes, disbanding, compliance override) is recorded as an entry on that Group's Topic's audit trail instead, since a Group always belongs to exactly one Topic. The Group detail view keeps its "Audit" action, but it opens that Topic's audit trail rather than a separate Group-scoped one.
- Q: Should an Organiser directly adding/removing a Participant from a Group be recorded differently than a Participant joining/leaving a Topic themselves? → A: No — both cases MUST be handled identically: whichever of the two ways a membership change happens (self-service join/leave, or an Organiser directly adding/removing a member), the system records the same two linked entries (Topic side and Participant side, sharing an action identifier). Only the recorded actor and capacity differ.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Every Change to a Topic or Participant Is Recorded (Priority: P1)

Whenever a Topic or Participant record is created, edited, or has its state changed — whether the change was made by the record's own user or by an Organiser — the system silently records who made the change, what kind of change it was, and which record was affected. This includes changes to a Topic's Group (forming, growing, shrinking, or disbanding it): a Group has no audit trail of its own, so anything that happens to one is recorded as an entry on its Topic's audit trail instead.

**Why this priority**: This is the foundation the rest of the feature depends on. Without a reliable, complete record of changes, there is nothing for an Organiser to review later. This can be built and verified entirely on its own, independent of any viewing UI.

**Independent Test**: Can be fully tested by performing a variety of changes (e.g., editing a Topic's description, changing a Participant's status, disbanding a Topic's Group) and confirming a corresponding audit entry exists for each, correctly identifying the actor, the action, and the affected Topic or Participant.

**Acceptance Scenarios**:

1. **Given** a Participant edits their own Topic, **When** the edit is saved, **Then** an audit entry is recorded identifying that Participant's underlying User as the actor, acting in a standard-user capacity, describing the edit, and referencing that Topic.
2. **Given** an Organiser edits a Participant's status, **When** the edit is saved, **Then** an audit entry is recorded identifying the Organiser as the actor, acting in the Organiser capacity, describing the status change, and referencing that Participant.
3. **Given** an Organiser disbands a Topic's Group, **When** the disband completes, **Then** an audit entry is recorded on that Topic's audit trail describing the disband event — not on any separate Group-scoped trail, since Groups do not have one.
4. **Given** an audit entry has been recorded, **When** any user or Organiser later attempts to modify or delete it, **Then** the system prevents the change; audit entries are permanent once written.

---

### User Story 2 - Organiser Reviews an Entity's Audit Trail On Demand (Priority: P1)

An Organiser, while viewing the detail page of a specific Topic, Group, or Participant, clicks an "Audit" action to load and review the full history of changes recorded against that Topic or Participant — who did what, when, and in what capacity — without that history being fetched or shown until requested. Since a Group has no audit trail of its own, clicking "Audit" on a Group's detail page opens its Topic's audit trail instead of a separate one.

**Why this priority**: This is how the recorded history (Story 1) actually becomes useful to an Organiser running the hackathon. It delivers the visible value of the feature and is independently testable once Story 1's recording exists.

**Independent Test**: Can be fully tested by an Organiser opening a Topic's, a Group's, or a Participant's detail page, confirming no audit data has loaded yet, clicking the "Audit" action, and confirming the relevant history then appears — the Topic's own history for a Topic or Group detail page, or the Participant's for a Participant detail page — matching entries recorded in Story 1.

**Acceptance Scenarios**:

1. **Given** an Organiser is viewing a Topic's, Group's, or Participant's detail page, **When** the page first loads, **Then** no audit data is fetched or displayed.
2. **Given** an Organiser is viewing a Topic's or a Participant's detail page, **When** they click the "Audit" action, **Then** the system loads and displays that record's own audit entries, each showing what happened, who did it, and whether the actor was acting as an Organiser or as a standard user.
3. **Given** an Organiser is viewing a Group's detail page, **When** they click the "Audit" action, **Then** the system loads and displays that Group's Topic's audit entries — including any entries describing changes to the Group itself — not a separate Group-scoped history.
4. **Given** a standard user (not an Organiser) is viewing a Topic's, Group's, or Participant's detail page, **When** the page renders, **Then** no "Audit" action is shown to them.
5. **Given** a standard user attempts to reach the audit-loading mechanism directly (e.g., without using the "Audit" button), **When** the request is processed, **Then** the system denies it regardless of how it was reached.

---

### User Story 3 - Topic Membership Changes Are Recorded on Both Sides, However They Happen (Priority: P2)

Whenever a Participant's membership in a Topic's Group changes — a Participant joining or leaving it themselves, or an Organiser directly adding or removing that Participant — the system records the event on both the Topic side and the Participant side, so an Organiser reviewing either the Topic or the Participant independently sees the membership change, without needing to cross-reference the other record. The same two-linked-entries treatment applies uniformly regardless of which of these ways the membership change happened; only the recorded actor and capacity (standard user vs. Organiser) differ.

**Why this priority**: This is a specific, named case from the request that exercises the "what objects are affected" behavior of Story 1 across two related records in a single action. It depends on Stories 1 and 2 already existing.

**Independent Test**: Can be fully tested by having a Participant join an open Topic, then opening the Topic's audit trail and confirming a "joined" entry appears referencing that Participant, and separately opening the Participant's own audit trail and confirming a matching "joined" entry appears referencing that Topic — then repeating the same check after an Organiser directly adds a different Participant to that Topic's Group instead, confirming the same paired-entry shape results.

**Acceptance Scenarios**:

1. **Given** a Participant joins a Topic with no existing Group, **When** the join completes, **Then** an audit entry appears on the Topic's audit trail recording that the Participant joined (the Group formed as a side effect of the join has no audit trail of its own), and a separate, linked audit entry appears on that Participant's own audit trail recording that they joined that Topic.
2. **Given** a Participant joins a Topic whose Group already has members, **When** the join completes, **Then** the same paired audit entries are recorded as in Scenario 1.
3. **Given** a Participant leaves a Topic they had joined, **When** the leave completes, **Then** the same paired audit entries are recorded as Scenario 1, describing a "left" event instead of "joined."
4. **Given** an Organiser directly adds a Participant to a Topic's Group through the organiser view, **When** the addition is saved, **Then** the same paired audit entries are recorded as Scenario 1, except the actor is the Organiser, acting in the Organiser capacity.
5. **Given** an Organiser directly removes a Participant from a Topic's Group through the organiser view, **When** the removal is saved, **Then** the same paired audit entries are recorded as Scenario 3, except the actor is the Organiser, acting in the Organiser capacity.
6. **Given** an Organiser reviews only the Participant's audit trail, **When** they look at a "joined"/"left" entry, **Then** they can identify which Topic was involved without needing to open the Topic separately, regardless of whether a Participant or an Organiser caused the change.

---

### Edge Cases

- What happens when an Organiser performs an action that a Participant would normally perform themselves (e.g., an Organiser joins a Participant to a Topic on the Participant's behalf)? The recorded entry MUST reflect the Organiser as the actor and record that it was done in the Organiser capacity, not as the affected Participant acting for themselves.
- What happens to a Topic's or Participant's audit trail when the record itself is later disbanded (its Group), revoked, or otherwise deactivated? The audit trail MUST remain fully retrievable by an Organiser, consistent with how the rest of the system preserves historical data.
- What happens when an Organiser opens the audit trail for a Topic or Participant that has no recorded changes yet? The system MUST show an empty, clearly-labeled history rather than an error. The same applies when opening a Group's Audit action for a Topic that has none yet.
- What happens if a standard user's session is somehow used to call the audit-retrieval mechanism directly (bypassing the hidden button)? The request MUST be denied server-side; hiding the button in the interface is not sufficient on its own.
- What happens when the same action affects more than one record (e.g., joining a Topic)? Each affected record MUST receive its own audit entry, and those entries MUST be identifiable as belonging to the same underlying action.
- What happens when an Organiser, rather than the Participant themselves, directly adds or removes that Participant from a Topic's Group? The same paired Topic-side/Participant-side entries MUST be recorded as if the Participant had joined/left themselves — only the actor and capacity differ; there MUST be no separate, lesser form of recording for organiser-initiated membership changes.
- What happens when an Organiser clicks "Audit" on a Group's detail page? They are shown that Group's Topic's audit trail, not a separate Group-scoped one, since Groups do not have an audit trail of their own.
- What happens when a Topic's Group is disbanded and a new Group later forms for the same Topic? Both Groups' events remain on the same Topic's single audit trail — an Organiser does not need to know which specific Group a historical entry belonged to in order to find it.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST record an audit entry whenever a Topic or Participant record is created, edited, or undergoes a state change (including but not limited to status changes, joining, and leaving), and whenever a Topic's Group is formed, grown, shrunk, or disbanded — Group events are recorded against that Group's Topic, since a Group does not have an audit trail of its own.
- **FR-002**: Each audit entry MUST capture, at minimum: the type of event that occurred, the identity of the acting User, whether that User was acting in the Organiser capacity or as a standard user/participant at the time of the action, a timestamp, and a reference to the specific Topic or Participant affected.
- **FR-002a**: For a change to a Participant's status or to a Topic's Group membership (joining or leaving), the audit entry MUST additionally record the specific old and new values. For all other edits (e.g., Topic name/description, Custom Field values), the audit entry MUST record only the event type and affected record, without field-level before/after values.
- **FR-003**: System MUST store all audit entries in a single, shared audit log, from which the entries relevant to any specific Topic or Participant can be retrieved. Retrieving a Group's history means retrieving its Topic's history — there is no separate, Group-scoped subset to retrieve.
- **FR-004**: Whenever a Participant's membership in a Topic's Group changes — the Participant joining or leaving it themselves, or an Organiser directly adding or removing that Participant through the organiser view — System MUST record two linked audit entries as part of that single action: one referencing the Topic, and one referencing the Participant. This treatment MUST be identical regardless of which of the two ways the membership change happened; only the recorded actor and capacity differ.
- **FR-004a**: The two audit entries created by a single membership-change action (join, leave, or an Organiser's direct add/remove) MUST share a common action identifier, so their pairing is verifiable rather than merely inferred from event type and timing.
- **FR-005**: System MUST restrict all access to audit entries — for any Topic or Participant — to users holding the Organiser privilege; standard users and participants MUST NOT be able to view audit data, including audit data about themselves.
- **FR-006**: System MUST enforce the restriction in FR-005 at the point audit data is retrieved, not only by hiding the interface element that requests it.
- **FR-007**: System MUST NOT load or transmit any audit data for a Topic, Group, or Participant detail page until an Organiser explicitly requests it.
- **FR-008**: System MUST provide an "Audit" action on the Topic, Group, and Participant detail views, visible only to Organisers. For a Topic or Participant detail view, it loads that record's own audit entries on demand. For a Group detail view, it loads its Topic's audit entries on demand instead, since a Group has no audit trail of its own.
- **FR-009**: System MUST preserve every recorded audit entry even after the Topic or Participant it references is later revoked or otherwise deactivated, and even after a Topic's Group is disbanded.
- **FR-010**: System MUST treat recorded audit entries as immutable; no user or Organiser may edit or delete an existing entry.
- **FR-011**: System MUST display an entity's audit entries in chronological order, most recent first.
- **FR-011a**: System MUST display a record's complete audit history in a single view, unbounded and unpaginated, when the "Audit" action is used.

### Key Entities

- **Audit Entry**: A single recorded change. Represents what happened (event type, e.g., "created," "edited," "status changed," "joined," "left," "disbanded"), who performed it (a reference to the acting User), the capacity they were acting in (Organiser or standard user), when it happened, which record it affects (a reference to a Topic or a Participant — never a Group directly), and, for high-stakes changes (Participant status, Topic/Group membership), the old and new value. An entry created alongside a paired entry from the same action (e.g., a Topic join) also carries a shared action identifier linking the two.
- **Topic, Participant** *(existing entities, referenced here)*: The record types that own an audit trail; each entry references exactly one of them.
- **Group** *(existing entity, referenced here)*: Does not own an audit trail of its own. Every event that affects a Group — formation, membership changes, disbanding, compliance override — is recorded as an entry on that Group's Topic instead, since a Group always belongs to exactly one Topic.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An Organiser can retrieve the complete, correctly-ordered change history for any Topic or Participant — including, via a Group's detail page, its Topic's history — within a few seconds of requesting it.
- **SC-002**: Every creation, edit, status change, join, leave, and disband action performed against a Topic, Group, or Participant during testing produces a matching, correctly-attributed audit entry (filed under the Topic for any Group-level action) — a 100% capture rate.
- **SC-003**: In testing, zero audit data is ever exposed to a non-Organiser, whether through the standard interface or a direct attempt to reach the retrieval mechanism.
- **SC-004**: Standard users experience no added load time on Topic, Group, or Participant detail pages as a result of this feature, since audit data is never fetched for them.
- **SC-005**: Any Topic membership change — a Participant joining or leaving, or an Organiser directly adding or removing one — produces both of its linked audit entries (Topic side and Participant side) atomically — testers never observe one entry recorded without the other, regardless of which of the two ways the change happened.
- **SC-006**: Clicking "Audit" on a Group's detail page always shows exactly the same history as clicking "Audit" on its Topic's detail page — testers never observe the two diverge.

## Assumptions

- Only Topic and Participant own an audit trail; Group does not, since it always belongs to exactly one Topic and its events are recorded there instead. The underlying User/identity record itself is not separately audited beyond being referenced as the actor on other entities' entries.
- "For the user" in the request's description of the topic-join case is interpreted as the audit entry recorded against the Topic — the record the acting user interacted with — since User is not itself an audited entity type; the second, linked entry is recorded against the Participant. This same Topic-side/Participant-side pairing is also what an Organiser's direct add/remove of a Participant produces (FR-004), not a scaled-down, single-entry version.
- Audit entries are retained indefinitely, consistent with how the rest of the system already preserves historical records (e.g., disbanded Groups remain viewable).
- "Watching an entry," per the request, refers to an Organiser viewing a Topic's, Group's, or Participant's existing detail page; the "Audit" action is added to those existing pages rather than a new page being introduced. On a Group's detail page, that action opens its Topic's audit trail.
- The distinction between "Organiser" and "normal user" recorded on each entry reflects the capacity the actor was in at the moment the action was performed (e.g., an Organiser editing another Participant's record on their behalf is recorded as an Organiser action), not the actor's general set of privileges.
- If a Topic's Group is disbanded and a later Group forms for the same Topic, both Groups' events accumulate on that one Topic's single audit trail; the spec does not require distinguishing which specific Group (the earlier, disbanded one vs. a later one) a historical entry belongs to.
