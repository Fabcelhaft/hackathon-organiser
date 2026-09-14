# Feature Specification: Sortable Custom Fields

**Feature Branch**: `009-custom-fields-sortable`

**Created**: 2026-09-14

**Status**: Draft

**Input**: User description: "custom fields are sortable. Enabling custom fields to be sorted. Default every field has the index 0, which leads to alphabetical sorting. As soon as an index differs from others (positive or negative numbers) it is ordered alphabetical with others in that index. In the admin view they are loaded in the order as shown as in user views. ordering should apply to all views."

## Clarifications

### Session 2026-09-14

- Q: Should a change to a Custom Field's sort index be written to the audit trail, given that Custom Field edits are not audited at all today? → A: No audit. Sort index changes are not recorded, consistent with all other Custom Field configuration edits, which are not audited either.
- Q: When two fields share the same sort index and have identical labels, which one should come first? → A: The field created earlier comes first (oldest to newest).
- Q: Should the options offered inside a single-select or multi-select field be orderable as well? (raised during implementation) → A: Yes. Each option gets its own sort index following the same rule as fields (index ascending, then label case-insensitively, then creation time). The Organiser sets it when adding an option and can change it afterwards on the field's edit page. The order applies wherever options are offered or shown: the registration/self-edit form, and every place a Participant's selected options are displayed.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Organiser assigns a sort index to a Custom Field (Priority: P1)

An Organiser wants the Custom Fields shown to Participants in a deliberate order — for example "Company" first, then "Job title", then everything else — instead of whatever order the system happens to produce. When creating or editing a Custom Field, the Organiser enters a whole-number sort index. Fields with a lower index appear before fields with a higher index; fields sharing the same index appear alphabetically by label among themselves. Leaving the index untouched keeps the field at the default index of 0, so an Organiser who never uses the feature simply gets all fields in alphabetical order.

**Why this priority**: Without a way to record the index, nothing else in this feature can work. It is the minimum slice that already delivers value on its own once any view respects it.

**Independent Test**: Can be fully tested by creating three Custom Fields, giving one a negative index, one a positive index and leaving one at 0, then reloading the Organiser's Custom Fields list and confirming the rows appear in index order with alphabetical ties.

**Acceptance Scenarios**:

1. **Given** the Organiser opens the "New Custom Field" form, **When** the form is displayed, **Then** it offers a sort index input pre-filled with 0.
2. **Given** the Organiser creates a Custom Field without changing the sort index, **When** the field is saved, **Then** it is stored with sort index 0.
3. **Given** the Organiser edits an existing Custom Field, **When** they set the sort index to -5 (or any other whole number, negative or positive) and save, **Then** the new index is persisted and shown pre-filled the next time the edit form is opened.
4. **Given** the Organiser enters a value that is not a whole number (for example "abc" or "1.5") in the sort index, **When** they submit the form, **Then** the form is re-displayed with a clear validation message and nothing is saved.
5. **Given** Custom Fields exist from before this feature was introduced, **When** the Organiser views them, **Then** each of them has sort index 0 and therefore appears alphabetically.

---

### User Story 2 - Participants see Custom Fields in the configured order (Priority: P1)

A Participant filling in their profile, viewing another Participant's details, or scanning the Participants directory sees the Custom Fields in the exact order the Organiser configured: ascending by sort index, then alphabetically by label within the same index. The same order applies everywhere Custom Fields are listed, so the position of a field is consistent across all Participant-facing views.

**Why this priority**: The whole point of assigning an index is that Participants experience the intended order. This story turns the stored index into visible value and is co-equal with Story 1.

**Independent Test**: Can be fully tested by configuring fields "Zeta" (index -1), "Alpha" (index 0), "Mid" (index 0) and "Omega" (index 3), then opening the profile form, the Participants directory and a Participant detail view and confirming the order Zeta, Alpha, Mid, Omega in each.

**Acceptance Scenarios**:

1. **Given** the field configuration Zeta (-1), Alpha (0), Mid (0), Omega (3), **When** a Participant opens their profile edit form, **Then** the fields are presented in the order Zeta, Alpha, Mid, Omega.
2. **Given** the same configuration and all four fields marked as public, **When** any Participant opens another Participant's detail view, **Then** the fields appear in the order Zeta, Alpha, Mid, Omega.
3. **Given** the same configuration with all four fields marked as overview fields, **When** a Participant opens the Participants directory, **Then** the overview columns are laid out left-to-right in the order Zeta, Alpha, Mid, Omega, and every row's cells align with that column order.
4. **Given** two fields with the same index whose labels differ only in letter case (for example "apple" and "Banana"), **When** they are listed in any view, **Then** "apple" precedes "Banana" — ties are broken alphabetically without regard to case.
5. **Given** the Organiser changes a field's index, **When** a Participant next loads any view listing Custom Fields, **Then** the new order is already reflected without any further action.

---

### User Story 3 - Organiser views match the Participant order (Priority: P2)

Every Organiser-facing screen that lists Custom Fields — the Custom Fields management list, the Organiser's Participant detail view, and any selection list of Custom Fields (such as when choosing a field for a compliance rule) — shows the fields in the very same order Participants see. The Organiser can therefore verify the ordering result directly in the management list without switching to a Participant view, and the management list acts as a preview of the configured order.

**Why this priority**: Consistency between Organiser and Participant views is explicitly requested and removes guesswork, but the Organiser-side ordering does not block the Participant-facing outcome, so it ranks slightly below Stories 1 and 2.

**Independent Test**: Can be fully tested by configuring an order as in Story 2 and confirming that the Organiser's Custom Fields list, the Organiser's Participant detail view and the compliance rule field dropdown all present the fields in the order Zeta, Alpha, Mid, Omega.

**Acceptance Scenarios**:

1. **Given** the field configuration from Story 2, **When** the Organiser opens the Custom Fields management list, **Then** the rows appear in the order Zeta, Alpha, Mid, Omega, and the sort index of each field is visible in the list.
2. **Given** the same configuration, **When** the Organiser opens a Participant's detail view, **Then** that Participant's Custom Field values appear in the order Zeta, Alpha, Mid, Omega.
3. **Given** the same configuration, **When** the Organiser opens a form offering a choice of Custom Fields (for example the compliance rule form), **Then** the choices are listed in the order Zeta, Alpha, Mid, Omega.
4. **Given** the Organiser has just saved a changed sort index, **When** they are returned to the Custom Fields list, **Then** the list already reflects the new position of the edited field.

---

### User Story 4 - Organiser orders the options of a select field (Priority: P2)

An Organiser managing a single-select or multi-select Custom Field wants the choices offered to Participants in a deliberate order — for example T-shirt sizes as S, M, L, XL rather than alphabetically (L, M, S, XL). Each option carries its own whole-number sort index, default 0, that the Organiser enters when adding the option and can change later on the field's edit page. Options follow the same rule as fields: lower index first, ties alphabetical by label, identical labels oldest first. The resulting order applies wherever options are offered or displayed.

**Why this priority**: It completes the ordering story for select-type fields, but it depends on nothing beyond the field-level work and is not needed for the core outcome of ordering fields themselves.

**Independent Test**: Can be fully tested by creating a single-select field with options "L", "M", "S" (added in that order), assigning indices S=1, M=2, L=3, then opening the registration form and a Participant's detail and confirming the choices read S, M, L.

**Acceptance Scenarios**:

1. **Given** the Organiser is on a select-type field's edit page, **When** they add an option with label "M" and sort index 2, **Then** the option is stored with index 2 and the edit page lists it with that index.
2. **Given** an option was added without a sort index, **When** it is stored, **Then** its index is 0.
3. **Given** existing options L (0), M (0), S (0), **When** the Organiser changes their indices on the edit page to L=3, M=2, S=1 and saves each, **Then** the edit page lists them S, M, L with the new indices.
4. **Given** options S (1), M (2), L (3), **When** a Participant opens the registration or self-edit form, **Then** the choices for that field are offered in the order S, M, L.
5. **Given** the same options and a Participant who selected L and S, **When** anyone views that Participant's detail or the directory cell for that field, **Then** the selected options are shown in the order S, L.
6. **Given** the Organiser enters a non-numeric sort index for an option, **When** they submit, **Then** the edit page is re-displayed with a clear validation message and nothing is stored.
7. **Given** two options at the same index whose labels differ only in case, **When** they are listed, **Then** they are ordered alphabetically without regard to case.

