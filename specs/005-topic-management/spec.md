# Feature Specification: Topic Management, Group Formation & Compliance

**Feature Branch**: `005-topic-management`

**Created**: 2026-08-29

**Status**: Draft

**Input**: User description: "Topic Management. As described before anyone can propose a topic and select skills and a description. Home page shows up to 10 topics which are not full yet. sorted by fullness (fullest first). Home page has 3 columns in the topic table: Topic Name, Participant count in the group (group is created with first participant), skills needed, which are offered by the user. Admin setting to show only still needed skills or map against all. In the menu is a topic overview added. Following columns: Topic Name, author, participant count, skills, compliance. Compliance is defined by the settings of the organiser. Compliance rules are Minimal group members, Maximum group members (once maximum reached, no more group members are added), Two different custom field values in the group (including countries). This can be combined with AND logic, for example: a group can have 2 to 5 participants, participants come from two countries and from two departments (custom field); this kind of rules is configured per instance. Compliance check can also only consist out of minimum and maximum members. Organiser can mark a group as compliant, and add more users, although maximum is reached."

## Clarifications

### Session 2026-08-29

- Q: How does a Participant join a Topic's Group? → A: A self-service "Join" action any eligible Participant can trigger directly, which creates the Group on the first join and grows it on subsequent joins, subject to capacity/compliance rules.
- Q: Is the "two different custom field values" diversity rule always exactly 2 distinct values, or can an Organiser set the required minimum per field? → A: Organiser-configurable per field — each Custom Field diversity requirement carries its own minimum required distinct-value count (at least 2), settable independently of other requirements.
- Q: What determines whether a Topic is "full" (for the Home Page's filtering/sorting) when the instance-wide Maximum Group Members compliance rule is not configured? → A: A Maximum Group Members value MUST always be configured — there is no "unlimited" mode; System seeds a default value on first startup so this is never left unset.
- Q: Should this feature's new UI (Join action, Topic Skill picker, Topic Overview table, compliance settings screens, Skill Display Mode toggle) meet the same WCAG 2.1 AA bar that feature 003 established for its own new UI? → A: Yes — apply the same WCAG 2.1 AA requirements (keyboard operability, live regions, labeled controls, contrast, focus indicators) to all UI this feature introduces, plus an automated-scan success criterion, mirroring 003's FR-030–038/SC-009.
- Q: If an Organiser lowers the Maximum Group Members value below a Group's current member count, should the system leave that Group's existing membership untouched or actively remove members down to the new maximum? → A: Leave existing members untouched — the Group becomes "Not Compliant" (over capacity) and stays blocked from new joins until membership drops or an Organiser override is set; no member is ever removed as a side effect of a settings change.
- Q: Does the "Join Topic" self-service action require an explicit confirmation step before it takes effect, or does it join immediately on a single click? → A: Immediate, single-click — joining takes effect right away with a success confirmation shown afterward, mirroring 003's "Register" action; no confirmation dialog.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Propose a Topic with Skills (Priority: P1)

Any registered Participant proposes a new Topic by giving it a name, a description, and selecting one or more Skills from the organiser-defined Skill catalog that the Topic needs. The Topic's author can later edit its name, description, and Skill selections.

**Why this priority**: Skills on a Topic are the foundation every other capability in this feature depends on — the Home Page discovery table, the Topic Overview page, and skill-based matching for viewers all read the Topic's Skill list. Without it, nothing else in this feature has data to display.

**Independent Test**: Can be fully tested by a Participant proposing a Topic with a name, description, and two or more Skills, confirming all of it is saved, then editing the Skill selection and confirming the change persists.

**Acceptance Scenarios**:

1. **Given** an authenticated Participant is proposing a new Topic, **When** they provide a name, a description, and select one or more Skills from the catalog, **Then** the Topic is created with those Skills attached and the Participant recorded as its author.
2. **Given** a Participant viewing a Topic they authored, **When** they add or remove a Skill selection and save, **Then** the updated Skill list is reflected everywhere the Topic is shown.
3. **Given** a Participant proposes a Topic with no Skills selected, **When** they save, **Then** the Topic is created successfully with an empty Skill list (Skill selection is not mandatory).

---

### User Story 2 - Discover Open Topics on the Home Page (Priority: P1)

Any authenticated user visiting the Home Page sees a table of up to 10 Topics that are not yet full, sorted with the fullest Topic first, so they can quickly find a Topic that both needs members and matches their own Skills.

**Why this priority**: This is the primary discovery surface described in the request — it is the first thing a prospective joiner sees, and it depends only on Topics/Skills (Story 1) already existing.

**Independent Test**: Can be fully tested by creating several Topics with varying member counts, visiting the Home Page, and confirming exactly the not-full Topics appear, capped at 10, ordered fullest-first.

**Acceptance Scenarios**:

1. **Given** more than 10 not-full Topics exist, **When** a user visits the Home Page, **Then** exactly 10 are shown, being the 10 fullest not-full Topics.
2. **Given** a set of not-full Topics with different current participant counts, **When** the Home Page table renders, **Then** they are ordered with the highest participant count first.
3. **Given** a Topic has reached the configured maximum, **When** the Home Page table renders, **Then** that Topic does not appear in it.
4. **Given** the Home Page table is shown, **When** a user reads a row, **Then** they see the Topic Name, the current participant count of its Group, and the subset of the Topic's needed Skills that the viewing user personally offers.
5. **Given** a viewing user offers none of a listed Topic's needed Skills, **When** the Home Page table renders, **Then** that Topic still appears in the table with an empty Skills-offered cell.

---

### User Story 3 - Join a Topic to Form or Grow a Group (Priority: P1)

A registered Participant who is not currently a member of any Group joins an open Topic. If the Topic has no Group yet, joining creates one with that Participant as its first member; if a Group already exists for the Topic, joining adds the Participant to it, subject to the instance's compliance rules.

**Why this priority**: This is the mechanism that makes the participant counts shown in Stories 2 and 5 meaningful at all — without a way to join, "fullness" and "compliance" have nothing to measure.

**Independent Test**: Can be fully tested by a Participant joining a Topic with no existing Group and confirming a Group is created with them as its sole member, then having a second Participant join the same Topic and confirming the Group's member count increases.

**Acceptance Scenarios**:

1. **Given** a Topic with no Group yet, **When** a Participant who belongs to no active Group joins it, **Then** a new Group is created for that Topic with the Participant as its only member.
2. **Given** a Topic whose Group already has members and has not reached the configured maximum, **When** another eligible Participant joins, **Then** they are added to the existing Group and the participant count increases by one.
3. **Given** a Topic whose Group has reached the configured Maximum Group Members and is not marked as an Organiser compliance override, **When** a Participant attempts to join, **Then** the system rejects the attempt and states the Topic is full.
4. **Given** a Participant who already belongs to an active Group for a different Topic, **When** they attempt to join another Topic, **Then** the system rejects the attempt, consistent with the one-active-Group-per-Participant rule.
5. **Given** an eligible Participant clicks "Join" on an open Topic, **When** the action completes, **Then** they become a Group member immediately with no intermediate confirmation step, and a clear success confirmation is shown.
6. **Given** a Participant whose status is Not Participated or Revoked, **When** they attempt to join a Topic, **Then** the system rejects the attempt; only Participants with an Active status may join.

---

### User Story 4 - Organiser Controls Topic-Joining Availability (Priority: P2)

An Organiser opens the organiser settings area and independently enables or disables whether Participants may use the self-service Join action (Story 3) at all. Regardless of this setting, only a Participant whose status is Active is ever eligible to join.

**Why this priority**: This governs when Story 3's Join action is available (e.g., closing team formation once the roster is final), mirroring how the existing self-registration and self-revocation actions are gated by organiser settings; it depends on Story 3 existing but is independently valuable and testable as its own settings capability.

**Independent Test**: Can be fully tested by an Organiser disabling Topic joining in the settings area, confirming an eligible Participant no longer sees a "Join" action on any open Topic, then re-enabling it and confirming the action reappears. Separately, confirm a Participant whose status is Revoked or Not Participated never sees a "Join" action regardless of the setting.

**Acceptance Scenarios**:

1. **Given** an Organiser is on the organiser settings area, **When** they disable Topic joining, **Then** no Participant subsequently sees or can use a "Join" action on any Topic.
2. **Given** an Organiser re-enables Topic joining, **When** the change is saved, **Then** eligible Participants can use the "Join" action again immediately.
3. **Given** Topic joining is enabled, **When** a Participant whose status is Revoked or Not Participated looks for a "Join" action, **Then** none is available to them.
4. **Given** Topic joining is disabled while a Participant already has a Topic's page open, **When** they attempt to join, **Then** the action is rejected server-side even though the button was rendered before the setting changed.

---

### User Story 5 - Browse the Full Topic Overview (Priority: P2)

Any authenticated user opens a "Topic Overview" page from the navigation menu and sees every visible Topic in a single table, with its name, author, current participant count, needed Skills, and compliance status.

**Why this priority**: This complements the Home Page's capped, fullness-only view with a complete picture of every Topic, including full ones and their compliance state; it depends on Stories 1–3 for its data but is independently valuable as a full-roster view.

**Independent Test**: Can be fully tested by creating several Topics (including a full one) and confirming all of them appear in the Topic Overview with the correct author, participant count, Skills, and compliance status.

**Acceptance Scenarios**:

1. **Given** an authenticated user opens the Topic Overview from the menu, **When** the page loads, **Then** they see every Topic visible to them, each showing its Name, Author, current participant count, needed Skills, and Compliance status.
2. **Given** a Topic's Group satisfies every configured compliance rule, **When** the Topic Overview renders that row, **Then** its Compliance status shows as compliant.
3. **Given** a Topic's Group does not satisfy at least one configured compliance rule, **When** the Topic Overview renders that row, **Then** its Compliance status shows as not compliant.
4. **Given** a Topic has no Group yet, **When** the Topic Overview renders that row, **Then** its Compliance status shows a distinct "no group yet" state rather than compliant or not compliant.

---

### User Story 6 - Organiser Configures Compliance Rules (Priority: P2)

An Organiser opens the organiser settings area and defines the instance-wide rules a Group must satisfy to be considered compliant: a mandatory maximum member count, an optional minimum member count, and/or one or more Custom Field diversity requirements, each with its own Organiser-set minimum required distinct-value count (e.g., participants must come from at least two different countries and at least two different departments). Beyond the always-present maximum, any combination of the optional pieces may be configured, combined with AND logic.

**Why this priority**: This governs what "compliant" means everywhere it is displayed (Stories 3 and 5); it depends on those display/join mechanisms existing but is independently valuable and testable as its own settings capability.

**Independent Test**: Can be fully tested by an Organiser setting a minimum of 2 and a maximum of 5 members, adding a Custom Field diversity requirement on "Country," saving, and confirming a Group with 3 members from one country alone is evaluated as not compliant while one from two countries is compliant.

**Acceptance Scenarios**:

1. **Given** an Organiser is on the compliance settings view, **When** they set a Minimum Group Members value, **Then** any Group below that member count is subsequently evaluated as not compliant.
2. **Given** an Organiser is on the compliance settings view, **When** they change the always-present Maximum Group Members value, **Then** any Group at or above the new member count is subsequently blocked from accepting new joins (per Story 3) unless an Organiser override is set on it.
3. **Given** an Organiser is on the compliance settings view, **When** they add a Custom Field diversity requirement referencing an existing Custom Field Definition and set its required minimum distinct-value count, **Then** a Group is subsequently evaluated as not compliant on that requirement unless its members' recorded values for that field include at least that many distinct values.
4. **Given** an Organiser has configured more than one compliance rule, **When** the rules are evaluated for a Group, **Then** the Group is compliant only if every configured rule is individually satisfied (AND logic).
5. **Given** an Organiser clears the optional Minimum Group Members value and removes all diversity requirements, leaving only the always-present Maximum, **When** rules are subsequently evaluated for any Group, **Then** compliance depends solely on whether the Group is below the Maximum.
6. **Given** an Organiser is on the compliance settings view, **When** they save a configuration consisting of only a minimum and the maximum (no diversity requirements), **Then** compliance is evaluated using only those two thresholds.
7. **Given** an Organiser attempts to clear the Maximum Group Members value entirely, **When** they attempt to save, **Then** the system rejects the save and requires a Maximum value to remain set.
8. **Given** an Organiser adds a new Custom Field diversity requirement, **When** they attempt to set its required minimum distinct-value count below 2, **Then** the system rejects it, since a diversity requirement below 2 distinct values is meaningless.

---

### User Story 7 - Organiser Overrides a Group's Compliance (Priority: P3)

An Organiser marks a specific Group as compliant regardless of the automatic evaluation, and this override also allows the Group to keep accepting new joining Participants even after it has reached the configured Maximum Group Members.

**Why this priority**: This is an exception-handling capability for edge cases (e.g., a hackathon organiser deciding to admit one more participant); it depends on the compliance engine (Story 6) existing and is the least frequently used capability in this feature.

**Independent Test**: Can be fully tested by an Organiser marking a Group that is below the configured minimum (and thus automatically not compliant) as compliant, confirming its status changes, and then having a Participant join a Group that is at the configured maximum after it has been marked, confirming the join succeeds.

**Acceptance Scenarios**:

1. **Given** a Group that the automatic evaluation marks as not compliant, **When** an Organiser marks it as compliant, **Then** its Compliance status subsequently shows as compliant (Organiser override) everywhere it is displayed.
2. **Given** a Group has been marked compliant by an Organiser and has reached the configured Maximum Group Members, **When** another eligible Participant attempts to join, **Then** the join succeeds instead of being blocked.
3. **Given** an Organiser removes a previously set compliance override from a Group, **When** compliance is next evaluated for it, **Then** its status reverts to the outcome of the automatic rule evaluation, and the Maximum Group Members cap is enforced again for future joins.

---

### User Story 8 - Organiser Controls the Skill-Matching Display Mode (Priority: P3)

An Organiser chooses, via a single instance-wide setting, whether the Skills column shown to viewers (on the Home Page and the Topic Overview) lists only the Topic's still-needed Skills (those not yet covered by any current Group member's own Skills) or all of the Topic's associated Skills regardless of whether they are already covered.

