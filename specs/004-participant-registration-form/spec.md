# Feature Specification: Participant Registration Form, Profile Fields & Directory

**Feature Branch**: `004-participant-registration-form`

**Created**: 2026-08-29

**Status**: Draft

**Input**: User description: "Participant registration. Any user can become a participant. In the settings a max participant count can be defined, after which registration for participation is not possible anymore. Revoked participants do not count to this number, only active ones. When a participant register, the participant needs is lead to a form, where they can select the Skills (skills can help with topic selection, but don't need to dictate the topic). Besides the existing multi select and free text, it is also possible to have single select options. One special single select option is the country, which can be enabled, containing all countries (based on ISO 3166, searchable field). Each custom field can be marked as public (visible to other users) and/or for Overview, so that the custom field is listed in user overviews, e.g. in a topic view (coming in a later spec). Fields can be updated by users (edited). This edit feature can be enabled or disabled in the administration. Also a menu item for participants, listing all is added, which can be enabled for organisers only, organisers and particpants or all logged in users. This overview is a table, with the option to view the users. It is clearly marked which values are visible to other users and which are not. Also skills can be visible to other users, but this can be enabled or disabled from an organiser."

## Clarifications

### Session 2026-08-29

- Q: When a Custom Field is marked "for Overview" but NOT "Public", who may see it in the Participants overview table and detail view? → A: Overview alone only controls whether the field gets a table column for viewers who could already see that data (Organisers, and the owning Participant on their own row); a non-owning viewer never sees it unless the field is also marked Public. Public remains the single privacy gate.
- Q: On the registration/edit form, must a Participant select at least one Skill to submit? → A: No — Skill selection is always optional; a form can be submitted with zero Skills selected.
- Q: When self-edit is disabled by an Organiser, can a Participant still open their own profile in a read-only view? → A: Yes — the Participant can always open their own profile to see their current Custom Field values and Skill selections; only the edit controls disappear while self-edit is disabled.
- Q: How does the "Not Participated" status (defined in the core domain model, alongside Active and Revoked) interact with self-service registration and revocation? → A: "Not Participated" can only be set by an Organiser, purely to document the outcome (e.g., someone who registered but never showed up). Once a Participant's status is Not Participated, self-service registration (reactivation) and self-service revocation are both permanently unavailable to that Participant; only an Organiser can move them out of that status via the existing organiser participant management views.
- Q: Should a Participant's Skills also appear as a column in the Participants overview table, or only on their individual detail view? → A: Only on the individual detail view — the overview table's columns are limited to Custom Fields marked Overview; Skills are not shown as a table column.
- Q: In what order should the Participants overview table list registered Participants? → A: Alphabetical by display name (ascending).
- Q: Should an Organiser be allowed to set the maximum registrations to 0 to freeze registration, or must the value be at least 1? → A: Require a minimum of 1 — freezing registration entirely is the job of the existing self-registration-enabled toggle (homepage feature), not the registration-count setting.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Register Through a Profile Form (Priority: P1)

An authenticated user who is not yet a Participant starts registration and is taken to a form where they fill in the Custom Fields an Organiser has configured (free text, single-select, multi-select, and — if enabled — Country) and select any Skills that apply to them, before their Participant record is created.

**Why this priority**: This is the entry point for everything else in this feature — profile field types, visibility, editing, and the directory all operate on data captured here. Without it, there is nothing for the rest of the feature to show or manage.

**Independent Test**: Can be fully tested by an unregistered user opening registration, filling in all required Custom Fields, optionally selecting Skills, submitting, and confirming a registered Participant record now exists carrying exactly the values entered.

**Acceptance Scenarios**:

1. **Given** an authenticated user with no Participant record starts registration, **When** the form loads, **Then** they see every configured Custom Field and the Skill catalog presented as one unified set of ordinary profile fields — each shown only by its own label and a control appropriate to its type, with no "Custom Field" grouping, tag, or badge distinguishing it from any built-in field — and fields marked required visually distinguished from optional ones.
2. **Given** the registration form is open, **When** the user submits without filling in a field marked required, **Then** submission is rejected, the missing field(s) are identified, and no Participant record is created.
3. **Given** the registration form is open, **When** the user fills in all required fields, makes their Skill selections, and submits, **Then** a registered Participant record is created carrying exactly the submitted Custom Field values and Skill selections.
4. **Given** a Custom Field is configured as single-select, **When** the user opens its control on the form, **Then** they can choose exactly one of its organiser-defined options.
5. **Given** the Country field is enabled, **When** the user opens its control on the form, **Then** they can search and select exactly one country from the full ISO 3166 country list.
6. **Given** a user abandons the registration form without submitting, **When** they later revisit the site, **Then** no Participant record exists for them and they are offered registration again.
7. **Given** a Participant's status has been set to Not Participated by an Organiser, **When** they visit the site, **Then** they see no registration/reactivation entry point and no self-revocation action, with a clear statement that their status was set by an Organiser and only an Organiser can change it.

