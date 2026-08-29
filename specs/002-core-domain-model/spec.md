# Feature Specification: Core Domain Model & Organiser Management

**Feature Branch**: `002-core-domain-model`

**Created**: 2026-08-23

**Status**: Draft

**Input**: User description: "This tool has backend and UI for a hackathon. This feature is for the general logic. The finetuned logic will be added in further specifications. So no specific UIs or so are added here. There are different types. User - logs in and is identified by an OIDC provider. User can have two logical roles (Standard, Organiser and Participant) Standard is any logged in user, Organiser is determined by a privilege in the database, and participant is someone registered for the hackathon. Participant data has a status (active, not participated, revoked, ...) and custom fields, which are either freetext or multi select. Configured by an organiser. The participant can also select skills. The possible skills are also defined in the database. Besides the user is also a topic existing. The topic has a description, a name and skills. Also the user who created it is linked. Internally every primary key (besides mapping tables) should be a UUID v7. When a team forms for a topic this is defining a group. Add for all of these infos organiser thymeleaf views, which enable viewing and editing of the data. Organiser area is in a separate path and java packages."

## Clarifications

### Session 2026-08-23

- Q: Can an organiser mark a Custom Field as required, so a participant record is incomplete until it's filled in — or are all custom fields always optional? → A: Organiser can mark individual fields as required; a participant record can be flagged incomplete until all required fields are filled in.
- Q: Can a single User have more than one Participant record, or should the system enforce at most one Participant registration per User? → A: At most one — the system enforces a single Participant record per User.
- Q: Must Skill names be unique within the catalog, or can an organiser create two Skills with the same name? → A: Unique — the system rejects creating a Skill whose name already exists in the catalog.
- Q: Can more than one Group/team form around the same Topic, or is a Topic limited to a single Group? → A: One Group per Topic — a Topic is restricted to at most one Group at a time.
- Q: What triggers a User becoming a Participant? → A: Registration — a User becomes a Participant as the direct result of a registration action creating their Participant record.
- Q: Can an Organiser disband/remove an existing Group, so that its Topic becomes available for a new Group to form? → A: Yes — an Organiser can disband a Group, freeing its Topic for a new Group to be formed later; the disbanded Group's historical record remains viewable, consistent with how the rest of this spec preserves historical data.
- Q: If an Organiser changes a Custom Field's type (free-text to multi-select, or vice versa) after participants already have values for it, what should happen to those existing values? → A: Block the type change — an Organiser cannot change a Custom Field's type once any Participant has a value for it; a new field must be created instead.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Identity & Role Recognition (Priority: P1)

A person authenticates through the organisation's external identity provider. The system recognises them as a known user on every login and immediately treats them as holding the Standard role, without any manual setup step. Separately, an existing Organiser can grant or revoke the Organiser privilege for any user.

**Why this priority**: Every other capability in this feature — including access to the organiser views themselves — depends on the system being able to identify a logged-in person and know whether they are an Organiser. Without this, nothing else can be safely built or tested.

**Independent Test**: Can be fully tested by having a new person log in via the identity provider and confirming a corresponding record now exists and is treated as Standard; then having an Organiser flip the Organiser privilege for that record and confirming the change takes effect on the next access check.

**Acceptance Scenarios**:

1. **Given** a person has never logged in before, **When** they authenticate successfully via the identity provider, **Then** the system recognises them as a known user and grants them the Standard role automatically.
2. **Given** a known user without the Organiser privilege, **When** an existing Organiser grants them that privilege through the organiser views, **Then** the user is subsequently treated as an Organiser.
3. **Given** a known user with the Organiser privilege, **When** an existing Organiser revokes it, **Then** the user is subsequently denied access to organiser-only capabilities.

---

### User Story 2 - Organiser Configures Skills & Custom Fields (Priority: P1)

An Organiser defines the catalog of Skills that can be attached to participants and topics, and defines the set of Custom Fields (free-text or multi-select) that participant records will capture, all through dedicated organiser views.

**Why this priority**: Participant records and topics cannot meaningfully be filled in or reviewed until the Skill catalog and Custom Field definitions exist. This is a prerequisite for the participant-management and topic-management stories, but is independently useful and testable on its own.

**Independent Test**: Can be fully tested by having an Organiser create a new Skill and a new Custom Field definition (one free-text, one multi-select with options) through the organiser views and confirming both are persisted and appear in the respective catalogs.

**Acceptance Scenarios**:

1. **Given** an Organiser is on the Skill management view, **When** they add a new Skill with a name, **Then** the Skill becomes available for selection on participants and topics.
2. **Given** an Organiser is on the Custom Field management view, **When** they define a new field as free-text, **Then** that field becomes available to capture a free-text value on participant records.
3. **Given** an Organiser is on the Custom Field management view, **When** they define a new field as multi-select and provide its selectable options, **Then** that field becomes available on participant records restricted to those options.
4. **Given** an Organiser edits an existing Skill's or Custom Field's name/label, **When** they save the change, **Then** the updated name/label is reflected everywhere it is referenced.
5. **Given** a Custom Field that already has at least one Participant value recorded, **When** an Organiser attempts to change its type between free-text and multi-select, **Then** the system blocks the change and indicates a new field must be created instead.

---

### User Story 3 - Organiser Manages Participant Records (Priority: P2)

An Organiser views the roster of participants and, for any individual, reviews and edits their status, their custom field values, and their selected skills, all through the organiser views.

**Why this priority**: This is the primary day-to-day data-management need for running a hackathon roster, but it depends on Story 1 (roles) and Story 2 (skill/field catalogs) already being in place.

**Independent Test**: Can be fully tested by creating a participant record for a known user through the organiser views, setting its status, assigning skills, and filling in custom field values, then confirming all of it is retrievable and editable afterward.

**Acceptance Scenarios**:

1. **Given** an Organiser is on the participant list view, **When** they open a specific participant, **Then** they see that participant's current status, custom field values, and selected skills.
2. **Given** an Organiser is viewing a participant, **When** they change the status, **Then** the new status is saved and reflected on the participant list.
3. **Given** an Organiser is viewing a participant, **When** they add or remove a skill selection, **Then** the change is saved and reflected on future views of that participant.
4. **Given** an Organiser is viewing a participant, **When** they edit a custom field value, **Then** the value is saved and validated against that field's configured type (free-text vs. one of the defined multi-select options).

---

### User Story 4 - Topics with Skills and Creator (Priority: P2)

The system records topics, each with a name, description, an associated set of skills, and a link to the user who created it. An Organiser can view and edit any topic through the organiser views.

**Why this priority**: Topics are a distinct piece of hackathon data (challenges participants can work on) that must exist and be manageable before team formation (Story 5) is meaningful.

**Independent Test**: Can be fully tested by creating a topic record with a name, description, creator, and one or more skills through the organiser views, and confirming it is retrievable and editable afterward.

**Acceptance Scenarios**:

1. **Given** an Organiser is on the topic list view, **When** they open a specific topic, **Then** they see its name, description, associated skills, and the user who created it.
2. **Given** an Organiser is viewing a topic, **When** they edit its name, description, or skill associations, **Then** the changes are saved and reflected on future views.
3. **Given** an Organiser creates a new topic through the organiser views, **When** they select the creating user and save, **Then** the topic is persisted with that user recorded as its creator.

---

### User Story 5 - Groups Formed Around a Topic (Priority: P3)

When a team forms to work on a topic, the system represents this as a Group tied to that topic, with its participant members. Each topic can have at most one group formed around it. An Organiser can view and edit groups, including their topic association and membership, through the organiser views.

**Why this priority**: Group/team tracking builds on both participants (Story 3) and topics (Story 4) already existing, and is the final piece of the core domain model for this feature.

**Independent Test**: Can be fully tested by creating a group linked to an existing topic, adding existing participants as members through the organiser views, and confirming the group's topic and membership are retrievable and editable afterward.

**Acceptance Scenarios**:

1. **Given** an Organiser is on the group list view, **When** they open a specific group, **Then** they see its associated topic and its participant members.
2. **Given** an Organiser is viewing a group, **When** they add or remove a participant member, **Then** the change is saved and reflected on future views.
3. **Given** an Organiser creates a new group through the organiser views, **When** they select its topic and save, **Then** the group is persisted associated with that topic.
4. **Given** a Topic that already has a Group, **When** an Organiser attempts to create a second Group for that Topic, **Then** the system rejects the attempt and indicates the Topic already has a Group.
5. **Given** an Organiser is viewing a Group, **When** they disband it, **Then** the Group's historical record (including its former members) remains viewable and its Topic becomes eligible for a new Group.

---

### Edge Cases