---

### Edge Cases

- What happens when every field has index 0? The entire list is simply alphabetical by label, which is the pre-existing default behaviour and must remain unchanged for installations that never use the index.
- What happens when several fields share a non-zero index? They form a contiguous block at that index, ordered alphabetically among themselves, positioned before higher indices and after lower ones.
- What happens when two fields have identical labels and the same index? Both are shown adjacent to each other, with the field created earlier first; this order is therefore identical across repeated loads of the same view.
- How does the system handle the Country field? It participates in ordering exactly like any other field, using its own index and its label; when it is disabled it is omitted from Participant forms as today, without affecting the order of the remaining fields.
- What happens when an extremely large or small index is entered (for example 999999999 or -999999999)? The value is accepted as long as it is a whole number within the platform's standard signed 32-bit integer range; anything outside that range is rejected with the same validation message as a non-numeric entry.
- What happens when the sort index input is submitted empty? The field is treated as 0 (the default) rather than being rejected, so an Organiser clearing the input effectively resets the field to default ordering.
- Does the ordering apply to the options of a select-type field? Yes — each option has its own sort index (default 0) and the same index-then-label-then-creation rule applies within its field; a field's index and its options' indices are independent of each other.
- What happens to the options entered while creating a new select-type field? They are all stored at index 0 and therefore appear alphabetically until the Organiser assigns indices on the edit page.
- What happens when an option's sort index is changed while Participants have already selected it? Nothing besides its position: selections are preserved, and the option simply moves in every listing on the next load.
- Does a disabled or non-public field affect the ordering of other fields in a view where it is hidden? No — hidden fields are simply absent; the visible fields keep their relative order.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Every Custom Field definition MUST carry a whole-number sort index; a field created without an explicit index MUST receive the index 0.
- **FR-002**: All Custom Field definitions that existed before this feature was introduced MUST be treated as having sort index 0.
- **FR-003**: The Organiser MUST be able to set the sort index when creating a Custom Field and to change it when editing one; the create form MUST pre-fill 0 and the edit form MUST pre-fill the field's current index.
- **FR-004**: The sort index MUST accept negative, zero and positive whole numbers within the standard signed 32-bit integer range; an empty submission MUST be treated as 0.
- **FR-005**: A submitted sort index that is not a whole number, or lies outside the accepted range, MUST be rejected with a user-friendly validation message and MUST NOT alter any stored data.
- **FR-006**: Changing the sort index MUST be possible independently of every other restriction on a Custom Field — in particular it MUST remain editable when the field type is locked because Participant values already exist, and MUST be editable on the Country field.
- **FR-007**: Wherever Custom Fields are listed, they MUST be ordered first by sort index ascending (lower numbers first), then by label alphabetically, case-insensitively, for fields sharing the same index.
- **FR-008**: The ordering rule in FR-007 MUST be applied in every Participant-facing view that lists Custom Fields, including at least: the registration / profile edit form, the Participant detail view, and the overview columns of the Participants directory.
- **FR-009**: The ordering rule in FR-007 MUST be applied in every Organiser-facing view that lists Custom Fields, including at least: the Custom Fields management list, the Organiser's Participant detail view, and any form that offers a choice among Custom Fields (such as the compliance rule form).
- **FR-010**: The Organiser's Custom Fields management list MUST display each field's sort index so the Organiser can see the configured value without opening each field.
- **FR-011**: A change to a sort index MUST be reflected in all views on their next load; no additional publishing or refresh action by the Organiser is required.
- **FR-012**: Two fields with the same index and identical labels MUST be ordered by creation time, the earlier-created field first, so their relative order is identical across repeated loads of the same view.
- **FR-013**: The ordering MUST NOT change which fields are visible in any view; existing visibility rules (public, overview, enabled, required) continue to determine presence, and the ordering applies only to the fields that are already visible.
- **FR-014**: Changes to a field's sort index are NOT audited; Custom Field configuration is outside the audit trail today and this feature does not extend the audit trail. The same applies to option sort indices.
- **FR-015**: Every option of a single-select or multi-select Custom Field MUST carry a whole-number sort index, default 0; options that existed before this feature, and options entered while creating a new field, MUST be treated as index 0.
- **FR-016**: The Organiser MUST be able to set an option's sort index when adding the option on the field's edit page and to change it afterwards for each existing option on that page; the page MUST show each option's current index.
- **FR-017**: An option's sort index MUST accept the same range as a field's (signed 32-bit whole numbers), MUST treat an empty submission as 0, and MUST reject any other input with a user-friendly message and no stored change.
- **FR-018**: Wherever the options of a field are offered or displayed, they MUST be ordered first by option sort index ascending, then by option label alphabetically case-insensitively, then by creation time oldest first — including at least: the choices on the registration and self-edit forms, the Organiser's edit page for the field, and the selected options shown in the Participants directory, both Participant detail views, and any other page that lists a Participant's selected options.
- **FR-019**: Changing an option's sort index MUST NOT alter which Participants have selected it or which options exist; it changes position only.