---

### User Story 2 - Organiser Caps Total Registrations (Priority: P1)

An Organiser sets a maximum number of registrations in the settings. Once that many Participants are registered (hold Active status), no further registration (new or reactivated) is possible until the registration count drops back below the cap.

**Why this priority**: Capacity limits are a hard operational constraint for running a hackathon (venue size, swag, catering) and must be enforced from the moment self-service registration exists, alongside Story 1.

**Independent Test**: Can be fully tested by an Organiser setting the max to a small number, registering that many Participants, confirming the next registration attempt is blocked, then revoking one Participant and confirming registration becomes possible again.

**Acceptance Scenarios**:

1. **Given** an Organiser sets a maximum number of registrations, **When** the number of registered Participants reaches that count, **Then** any further registration attempt is rejected with a clear "Maximum registrations reached" message.
2. **Given** the registration count is at the configured maximum, **When** an Organiser or the Participant themself revokes one Participant, **Then** the registration count drops below the maximum and a new registration attempt is subsequently accepted.
3. **Given** a Participant is Revoked, **When** the registration count is calculated for the maximum-capacity check, **Then** that Revoked Participant is excluded from the count.
4. **Given** a previously Revoked Participant reactivates via registration, **When** the registration count is already at the configured maximum, **Then** the reactivation attempt is rejected the same as a first-time registration would be.
5. **Given** no maximum is configured, **When** any number of users register, **Then** registration is never blocked for capacity reasons.
6. **Given** the registration count is already at the configured maximum, **When** a user opens the registration entry point, **Then** they are shown a clear "Maximum registrations reached" message before investing time filling in the form.

---

### User Story 3 - Organiser Configures Field Types & Visibility (Priority: P2)

An Organiser defines Custom Fields as free text, single-select, or multi-select (extending the existing catalog), optionally enables the built-in Country field, and marks each field as Public and/or included in the Overview, controlling how it appears to other users and in the Participants overview.

**Why this priority**: The registration form (Story 1) and the directory (Story 5) both depend on these field definitions and their visibility flags existing; this is the configuration layer that makes the rest of the feature meaningful beyond a fixed set of fields.

**Independent Test**: Can be fully tested by an Organiser creating a single-select Custom Field with options, enabling the Country field, marking one field Public and Overview, and confirming those flags are honored on the registration form and (once Story 5 exists) in the directory.

**Acceptance Scenarios**:

1. **Given** an Organiser is on the Custom Field management view, **When** they define a new field as single-select and provide its selectable options, **Then** that field becomes available on the registration and edit forms restricted to exactly one of those options.
2. **Given** an Organiser is on the Custom Field management view, **When** they enable the Country field, **Then** it becomes available on the registration and edit forms as a searchable single-select populated with the full ISO 3166 country list, with no manually entered options.
3. **Given** an Organiser is on the Custom Field management view, **When** they disable the Country field, **Then** it no longer appears on the registration or edit forms, while any values Participants already recorded for it remain stored and visible on their existing records.
4. **Given** an Organiser is viewing a Custom Field's configuration, **When** they mark it Public, **Then** its value becomes visible to other users wherever Participant field values are shown to non-Organisers.
5. **Given** an Organiser is viewing a Custom Field's configuration, **When** they mark it for Overview, **Then** it becomes one of the columns shown in the Participants overview table.
6. **Given** a Custom Field is marked neither Public nor Overview, **When** any user other than an Organiser or the field's owning Participant views that Participant's data, **Then** the field and its value are not shown to them.

---

### User Story 4 - Participants Edit Their Own Profile (Priority: P2)

When an Organiser has enabled self-service editing, a Participant reopens their Custom Field values and Skill selections at any time after registration and updates them, subject to the same validation as registration.

**Why this priority**: Registration data goes stale (skills change, a field was filled in wrong); this closes the loop opened by Story 1 without requiring an Organiser to make every correction, but it is a controllable capability rather than foundational.