- What happens when a user's Organiser privilege is revoked while they are actively using the organiser views? Subsequent requests MUST be denied even if their current session was already in progress.
- What happens when an Organiser tries to remove a Skill or Custom Field definition that is still referenced by existing participants or topics? The removal MUST be blocked with a message telling the Organiser which references must be cleared first (FR-023).
- What happens when a participant's status is changed to a terminal state (e.g., revoked)? Their existing custom field values, skill selections, and group memberships MUST remain visible for historical record-keeping rather than being deleted.
- How does the system reconcile a returning user whose identity-provider profile details (e.g., display name, email) have changed since their last login? The system MUST match on the identity provider's stable subject identifier, not on mutable profile attributes, and MUST refresh the stored profile details from the latest login.
- What happens if the user recorded as a topic's creator later has their access revoked? The topic MUST retain the historical creator reference regardless of that user's current access level.
- What happens when an Organiser tries to open the organiser views without holding the Organiser privilege? Access MUST be denied.
- What happens when an Organiser tries to create a second Group for a Topic that already has one? The creation MUST be rejected with a message indicating the Topic already has a Group (FR-016a).
- What happens when an Organiser disbands a Group? Its Topic MUST become eligible for a new Group to form, and the disbanded Group's historical record MUST remain viewable rather than being deleted (FR-016b).
- What happens when an Organiser tries to change a Custom Field's type after participants already have values for it? The change MUST be blocked; the Organiser must create a new field instead (FR-012a).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST authenticate all users exclusively via an external OIDC identity provider; the system MUST NOT maintain a separate local username/password credential store.
- **FR-002**: System MUST automatically create a corresponding user record the first time a person authenticates successfully, linked to the identity provider's stable subject identifier.
- **FR-003**: System MUST treat every authenticated user as holding the Standard role implicitly, with no separate assignment step required.
- **FR-004**: System MUST allow an Organiser to grant or revoke the Organiser privilege for any user record via the organiser views.
- **FR-005**: System MUST derive the Organiser role solely from the database-stored Organiser privilege on the user's record.
- **FR-006**: System MUST treat the Participant role as present for a user only when a Participant record exists for them, independent of and combinable with their Standard/Organiser status.
- **FR-006a**: System MUST enforce that a User has at most one Participant record at any time.
- **FR-006b**: System MUST create a Participant record for a User only as the result of an explicit registration action, and MUST set that Participant's initial status to Active upon registration.
- **FR-007**: System MUST restrict every Participant record to exactly one status value at any given time, drawn from the set: Active, Not Participated, Revoked.
- **FR-008**: System MUST allow an Organiser to create, edit, and remove Skill definitions (each with a name) via the organiser views.
- **FR-008a**: System MUST reject creating or renaming a Skill to a name that already exists in the catalog.
- **FR-009**: System MUST allow a Participant record to be associated with zero or more Skills drawn from the organiser-defined Skill catalog.
- **FR-010**: System MUST allow a Topic to be associated with zero or more Skills drawn from the same organiser-defined Skill catalog used for participants.
- **FR-011**: System MUST allow an Organiser to create, edit, and remove Custom Field definitions via the organiser views, each configured as either free-text or multi-select.
- **FR-012**: For a multi-select Custom Field definition, the system MUST require the Organiser to define its set of selectable options.
- **FR-012a**: System MUST block an Organiser from changing a Custom Field definition's type (free-text vs. multi-select) once any Participant already has a value recorded for it.
- **FR-012b**: System MUST block an Organiser from removing a selectable option from a multi-select Custom Field while any Participant's recorded value still references that option, consistent with FR-023.
- **FR-013**: System MUST record, for every Participant, a value for each applicable Custom Field, consistent with that field's configured type (free text, or one/more of its defined options).
- **FR-014**: System MUST reject a Custom Field value that does not conform to its field's configured type (e.g., a selection not present among a multi-select field's defined options).
- **FR-015**: System MUST record, for every Topic, a name, a description, its associated Skills, and the user who created it.
- **FR-016**: System MUST represent a formed team as a Group associated with exactly one Topic.
- **FR-016a**: System MUST restrict each Topic to at most one Group at a time; an attempt to create a second Group for a Topic that already has one MUST be rejected.
- **FR-016b**: System MUST allow an Organiser to disband an existing Group via the organiser views, after which its Topic MUST again be eligible to have a new Group formed. The disbanded Group's historical record, including its former members, MUST remain viewable.
- **FR-017**: System MUST record, for every Group, its participant members, and MUST restrict each Participant to membership in at most one active Group at a time.
- **FR-018**: System MUST provide organiser-only web views to list, view, and edit user records, including toggling the Organiser privilege.
- **FR-019**: System MUST provide organiser-only web views to list, view, create, and edit Participant records, including their status, Skill selections, and Custom Field values.
- **FR-020**: System MUST provide organiser-only web views to list, view, create, edit, and remove Skill and Custom Field definitions.
- **FR-021**: System MUST provide organiser-only web views to list, view, create, and edit Topics and Groups, including disbanding a Group (FR-016b).
- **FR-022**: System MUST restrict access to every organiser view and its underlying edit actions to users holding the Organiser privilege, denying access to all other users.
- **FR-023**: When an Organiser attempts to remove a Skill or Custom Field definition that is still referenced by existing Participant or Topic records, the system MUST block the removal and inform the Organiser that existing references must be cleared first.
- **FR-024**: System MUST organise all organiser-facing views and their supporting code under a distinct path and package, separate from the rest of the application.
- **FR-025**: System MUST assign a UUID v7 as the primary identifier for every core entity record (User, Participant, Skill, Custom Field Definition, Topic, Group), excluding pure mapping/association tables.
- **FR-026**: System MUST allow an Organiser to mark any Custom Field definition as required or optional.
- **FR-027**: System MUST treat a Participant record as incomplete while any required Custom Field lacks a value, and MUST make this incomplete status visible to the Organiser in the organiser views.