**Why this priority**: This is a display-refinement setting on top of Stories 2 and 5; it changes what is shown but not the underlying data, making it the lowest-priority piece of this feature.

**Independent Test**: Can be fully tested by creating a Topic needing two Skills where one is already covered by an existing Group member, toggling the setting, and confirming the displayed Skill list changes accordingly between "still needed only" and "all associated Skills."

**Acceptance Scenarios**:

1. **Given** the setting is set to "still needed only," **When** the Home Page or Topic Overview renders a Topic's Skills, **Then** only Skills not already held by any current member of that Topic's Group are shown.
2. **Given** the setting is set to "map against all," **When** the Home Page or Topic Overview renders a Topic's Skills, **Then** every Skill associated with the Topic is shown, regardless of whether a current Group member already offers it.
3. **Given** an Organiser changes this setting, **When** the change is saved, **Then** it immediately affects the Skills column on both the Home Page and the Topic Overview for all users, with no deployment required.

---

### Edge Cases

- What happens when a Topic has no Group yet and the Skills column is being computed? All of the Topic's needed Skills are treated as still needed, since no member exists yet to cover any of them.
- What happens when two Participants attempt to join the same Topic's Group at the same time and only one open slot remains before the Maximum is reached? Exactly one join MUST succeed; the other MUST be rejected as full, with no Group temporarily exceeding the configured maximum outside of an Organiser override.
- What happens when a Participant who authored a Topic tries to join their own Topic's Group? They are treated like any other eligible Participant and MUST be allowed to join, subject to the same one-active-Group-at-a-time and capacity rules as everyone else.
- What happens when a Custom Field referenced by a compliance diversity rule is later removed by an Organiser? The removal MUST be blocked while any compliance rule still references it, consistent with the core domain model's existing reference-blocking behavior for Custom Fields.
- What happens when a Group's members leave enough values blank on a Custom Field used in a diversity rule that fewer than the required number of distinct non-blank values remain? That diversity requirement MUST be evaluated as not satisfied.
- What happens when a Topic's Group is disbanded? The Topic reverts to having no Group, becomes eligible for a new Group to form via a fresh join (Story 3), and no longer counts toward the Home Page's fullness table until it has members again.
- What happens when the Minimum Group Members rule is set higher than the Maximum Group Members rule? The system MUST reject saving such a compliance configuration and explain that the minimum cannot exceed the maximum.
- What happens when an Organiser attempts to leave the Maximum Group Members value blank when saving compliance settings? The system MUST reject the save and require a value, since a Maximum must always be configured.
- What happens when an Organiser attempts to set a Custom Field diversity requirement's minimum distinct-value count below 2? The system MUST reject it, since a diversity requirement inherently requires at least 2 distinct values to have meaning.
- What happens when a non-Organiser user attempts to change compliance settings or mark a Group as compliant? The action MUST be denied.
- What happens when an Organiser lowers the Maximum Group Members value below a Group's current member count? No existing member is removed; the Group simply becomes Not Compliant (over capacity) and is blocked from accepting further joins until its membership drops back to or below the new Maximum, or an Organiser sets a compliance override on it.
- What happens when Topic joining is disabled while a Participant already has a Topic's page open? The join attempt MUST be rejected server-side even though the button was rendered before the setting changed, and the user MUST be shown the current, accurate state on their next view.
- What happens when a Standard user (no Participant record) or a Participant whose status is Not Participated or Revoked attempts to join a Topic directly (e.g., via a stale link or replayed request)? The action MUST be denied server-side regardless of what the client displayed, consistent with only Active Participants being eligible to join.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST allow any registered Participant to propose a new Topic with a name, a description, and zero or more Skills selected from the organiser-defined Skill catalog.
- **FR-002**: System MUST allow a Topic's author to edit its name, description, and Skill selections after creation.
- **FR-003**: System MUST present a Home Page table listing at most 10 Topics that are not currently full, ordered by their Group's current participant count descending (fullest first).
- **FR-003a**: A Topic with no Group yet MUST be treated as having a participant count of zero for the purposes of the Home Page table's ordering and eligibility.
- **FR-003b**: A Topic MUST be considered "full" for the purposes of FR-003 when its Group's current participant count is at or above the instance-wide Maximum Group Members value (FR-011), which is always configured (never unlimited).
- **FR-004**: The Home Page table MUST show, for each listed Topic: its Name, its Group's current participant count, and the subset of the Topic's needed Skills — after applying the Skill Display Mode (FR-017) — that the viewing user's own Skill selections include.
- **FR-005**: System MUST provide a "Topic Overview" item in the navigation menu, available to every authenticated user.
- **FR-006**: The Topic Overview page MUST list every Topic visible to the viewing user, showing its Name, Author, current participant count, needed Skills (after applying the Skill Display Mode, FR-017), and Compliance status.
- **FR-007**: System MUST allow a Participant who does not currently belong to any active Group to join an open Topic.
- **FR-007a**: The "Join" action MUST take effect immediately with no intermediate confirmation step, and MUST show a clear confirmation once it completes successfully, mirroring the immediacy of the existing self-registration "Register" action.
- **FR-007b**: System MUST restrict the Join action (FR-007) to Participants whose current status is Active; a join attempt from a Participant whose status is Not Participated or Revoked, or from a user with no Participant record, MUST be rejected.
- **FR-008**: When a Participant joins a Topic that has no Group yet, System MUST create a new Group for that Topic with the joining Participant as its first and only member.
- **FR-009**: When a Participant joins a Topic that already has a Group, System MUST add them as a member of that existing Group, subject to FR-011 (capacity enforcement).
- **FR-010**: System MUST reject a join attempt from a Participant who already belongs to an active Group for any Topic, consistent with the existing one-active-Group-per-Participant rule.
- **FR-011**: System MUST allow an Organiser to configure, per instance, a Maximum Group Members count (mandatory, always set), an optional Minimum Group Members count, and zero or more Custom Field diversity requirements (each referencing one existing Custom Field Definition and carrying its own required minimum distinct-value count), combined with AND logic when more than one rule is present.
- **FR-011a**: System MUST reject a compliance configuration whose Minimum Group Members value exceeds its Maximum Group Members value when a Minimum is set.
- **FR-011b**: System MUST reject any attempt to save a compliance configuration with the Maximum Group Members value left blank; it MUST always hold a value.
- **FR-011c**: On first application startup, if no Compliance Ruleset exists yet, System MUST seed one with a default Maximum Group Members value and no Minimum or diversity requirements, so a new deployment is never left without a configured Maximum; an Organiser MAY subsequently change this default.
- **FR-011d**: System MUST reject a Custom Field diversity requirement whose required minimum distinct-value count is set below 2.
- **FR-012**: System MUST evaluate a Group as compliant only when every currently configured compliance rule is individually satisfied for it; a Group with at least one member is evaluated as compliant when no optional rules (Minimum, diversity requirements) are configured and it is below the Maximum.
- **FR-012a**: A Custom Field diversity requirement MUST be evaluated as satisfied only when the Group's current members' recorded values for the referenced Custom Field include at least that requirement's configured minimum number of distinct, non-blank values.
- **FR-013**: System MUST block a join attempt (FR-007) that would bring a Group's member count to or beyond the configured Maximum Group Members, unless that Group currently carries an Organiser compliance override (FR-015).
- **FR-013a**: When an Organiser lowers the Maximum Group Members value below a Group's current member count, System MUST NOT remove any existing member from that Group; the Group's compliance MUST simply evaluate as not satisfying the Maximum rule (and thus, absent an Organiser override, remain blocked from further joins per FR-013) until its membership no longer exceeds the new value.
- **FR-014**: System MUST display each Group's Compliance status wherever it is shown (Topic Overview, and any Group detail view) as one of: Compliant, Not Compliant, Compliant (Organiser Override), or No Group Yet.
- **FR-015**: System MUST allow an Organiser to mark a specific Group as a compliance override, which (a) makes its displayed Compliance status show as compliant regardless of the automatic rule evaluation, and (b) suspends the Maximum Group Members cap (FR-013) for that Group, allowing further joins beyond it.
- **FR-016**: System MUST allow an Organiser to remove a previously set compliance override from a Group, after which its Compliance status and Maximum Group Members enforcement both revert to the automatic rule evaluation.
- **FR-017**: System MUST allow an Organiser to set a single, instance-wide display mode for how a Topic's needed Skills are shown to viewers (Home Page and Topic Overview): "still needed only" (excluding Skills already held by at least one current Group member) or "all associated Skills" (regardless of current coverage).
- **FR-018**: Changing the display mode (FR-017) MUST take effect for all users on their next view of the Home Page or Topic Overview, with no deployment required.
- **FR-019**: System MUST restrict changing compliance rules (FR-011), setting or removing a Group compliance override (FR-015, FR-016), changing the Skill display mode (FR-017), and enabling/disabling Topic joining (FR-020a) to users holding the Organiser privilege, denying these actions to all other users.
- **FR-020**: System MUST block removal of a Custom Field Definition that is currently referenced by a compliance diversity requirement, consistent with the core domain model's existing behavior for blocking removal of referenced Skills/Custom Fields.
- **FR-020a**: System MUST allow an Organiser to enable or disable Topic joining via a dedicated instance-wide setting in the organiser settings area.
- **FR-020b**: When Topic joining is disabled, System MUST reject every join attempt (FR-007) and MUST NOT present the Join action as available to any Participant, regardless of their status or a Topic's current capacity.
- **FR-020c**: System MUST enforce the current Topic-joining-enabled setting on every join attempt, regardless of what the requesting user's page displayed at load time.
- **FR-020d**: On first application startup, if no Topic-joining-enabled setting exists yet, System MUST default it to enabled, consistent with the core domain model's other self-service settings defaulting to enabled. Changing the setting MUST take effect for all users on their next join attempt, with no deployment required.