**Independent Test**: Can be fully tested by an Organiser enabling self-edit, having a Participant change a field value and their Skill selections, saving, and confirming the update persists; then disabling self-edit and confirming the edit action is no longer available.

**Acceptance Scenarios**:

1. **Given** self-edit is enabled and a Participant opens their own profile, **When** the edit form loads, **Then** it is pre-filled with their currently recorded Custom Field values and Skill selections.
2. **Given** a Participant is editing their profile, **When** they change a value and save, **Then** the new value is persisted and reflected the next time their profile is viewed.
3. **Given** a Participant is editing their profile, **When** they submit a value that violates a field's configured type or leaves a required field empty, **Then** the submission is rejected with the same validation used at registration.
4. **Given** self-edit is disabled by an Organiser, **When** a Participant looks for an edit action on their own profile, **Then** none is available to them.
5. **Given** self-edit was enabled when a Participant's edit page loaded but is disabled before they submit, **When** they submit their changes, **Then** the save is rejected server-side regardless of what the page displayed at load.

---

### User Story 5 - Participants Directory & Detail View (Priority: P2)

A navigation menu item lists all Participants in a table, restricted to an audience the Organiser configures (Organisers only, Organisers and Participants, or all authenticated users). From the table, a viewer opens an individual Participant to see their visible details.

**Why this priority**: This is the first participant-facing consumer of the profile data and visibility flags introduced by Stories 1 and 3; it delivers standalone value (finding and browsing other participants) once those exist.

**Independent Test**: Can be fully tested by an Organiser setting the directory audience to "all authenticated users," confirming a Participant sees the menu item and table listing Overview-marked fields, opening another Participant's detail view, and confirming only Public-marked data is shown.

**Acceptance Scenarios**:

1. **Given** an Organiser sets the Participants directory audience, **When** a user outside that audience looks at the navigation menu, **Then** no Participants menu item is shown to them.
2. **Given** a user within the configured audience opens the Participants menu item, **When** the table loads, **Then** it lists registered Participants, ordered alphabetically ascending by display name, with a column for each Custom Field marked Overview.
3. **Given** the Participants table is open, **When** the viewer selects a specific Participant, **Then** they are taken to that Participant's detail view.
4. **Given** a non-Organiser viewer opens another Participant's detail view, **When** the view loads, **Then** it shows only the Custom Fields marked Public (and Skills, only if Skill visibility is enabled) — never fields left private.
5. **Given** a Participant opens their own detail view, **When** the view loads, **Then** they see all of their own data regardless of each field's Public flag.
6. **Given** an Organiser opens any Participant's detail view, **When** the view loads, **Then** they see all of that Participant's data regardless of visibility flags.

---

### User Story 6 - Organiser Controls Skill Visibility & Values Are Clearly Marked (Priority: P3)

An Organiser toggles, globally, whether Participants' Skill selections are visible to other users. Wherever a Participant's own field values and Skill selections are displayed to them, each is clearly marked as visible to others or private.

**Why this priority**: This rounds out the visibility model with a single global control for Skills (which, unlike Custom Fields, are not individually flagged) and a clarity requirement that spans the other stories; it is valuable but not blocking for the core registration/directory flows to work.

**Independent Test**: Can be fully tested by an Organiser toggling Skill visibility off, confirming Skills no longer appear on other Participants' detail views (Skills are never shown in the directory table, per FR-027), then toggling it on and confirming they reappear on detail views; separately, opening a Participant's own profile and confirming each value shows a visible/private indicator matching its actual configuration.

**Acceptance Scenarios**:

1. **Given** an Organiser enables Skill visibility, **When** any user views a Participant's detail view, **Then** that Participant's selected Skills are shown to them (subject to Story 5's directory-audience rule).
2. **Given** an Organiser disables Skill visibility, **When** any non-Organiser, non-owning user views a Participant's detail view, **Then** Skills are not shown.
3. **Given** a Participant is viewing their own profile (read or edit), **When** the page renders each Custom Field and the Skills section, **Then** each is labeled as either "visible to others" or "private," matching its actual Public/Skill-visibility configuration, using text or an icon and not color alone.

---

### Edge Cases

- What happens when an Organiser lowers the maximum number of registrations below the number currently registered? Existing registered Participants MUST remain Active (registered); only new registrations and reactivations are blocked until the registration count falls back under the new maximum.
- What happens when two users submit registration at the same moment for the last available slot under the maximum? Exactly one submission MUST succeed; the other MUST be rejected with the capacity-reached message, with no double-counting or record left in a partial state.
- What happens when an Organiser re-enables the Country field after Participants registered while it was disabled? Those Participants' Country value MUST be treated as unset (and, if Country is required, their record flagged incomplete per the existing required-field rule) until they or an Organiser fill it in.
- What happens when an Organiser removes a selectable option from a single-select Custom Field that a Participant's value currently uses? Consistent with the existing rule for multi-select fields, the removal MUST be blocked while any Participant value still references that option.
- What happens when the Participants overview table renders a column for a Custom Field that a given Participant never filled in? That cell MUST show a clear empty indicator rather than an error or a misleading blank.
- What happens when a Participant who is not within the configured directory audience navigates directly to the Participants table or another Participant's detail URL? Access MUST be denied, consistent with the menu item itself being hidden from them.
- What happens when an Organiser changes the directory audience setting while a user's page is already open? The next request MUST enforce the new setting; the previously rendered page does not itself grant continued access.
- What happens when a Participant edits their profile and removes their only recorded value for a required field? The save MUST be rejected the same as at registration, per the existing required-field validation rule.
- What happens when an Organiser sets a Participant's status to Not Participated while that Participant is Active and a member of a Group? The status change is an existing organiser-only capability from the core domain model; this feature does not alter Group membership as a side effect of it (unlike self-revocation's automatic Group removal) — an Organiser must remove Group membership separately if intended.
- What happens when a Participant already in Not Participated status attempts registration or revocation directly (e.g., stale page, direct request)? The attempt MUST be rejected server-side per FR-006a, regardless of what any previously rendered page displayed.
- What happens when an Organiser attempts to set the maximum registrations to 0 or a negative number? The save MUST be rejected with a validation message; an Organiser who wants to stop all registration MUST use the existing self-registration-enabled setting instead (FR-007).

## Requirements *(mandatory)*

### Functional Requirements

**Registration form**

- **FR-001**: System MUST retain the existing homepage "Register" action (introduced in the homepage feature) as the entry point for self-registration, governed by the same self-registration-enabled setting. When used, it MUST navigate the user to a registration form presenting every configured Custom Field Definition (per its type) and the Skill catalog, and MUST NOT create a Participant record until that form is validly submitted; registration is not considered finished until submission succeeds. This supersedes the prior bare-record, no-form registration behavior (immediate creation on click) while keeping the "Register" action itself in place.
- **FR-001a**: System MUST retain the existing homepage "Revoke Registration" action unchanged in placement, wording, and confirmation behavior (per the homepage feature), governed by the same self-revocation-enabled setting, plus the Not Participated lockout (FR-006a).
- **FR-002**: The registration form MUST visually distinguish Custom Fields marked required from those that are optional.
- **FR-002a**: On the registration form, the edit form, and any detail/overview view, a Custom Field MUST be presented the same way as any built-in Participant attribute (e.g., Skills) — by its own configured name/label and a control or display appropriate to its type — with no "Custom Field" heading, section, tag, or badge that would distinguish it from a native field to a non-Organiser user. This does not apply to the Organiser's own Custom Field management/configuration screens, where "Custom Field" terminology is expected.
- **FR-003**: Submission MUST be rejected, with the missing field(s) identified, when any Custom Field marked required is left empty; in this case no Participant record is created.
- **FR-004**: Skill selection MUST always be optional on the registration and edit forms; a submission with zero Skills selected MUST be accepted.
- **FR-005**: Upon valid submission, the system MUST create a registered Participant record (Active status) carrying exactly the submitted Custom Field values and Skill selections.
- **FR-006**: Reactivating a previously Revoked Participant through registration MUST present the same form, pre-filled with that Participant's previously stored Custom Field values and Skill selections, editable before resubmission re-activates the record.
- **FR-006a**: The "Not Participated" status MUST only be settable by an Organiser (via the existing organiser participant management views), never by a Participant's own action; once a Participant's status is Not Participated, the system MUST NOT offer them the registration/reactivation entry point (FR-001) or the self-revocation action, and MUST reject either attempt server-side if triggered directly. Only an Organiser can move a Participant out of Not Participated status.

**Maximum registrations**