### Key Entities *(include if feature involves data)*

- **Custom Field Definition**: An Organiser-defined field that Participants fill in. Gains a **sort index** — a whole number, default 0 — that, together with the label and, as a final tie-breaker, the creation time, determines the field's position in every listing. All other attributes (label, type, required, public, overview, enabled) are unchanged.
- **Custom Field Option**: One selectable choice belonging to a single-select or multi-select Custom Field. Gains a **sort index** — a whole number, default 0 — that, together with the option label and creation time, determines the option's position within its field wherever options are offered or shown. Independent of the owning field's sort index.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An Organiser can reposition a Custom Field by editing a single number and confirm the new position in the management list within one page reload.
- **SC-002**: 100% of views that list Custom Fields (Organiser and Participant) present them in the identical order for the same configuration.
- **SC-003**: Installations that never set a sort index observe no change in field ordering compared with before this feature — all fields remain alphabetical.
- **SC-004**: Every invalid sort index entry is rejected with a message an Organiser can act on, and no invalid entry ever results in a stored value.
- **SC-005**: Pages that list Custom Fields load as quickly as before the feature was introduced; the ordering adds no user-perceivable delay.
- **SC-006**: 100% of places that offer or display a select field's options present them in the identical order for the same configuration, and an Organiser can reorder a field's options by editing one number per option.

## Assumptions

- "Sorted" refers to the display order of Custom Field definitions across the application, configured by the Organiser through a numeric index; it does not mean Participants can interactively re-sort tables themselves.
- Lower index values come first ("index -1 appears before index 0, which appears before index 1"); this is the natural reading of the description and matches common ordering conventions.
- Alphabetical tie-breaking compares labels case-insensitively so that mixed-case labels do not produce surprising groupings. Accented or umlaut initial letters (for example "Ärzte") are not folded to their base letter and therefore sort after "Z" within their index group, matching how Participant names are already ordered in the directory; locale-aware collation is a possible later refinement, not part of this feature.
- The sort index is a single global value per field; there is no per-view or per-Participant override.
- Options inside a select-type Custom Field are ordered by their own sort index with the same rule as fields; option labels remain non-editable as today, so the edit page offers only the index per existing option.
- The management form for Custom Fields (and, for options, the field's edit page) is the only place an index is edited; no drag-and-drop reordering is introduced.
- Existing visibility rules (public, overview, enabled, required) are unaffected; the feature only orders the fields each view already shows.
- The audit trail is untouched: Custom Field configuration changes (including the sort index) are not audited, as is the case today.