### Accessibility Requirements (WCAG 2.1 AA)

- **FR-021**: All UI introduced by this feature (the Home Page Topic table and its Join action, the Topic proposal/edit Skill picker, the Topic Overview table, the Organiser's compliance-settings screens, the Group compliance-override control, the Skill Display Mode toggle, and the Topic-joining-enabled toggle) MUST conform to WCAG 2.1 Level AA.
- **FR-022**: Every interactive element introduced by this feature (the Join action, Skill picker controls, compliance rule inputs, the override control, the display-mode toggle, the Topic-joining-enabled toggle) MUST be operable using the keyboard alone, in a logical tab order, and MUST display a visible focus indicator when focused via keyboard.
- **FR-023**: Every form control introduced by this feature (Topic Skill picker, Minimum/Maximum Group Members fields, Custom Field diversity requirement fields including the minimum distinct-value count, the compliance override control, the Skill Display Mode toggle, the Topic-joining-enabled toggle) MUST have a programmatically associated label that assistive technology can read.
- **FR-024**: A status change that occurs without a full page reload (a successful or rejected Join, a compliance settings save, a Group override being set or removed, a Skill Display Mode change) MUST be announced to assistive technology via an appropriately scoped live region, not communicated by visual change alone.
- **FR-025**: Any status conveyed by color (a Group's Compliance status — Compliant, Not Compliant, Compliant (Organiser Override), No Group Yet) MUST also be conveyed by text or an icon with an accessible text alternative, so it remains distinguishable without relying on color perception.
- **FR-026**: A validation error introduced by this feature (a rejected Join attempt, an invalid compliance configuration such as minimum exceeding maximum or a blank maximum, a diversity requirement below the minimum distinct-value count) MUST be presented as text associated with the relevant field or action, not indicated by color or icon alone.
- **FR-027**: Text and interactive-element boundaries introduced by this feature MUST meet WCAG 2.1 AA contrast minimums (at least 4.5:1 for normal text, 3:1 for large text and for UI component boundaries such as input borders and focus indicators) in both the light and dark presentation of the UI, if both are supported.

### Key Entities

- **Topic** *(extended by this feature)*: In addition to its name, description, and author (from prior features), a Topic now carries zero or more associated Skills it needs, editable by its author. Its Group (if any) determines its current participant count and Compliance status.
- **Group** *(extended by this feature)*: A team associated with exactly one Topic. Created automatically the moment the first Participant joins that Topic (rather than only via Organiser action), and grows as further Participants join, subject to compliance-driven capacity limits. Carries an optional Organiser-set compliance override flag.
- **Compliance Ruleset**: A single, instance-wide configuration (owned by Organiser Settings) consisting of a mandatory Maximum Group Members count (always set, seeded with a default on first startup), an optional Minimum Group Members count, and zero or more Custom Field diversity requirements, combined with AND logic when evaluating any Group.
- **Custom Field Diversity Requirement**: One rule within the Compliance Ruleset, referencing an existing Custom Field Definition and carrying its own required minimum distinct-value count (at least 2, Organiser-configurable per requirement), requiring a Group's members to collectively hold at least that many distinct, non-blank recorded values for that field to be satisfied.
- **Skill Display Mode**: A single, instance-wide Organiser setting determining whether Topic Skills columns show only still-needed Skills or all associated Skills.
- **Topic Joining Availability**: A single, instance-wide Organiser setting (enabled by default, FR-020d) determining whether the self-service Join action (Story 3) is available at all; independent of, and enforced alongside, the Active-status eligibility rule (FR-007b).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A Participant can propose a Topic with Skills attached and see it appear, correctly listed, on the Topic Overview within one save action.
- **SC-002**: The Home Page never shows more than 10 Topics, and every Topic it shows is strictly less full than the configured Maximum Group Members value, ordered fullest-first, on every load.
- **SC-003**: A Participant can go from viewing an open Topic to being a member of its Group in a single join action, with a Group created automatically if they are the first to join.
- **SC-004**: An Organiser can configure a compliance ruleset (minimum, maximum, and/or diversity requirements) and see every Group's Compliance status on the Topic Overview reflect that configuration without a deployment.
- **SC-005**: 100% of join attempts that would exceed a Group's configured Maximum Group Members are rejected unless that Group carries an Organiser compliance override.
- **SC-006**: An Organiser can mark a Group compliant and immediately allow at least one further Participant to join it beyond the configured maximum, without changing the instance-wide compliance ruleset.
- **SC-007**: 100% of attempts by non-Organiser users to change compliance rules, Group overrides, or the Skill display mode are denied.
- **SC-008**: An automated accessibility scan of the Home Page Topic table (including the Join action), the Topic proposal/edit Skill picker, the Topic Overview, and the Organiser's compliance-settings and override screens reports zero critical or serious WCAG 2.1 AA violations.
- **SC-009**: An Organiser can disable Topic joining and confirm that no Participant, including previously-eligible ones, can join any Topic; re-enabling it restores the "Join" action for eligible Participants on their very next view, with no deployment.
- **SC-010**: 100% of join attempts from a Participant whose status is not Active are rejected, regardless of the Topic-joining-enabled setting or the Topic's remaining capacity.

## Assumptions

- This feature supersedes the deferral in the prior homepage feature ("Skill association on Topics ... is deferred to a later, more detailed feature"): Topics now carry Skill selections captured directly on the existing propose/edit flow.
- The Home Page's 3-column, fullness-sorted table introduced here is the Topic-related content shown in the Home Page's existing left area; it replaces the plain name/description/author topic list from the prior feature for the purposes of Topic display, while the full name/description/author detail remains available via the Topic Overview page and each Topic's own detail/edit view. Pending (not-yet-approved) Topics continue to follow the prior feature's visibility rules and are not included in the fullness-sorted count of "open" Topics available to join.
- Only Approved Topics can be joined (Story 3); a Pending Topic has no Group and cannot be joined until an Organiser approves it, consistent with Pending Topics being hidden from all but their author and Organisers.
- A Participant may join at most one Topic's Group at a time, consistent with the core domain model's existing one-active-Group-per-Participant rule; this feature does not introduce a way to switch directly between Topics — a Participant must first leave their current Group through existing means (e.g., revoking registration) before joining another.
- This feature does not introduce a way for a Participant to voluntarily leave a Group while remaining a registered Participant; leaving a Group continues to happen only as a side effect of existing flows (e.g., self-revocation, or an Organiser disbanding the Group), consistent with prior features.
- The Compliance Ruleset is a single, global configuration applying uniformly to every Topic's Group, matching the request's description of "configured per instance," not configured per Topic.
- A Custom Field diversity requirement compares each member's recorded value for the referenced field for exact equality when counting distinct values; free-text fields are compared on their stored value as-is (no fuzzy or case-insensitive matching).
- The default Maximum Group Members value seeded on first startup (FR-011c) is a small placeholder (e.g., 5) intended purely to keep the system usable out of the box; an Organiser is expected to review and adjust it before opening registration for a real hackathon.
- When an Organiser adds a new Custom Field diversity requirement, its required minimum distinct-value count defaults to 2 and can be raised from there; it can never be set below 2 (FR-011d).
- The Topic Overview page is visible to any authenticated user (Standard, Participant, or Organiser), consistent with the existing Topic list's visibility on the Home Page; only changing compliance rules, Group overrides, the Skill display mode, and the Topic-joining-enabled setting is restricted to Organisers.
- The Skill display mode (FR-017) applies uniformly to both the Home Page and the Topic Overview; there is no separate setting per page.
- WCAG 2.1 Level AA (not AAA) is the accessibility target for all UI introduced by this feature (FR-021–FR-027), consistent with feature 003's target; automated scanning (SC-008) covers what tooling can verify, but manual screen-reader and keyboard-only testing is still expected before this feature ships. Pre-existing UI from features 002–004 is out of scope for this feature's accessibility requirements, unless this feature modifies it directly (e.g., the Topic proposal/edit form gains a Skill picker).
- The Topic-joining-enabled setting (FR-020a–FR-020d) and the Active-status eligibility rule (FR-007b) are independent gates that both apply on every join attempt: disabling the setting blocks all Participants regardless of status, and a non-Active status blocks that Participant regardless of the setting.
- Disabling Topic joining does not affect existing Group memberships or in-progress Groups; it only blocks new joins going forward, consistent with how disabling self-registration in feature 003 does not revoke existing Participant records.