- **FR-007**: System MUST allow an Organiser to configure a maximum number of registrations as an integer of at least 1, or leave it unconfigured for no limit; the system MUST reject an attempt to set it to 0 or a negative number, since freezing registration entirely is the job of the existing self-registration-enabled setting (homepage feature), not this count.
- **FR-008**: System MUST count only Participants currently holding Active status as registered toward the configured maximum; Revoked and any other non-Active status MUST NOT count.
- **FR-009**: System MUST reject, at the moment of submission, any registration or reactivation that would cause the number of registrations to exceed the configured maximum, regardless of what the entry point displayed when the user started.
- **FR-010**: When the maximum is already reached, the registration entry point MUST clearly display a "Maximum registrations reached" message to the user — in place of, or immediately alongside, the Register action — before they begin filling in the form.

**Custom Field types & the Country field**

- **FR-011**: System MUST extend Custom Field Definition types to include single-select, alongside the existing free-text and multi-select types.
- **FR-012**: For a single-select Custom Field Definition, the system MUST require the Organiser to define its set of selectable options, and MUST restrict each Participant's value to exactly one of them (or none, if optional).
- **FR-013**: System MUST provide one built-in single-select Custom Field, Country, that an Organiser can enable or disable; when enabled it MUST offer the full ISO 3166 country list as its options, presented as a searchable field, and its options MUST NOT be manually editable by the Organiser.
- **FR-014**: The Country field, when enabled, MUST otherwise be configurable the same as any other Custom Field Definition (required, Public, Overview, subject to self-edit).
- **FR-015**: Disabling the Country field MUST remove it from the registration and edit forms without deleting any Participant values already recorded for it.

**Field & Skill visibility**

- **FR-016**: System MUST allow an Organiser to mark each Custom Field Definition (including Country) independently as Public (visible to other users) and/or Overview (included as a column in the Participants overview table), the two flags being independently toggleable.
- **FR-017**: A Custom Field marked Overview but not Public MUST show its column/value only to Organisers and to the field's own Participant (on their own row/detail view); it MUST be omitted for any other, non-owning viewer, the same as if it were fully private. Public remains the sole flag that exposes a field's value to other users.
- **FR-018**: System MUST allow an Organiser to toggle, globally, whether Participants' Skill selections are visible to other users; this setting applies to all Participants uniformly (there is no per-Participant or per-Skill override).
- **FR-019**: A non-Organiser, non-owning viewer of a Participant's data MUST see only Custom Field values whose definition is marked Public (resolved per FR-017 for Overview-only fields) and Skill selections only if the global Skill-visibility setting is enabled.
- **FR-020**: Wherever a Participant's own Custom Field values and Skill selections are displayed to them (view or edit), each MUST be labeled as visible to other users or private, using text or an icon rather than color alone, matching its actual Public/Skill-visibility configuration.

**Self-service field editing**

- **FR-021**: System MUST allow an Organiser to enable or disable, via a single global setting, whether Participants may edit their own already-recorded Custom Field values and Skill selections after registration.
- **FR-022**: When self-edit is enabled, a Participant MUST be able to open a pre-filled edit form for their own Custom Field values and Skill selections and save changes, validated the same way as registration (FR-002, FR-003).
- **FR-023**: When self-edit is disabled, no edit action MUST be presented to a Participant for their own Custom Field values or Skill selections; the Participant MUST still be able to open their own profile in a read-only view showing their current values.
- **FR-024**: System MUST enforce the current self-edit setting on every edit submission attempt, regardless of what the Participant's page displayed when it loaded.

**Participants directory**

- **FR-025**: System MUST provide a "Participants" navigation menu item whose visibility an Organiser configures to one of: Organisers only, Organisers and Participants, or all authenticated users.
- **FR-026**: System MUST deny access to the Participants table and to any Participant's detail view for a user outside the currently configured audience, even via a direct link, consistent with the menu item being hidden from them.
- **FR-027**: The Participants table MUST list registered Participants and MUST include a column for each Custom Field Definition marked Overview; the table MUST NOT include a Skills column — Skill selections are shown only on the individual Participant detail view (FR-029/FR-030), never in the table.
- **FR-027a**: The Participants table MUST order registered Participants alphabetically ascending by display name.
- **FR-028**: From the Participants table, a permitted viewer MUST be able to open an individual Participant's detail view.
- **FR-029**: A Participant's own detail view MUST show all of their own Custom Field values and Skill selections regardless of visibility flags.
- **FR-030**: An Organiser's view of any Participant's detail view MUST show all of that Participant's data regardless of visibility flags.
- **FR-031**: A table cell for a Custom Field the given Participant has not filled in MUST render a clear empty indicator rather than an error or misleading blank.