### Key Entities

- **User**: A person recognised by the system after authenticating via the external identity provider. Holds a stable link to that provider's identity, profile details (e.g., display name), and the Organiser privilege flag. Implicitly holds the Standard role; may separately have a linked Participant record and may have created Topics.
- **Participant**: A User's registration record for the hackathon, created when that User registers (FR-006b). Holds a status (initially Active), a set of Skill selections, and a set of Custom Field values. Belongs to exactly one User, and a User has at most one Participant record (FR-006a). Belongs to at most one active Group at a time (FR-017).
- **Skill**: An organiser-defined, reusable capability or tag, identified by a unique name (FR-008a). Referenced by Participants and Topics.
- **Custom Field Definition**: An organiser-defined data field that Participant records capture, configured as free-text or multi-select and as required or optional; multi-select definitions also hold their set of selectable options. Its type is fixed once any Participant has a value for it (FR-012a).
- **Custom Field Value**: A single Participant's recorded answer to one Custom Field Definition, consistent with that field's configured type.
- **Topic**: A hackathon challenge/idea, with a name, description, associated Skills, and a link to the User who created it. May have at most one Group formed around it (FR-016a).
- **Group**: A formed team working on a Topic. Associated with exactly one Topic (with at most one Group per Topic, FR-016a) and one or more Participant members. Can be disbanded by an Organiser, freeing its Topic for a new Group; its historical record remains viewable after disbanding (FR-016b).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A new person is recognised as a system user and granted the Standard role immediately upon their first successful login, with no manual provisioning step by an Organiser.
- **SC-002**: An Organiser can locate any participant, topic, or group and view its full current data (status/skills/fields, or topic/group details) within the organiser views without needing direct database access.
- **SC-003**: An Organiser can define a new Skill or Custom Field and have it immediately available for selection on participant or topic records, without requiring a code change or deployment.
- **SC-004**: 100% of attempts by non-Organiser users to reach an organiser view or perform an organiser-only edit action are denied.
- **SC-005**: An Organiser can update a participant's status, skills, or custom field values and see the update reflected the next time that participant's record is viewed, with no data loss of the participant's other fields.
- **SC-006**: Every core entity record created by the system carries a unique, chronologically-ordered identifier, with no identifier collisions across records.
- **SC-007**: An Organiser can identify, from the participant list view, any participant with unmet required custom fields without opening each record individually.

## Assumptions

- Only organiser-facing views are delivered in this feature. Self-service registration by participants, self-service topic creation, and any participant-facing team-formation workflow are explicitly out of scope here and are expected in future specifications ("finetuned logic"); until then, the registration action that creates a Participant record (FR-006b), along with topic and group creation, is performed by an Organiser on the user's behalf through the organiser views.
- A single, flat Organiser privilege grants full access to all organiser views described in this feature; there are no finer-grained organiser sub-roles in this feature's scope.
- The system operates in the context of a single ongoing hackathon; support for running multiple concurrent or historical hackathon instances is out of scope for this feature.
- "Mapping tables" refers to pure join/association tables (e.g., Participant–Skill, Topic–Skill, Group–Participant membership); these are explicitly excluded from the UUID v7 primary-key requirement and may use composite or surrogate keys as appropriate.
- The organiser views in this feature provide only baseline create/view/edit management of the data described; hackathon-specific workflow logic (approvals, notifications, automated status transitions, etc.) is deferred to future specifications.
- The identity provider issues a stable, unique subject identifier per person that the system uses to link a User record across logins; display name and other profile attributes may be refreshed from the identity provider on each login.