**User feedback**

- **FR-032**: Every user-initiated action introduced by this feature (starting registration, submitting the registration or edit form, revoking registration, an Organiser saving a setting) MUST give the user immediate, visible feedback: an in-progress indicator while it is processing, and a clear success or failure outcome once it completes.
- **FR-033**: A successful registration or reactivation submission MUST show an explicit confirmation that the Participant is now registered (Active status), consistent with the existing homepage registration confirmation (homepage feature FR-003a).
- **FR-034**: A successful self-edit save MUST show an explicit confirmation; a rejected save MUST identify which field(s) caused the rejection and why, using the same messaging pattern as registration validation (FR-003).
- **FR-035**: A registration or reactivation rejected because the maximum number of registrations has been reached (FR-009) MUST show the same distinct, specific "Maximum registrations reached" message used at the entry point (FR-010), rather than a generic error or a validation message about individual fields.
- **FR-036**: System MUST prevent a duplicate submission caused by a double-click or repeated submit while a registration, reactivation, or edit save is already in progress, e.g. by disabling the submit control until that action completes.

### Accessibility Requirements (WCAG 2.1 AA)

This feature adopts the same accessibility target and scoping approach as the homepage feature (003): conformance is required for the screens and controls this feature introduces, not as a retrofit of pre-existing 002/003 screens.

- **FR-037**: All UI introduced by this feature (registration form, self-edit form, Participants directory table and detail view, Not Participated lockout messaging, and the new organiser settings controls — maximum registrations, self-edit toggle, Skill-visibility toggle, directory audience) MUST conform to WCAG 2.1 Level AA.
- **FR-038**: Every interactive element introduced by this feature (form fields, the searchable Country selector, Skill selection controls, directory table navigation, view/edit actions) MUST be operable using the keyboard alone, in a logical tab order, and MUST display a visible focus indicator when focused via keyboard.
- **FR-039**: Every form control introduced by this feature (registration/edit fields of every type, the searchable Country field, each organiser setting toggle) MUST have a programmatically associated label that assistive technology can read.
- **FR-040**: A status change or feedback event that occurs without a full page reload (registration success, revocation confirmation, edit-save confirmation, a capacity-reached message, a settings toggle taking effect) MUST be announced to assistive technology via an appropriately scoped live region, not communicated by visual change alone.
- **FR-041**: Any status conveyed by color (Public/private field marking, required/optional field marking, the Not Participated lockout state, the capacity-full state) MUST also be conveyed by text or an icon with an accessible text alternative.
- **FR-042**: The "Revoke Registration" confirmation prompt MUST remain programmatically identified as a dialog to assistive technology, MUST trap keyboard focus while open, and MUST return focus to a logical location once it closes, unchanged from the homepage feature's requirement.
- **FR-043**: A validation error introduced by this feature (a missing required field, a rejected single-/multi-select value, a capacity-reached rejection) MUST be presented as text associated with the relevant field or action, not indicated by color or icon alone.
- **FR-044**: Text and interactive-element boundaries introduced by this feature MUST meet WCAG 2.1 AA contrast minimums (at least 4.5:1 for normal text, 3:1 for large text and UI component boundaries such as input borders and focus indicators) in both the light and dark presentation of the UI, if both are supported.
- **FR-045**: The searchable Country selection control MUST remain operable via keyboard and MUST announce its filtered results to assistive technology as the user searches, using an accessible combobox/listbox pattern rather than relying on visual-only updates.

### Key Entities

- **Custom Field Definition** *(extends the core domain model's entity)*: Now supports type single-select (with organiser-defined options) in addition to free-text and multi-select, plus a built-in Country variant (fixed ISO 3166 option list, at most one enabled at a time). Carries two independent visibility flags — Public and Overview — alongside its existing required flag.
- **Custom Field Value**: A Participant's recorded answer to one Custom Field Definition; for single-select and Country, restricted to exactly one of the definition's options.
- **Participant** *(as used by this feature)*: Gains a form-driven registration/reactivation path that captures Custom Field values and Skill selections before the record becomes registered (Active status), and an optional self-service edit path for the same data. When in Not Participated status (organiser-set only, per FR-006a), neither path is available; the status is a self-service dead end by design.
- **Organiser Settings** *(extends the homepage feature's entity)*: Gains a maximum number of registrations (or unset for no limit), a global self-edit-enabled toggle, a global Skill-visibility toggle, and the Participants directory audience setting (Organisers only / Organisers and Participants / all authenticated users).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A user can go from starting registration to holding a registered Participant record (Active status) with all their entered profile data, in a single form submission, with no Organiser intervention required.
- **SC-002**: 100% of registration and reactivation attempts made while the registration count is at the configured maximum are rejected with a "Maximum registrations reached" message, and 100% of such attempts made while below it are accepted (capacity permitting for other reasons).
- **SC-003**: An Organiser can add a new single-select Custom Field or enable the Country field and have it appear on the next registration or edit form with no deployment.
- **SC-004**: 100% of Custom Field values and Skill selections shown to a non-Organiser, non-owning viewer are limited to those the field's or setting's visibility configuration marks visible; none of the remainder ever appears to that viewer.
- **SC-005**: An enabled Participant can update their own profile and see the change reflected the next time they or another permitted viewer opens their profile, with no data loss to their other fields.
- **SC-006**: 100% of Participants-menu and directory access attempts from outside the configured audience are denied.
- **SC-007**: A viewer of any Participant's own or others' profile can, without asking an Organiser, correctly state for every displayed field and for Skills whether it is visible to other users or private.
- **SC-008**: 100% of state-changing actions introduced by this feature (register, reactivate, revoke, edit save, an organiser setting change) present a visible in-progress indicator followed by an explicit success or failure outcome, with no action left ambiguous about whether it completed.
- **SC-009**: An automated accessibility scan of the registration form, self-edit form, Participants directory and detail view, and this feature's new organiser settings controls reports zero critical or serious WCAG 2.1 AA violations; pre-existing 002/003 screens are excluded from this scan, consistent with the homepage feature's own scoping.

## Assumptions

- This feature supersedes the homepage feature's ("003") assumption that self-service registration creates a bare registered Participant record (Active status) with no field-capture form; that immediate-record behavior is now replaced by the form-driven flow described here. Self-revocation (003) is unchanged by this feature except for the new Not Participated lockout (FR-006a), which also constrains 003's existing Revoke action.
- The maximum number of registrations, self-edit toggle, Skill-visibility toggle, and Participants directory audience are each a single, global setting for the one ongoing hackathon, consistent with how Organiser Settings already work in the homepage feature.
- The Country field's option list (ISO 3166 country names/codes) is maintained by the system as static reference data; it is not an Organiser-editable catalog the way Skills and other single-/multi-select options are.
- A field marked Overview but not Public is treated as visible only to the field's own Participant and to Organisers, the same as a fully private field (see Clarifications); this keeps the Overview flag from being usable to bypass the Public flag's privacy intent.
- Removing a selectable option from a single-select Custom Field follows the same in-use block already established for multi-select fields in the core domain model (FR-012b of that feature).
- The Participants directory table lists only registered Participants (Active status); Organisers can still see Revoked and Not Participated Participants through the existing organiser-only participant management views from the core domain model.
- "Registered" is the user-facing term this spec uses for a Participant currently holding Active status (per the core domain model's Active/Not Participated/Revoked status field); the underlying status value name itself is unchanged.
- Skill visibility defaults to disabled (private) until an Organiser explicitly turns it on, consistent with treating profile data as private-by-default unless an Organiser opts a field or setting into visibility.
- This feature does not itself build the "topic view" or other later-spec consumer of Overview-marked fields mentioned in the input; it only ensures those fields and their flags exist and are enforced consistently in the directory this feature adds.
- "Custom Field" is an Organiser-facing/internal modeling term (per FR-002a); participant-facing screens present these values as ordinary profile fields, indistinguishable in presentation from built-in attributes like Skills — only the Organiser's configuration screens use "Custom Field" terminology.
- WCAG 2.1 Level AA (not AAA) remains the accessibility target for this feature's new UI, matching the homepage feature's target and its scoping decision (new screens only); automated scanning (SC-009) covers what tooling can verify, but manual screen-reader and keyboard-only testing is still expected before this feature ships, consistent with the homepage feature's assumption.
- "Immediate, visible feedback" (FR-032–FR-036) is assumed to mean synchronous, in-page feedback (loading state, inline confirmation/error) rather than out-of-band notifications such as email; no notification channel is introduced by this feature.
