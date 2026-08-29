# Feature Specification: Homepage Overview, Self-Service Registration & Topics

**Feature Branch**: `003-homepage-overview-participant`

**Created**: 2026-08-29

**Status**: Draft

**Input**: User description: "Homepage when calling it. giving an overview.
two colums:

Left:

Registration status (Participant or not, group/topic assigned, if any)
if not register just a button leading to participant registration. Otherwise a button to revoke registration. (Registration, and Revocation can be activated and deactivated by organiser setings. (new tab with organiser settings)

When an organiser, in the top menu is also a link to the organiser pages, but just when authenticated.

below it is on the left page the list of registered topics (overview with contacts, who did it. Own topics can be edited. New topics can be proposed. New topics need to be approved by organisers.  (Approval can be enabled or disabled, organisers can also edit the author field and all topics)

On the right is a rendered markdown file.

More pages are on an info tab in the menu, where multiple markdown pages can be added and arragned (also organiser level)"

## Clarifications

### Session 2026-08-29

- Q: When topic approval is required, should a newly proposed topic be visible to other participants while it's still Pending, or hidden until an organiser approves it? → A: Hidden — only the author and Organisers can see a Pending topic; other users see it only once approved.
- Q: What determines the markdown content shown in the homepage's right column? → A: One page from the same pool used for the Info section, designated by an Organiser as "the homepage page."
- Q: Does the self-service "Register" action take the user through a form that captures required Custom Fields and Skill selections at registration time, or does it create a bare Active Participant record immediately? → A: The registration flow presents all Custom Fields and the Skill catalog for the user to fill in before the Participant record is created; required fields must be completed to submit. *(Superseded below — reversed to a bare-record registration.)*
- Q: When a Participant revokes their own registration, should the system automatically remove them from any Group they currently belong to? → A: Yes — revoking automatically removes the Participant from their current Group's membership; the Group's historical record still shows they were once a member.
- Q: Can a single Participant author/propose more than one Topic, or is each Participant limited to at most one? → A: Unlimited — a Participant may author any number of Topics; the propose action always stays available to them.
- Q: What contact information should the topic list show for each topic's author? → A: The author's display name together with their OIDC subject identifier shown in brackets — both already stored on the User record from the core domain model; no new contact field is introduced.
- Q: Should self-registration capture Custom Fields and Skill selections in a detailed form now? → A: No — keep it simple for now. Registering creates a bare Active Participant record immediately, with no field-capture form; detailed onboarding (Custom Field values, Skill selections) is deferred to a later, more detailed feature.
- Q: Should proposed Topics carry Skill associations in this feature? → A: No — keep it simple for now. A Topic has only a name and description; Skill association on Topics (and the resulting topic/skill matching this enables) is deferred to a later, more detailed feature.
- Q: When an organiser tries to delete an uploaded image still referenced by a Content Page's markdown, should the system block the deletion or delete it anyway and leave a broken image? → A: Block deletion — consistent with how the core domain model already blocks deleting a Skill/Custom Field still referenced by data; the organiser must remove the reference first.
- Q: What's the maximum file size an uploaded Content Image is allowed to be? → A: 5 MB.
- Q: Should an Organiser be able to edit an already-uploaded Content Image's alt text after the fact, without deleting and re-uploading it? → A: Yes, alt text only — the Organiser can edit an image's alt text in place; the image file and its stable reference remain unchanged.
- Q: Does the WCAG 2.1 AA requirement (FR-030) apply only to the new screens this feature adds, or to the pre-existing organiser UI from feature 002 (Groups, Skills, Custom Fields management) as well? → A: New UI only — accessibility conformance is scoped strictly to screens and controls this feature introduces; feature 002's existing organiser screens are out of scope here and would need their own accessibility pass if desired later.
- Q: When a Participant or Organiser can see a Pending topic, should it appear inline within the same topic list as approved topics, or in a visually separate section? → A: Inline, tagged "Pending approval" — but the list is grouped top to bottom: (1) topics pending approval visible to the viewer, (2) the viewer's own approved topics, (3) all other approved topics; each group ordered by creation date, and each topic appears in exactly one group.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Homepage Shows Registration Status & Self-Service Actions (Priority: P1)

An authenticated user visits the homepage and immediately sees whether they are a registered Participant, their current status, and their assigned Group/Topic if any. If they are not registered, they see a clear path to register; if they are registered, they see a way to revoke their own registration.

**Why this priority**: This is the primary entry point of the application and the first self-service capability participants gain — without it, registration still requires an Organiser to act on the user's behalf (as in the prior feature), which does not scale.

**Independent Test**: Can be fully tested by logging in as a user with no Participant record, confirming a "Register" action is shown, clicking it, and confirming the homepage now shows Active status, any assigned Group/Topic, and a "Revoke Registration" action instead.

**Acceptance Scenarios**:

1. **Given** an authenticated user with no Participant record and registration currently enabled, **When** they visit the homepage, **Then** they see a "Register" action and no revoke action.
2. **Given** an authenticated user with an Active Participant record and revocation currently enabled, **When** they visit the homepage, **Then** they see their status, their assigned Group/Topic (if any), and a "Revoke Registration" action instead of a "Register" action.
3. **Given** an authenticated Participant with an assigned Group and Topic, **When** they visit the homepage, **Then** the assigned Group/Topic is displayed alongside their status.
4. **Given** a Participant clicks "Revoke Registration", **When** the action completes, **Then** their status becomes Revoked and the homepage subsequently shows the "Register" action again (if registration is enabled).
5. **Given** a Participant who is currently a member of a Group clicks "Revoke Registration", **When** the action completes, **Then** they are removed from that Group's membership while the Group's historical record still shows they were once a member.
6. **Given** a user with no Participant record clicks "Register", **When** the action completes, **Then** an Active Participant record is created for them immediately, with no intermediate form to fill in.
7. **Given** a Participant clicks "Revoke Registration", **When** the confirmation prompt appears, **Then** it states that their current Group membership (if any) will also be removed, and the action only proceeds once they confirm.

---

### User Story 2 - Organiser Controls Registration & Revocation Availability (Priority: P2)

An Organiser opens a dedicated organiser settings area and independently enables or disables whether users may self-register as Participants and whether Participants may self-revoke their registration.

**Why this priority**: This governs when Story 1's actions are available; it depends on Story 1 existing but is independently valuable as a control an Organiser can exercise (e.g., closing registration once the hackathon roster is final).

**Independent Test**: Can be fully tested by an Organiser disabling registration in the settings area, then confirming an unregistered user's homepage no longer shows a "Register" action, and re-enabling it to confirm the action reappears. The same is tested for revocation.

**Acceptance Scenarios**:

1. **Given** an Organiser is on the organiser settings area, **When** they disable self-registration, **Then** no unregistered user subsequently sees a "Register" action on the homepage.
2. **Given** an Organiser is on the organiser settings area, **When** they disable self-revocation, **Then** no registered Participant subsequently sees a "Revoke Registration" action on the homepage.
3. **Given** an Organiser re-enables either setting, **When** the change is saved, **Then** the corresponding action reappears for eligible users immediately.
4. **Given** a user is authenticated and holds the Organiser privilege, **When** they view the top navigation menu, **Then** they see a link to the organiser area (including organiser settings); non-Organiser users do not see this link.

---

### User Story 3 - Browse and Propose Topics (Priority: P2)

Any registered Participant browses the list of topics on the homepage, sees each topic's overview (including who proposed it, shown as display name with OIDC subject identifier), and can propose a new topic of their own. Participants can edit topics they authored.

**Why this priority**: Topic proposal is the second major self-service capability the feature adds (previously Organiser-only per the prior feature). It depends on the core Topic data model already existing but delivers standalone value once available.

**Independent Test**: Can be fully tested by a Participant proposing a new Topic with a name and description, confirming it appears in the topic list with their contact info attached, then editing that topic and confirming the change is saved and visible.

**Acceptance Scenarios**:

1. **Given** an authenticated Participant is on the homepage, **When** they view the topic list, **Then** they see each topic's name, description, and the proposer's display name with their OIDC subject identifier shown in brackets.
2. **Given** an authenticated Participant, **When** they propose a new topic with a name and description, **Then** the topic is created with them recorded as its author.
3. **Given** a Participant viewing a topic they authored, **When** they edit its details, **Then** the changes are saved and reflected in the topic list for all users.
4. **Given** a Participant viewing a topic authored by someone else, **When** they look for an edit action, **Then** none is available to them.
5. **Given** a Participant has a Pending topic they authored, **When** they view the topic list, **Then** it appears at the top of their view labeled "Pending approval", followed by their own Approved topics, followed by all other Approved topics.

---

### User Story 4 - Organiser Approves and Administers Topics (Priority: P2)

An Organiser controls whether newly proposed topics require approval before becoming visible to other users, reviews and approves pending topics, and can edit any topic's details — including reassigning its author — regardless of who proposed it.

**Why this priority**: This is the governance counterpart to Story 3; it depends on topics existing but delivers independent value as a moderation and data-correction capability.

**Independent Test**: Can be fully tested by an Organiser enabling the approval requirement, having a Participant propose a topic, confirming it is Pending, approving it, and confirming its state changes accordingly. Separately, an Organiser edits an existing topic's author field and confirms the change persists.

**Acceptance Scenarios**:

1. **Given** an Organiser is on the organiser settings area, **When** they enable the topic-approval requirement, **Then** every topic subsequently proposed starts in a Pending state until approved.
2. **Given** an Organiser is on the organiser settings area, **When** they disable the topic-approval requirement, **Then** newly proposed topics no longer require approval.
3. **Given** a Pending topic, **When** an Organiser approves it, **Then** its state changes to Approved and it is treated the same as any other approved topic thereafter.
4. **Given** any topic, **When** an Organiser edits its name, description, or author, **Then** the changes are saved and reflected everywhere the topic is shown.
5. **Given** multiple Pending topics exist from different authors, **When** an Organiser views the topic list, **Then** all Pending topics appear together at the top (ordered by creation date), ahead of any Approved topics.

---

### User Story 5 - View Rendered Homepage & Info Content (Priority: P3)

Any authenticated user sees a rendered (not raw) markdown document in the homepage's right-hand column, and can navigate to an "Info" section in the menu to read additional markdown pages that have been arranged there.

**Why this priority**: This delivers informational/orientation content but does not gate any workflow — it can be built and demonstrated independently of the registration and topic capabilities.

**Independent Test**: Can be fully tested by publishing markdown content and confirming it renders as formatted HTML on the homepage's right column and in the Info section, in the arranged order.

**Acceptance Scenarios**:

1. **Given** homepage content has been published, **When** an authenticated user visits the homepage, **Then** the right column displays that content rendered as formatted HTML (headings, lists, links, emphasis).
2. **Given** one or more Info pages have been published and arranged, **When** an authenticated user opens the Info section, **Then** they see all pages listed in the arranged order and can open each to read its rendered content.

---

### User Story 6 - Organiser Manages Info & Homepage Content Pages (Priority: P3)

An Organiser adds, edits, removes, and reorders the markdown pages that appear under the Info section, and manages the content shown in the homepage's right column. Each page's edit view includes a sort index the Organiser can set directly to control its position among the other pages.

**Why this priority**: This is the authoring counterpart to Story 5; it depends on the content-display mechanism existing but is independently testable as a content-management capability.

**Independent Test**: Can be fully tested by an Organiser adding a new markdown page, confirming it appears in the Info section, changing its sort index relative to existing pages, and confirming the new order is reflected for all users.

**Acceptance Scenarios**:

1. **Given** an Organiser is on the content management view, **When** they add a new markdown page with a title and body, **Then** it appears in the Info section for all users.
2. **Given** an Organiser is editing a page, **When** they set or change its sort index, **Then** the Info section reorders all pages ascending by that index for all users.
3. **Given** an Organiser is on the content management view, **When** they remove a page, **Then** it no longer appears in the Info section.
4. **Given** a non-Organiser user, **When** they look for page-management actions, **Then** none are available to them.

---

### User Story 7 - Organiser Uploads and Embeds Images in Content Pages (Priority: P3)

An Organiser uploads image files to a shared image library, and embeds any uploaded image inside a Content Page's markdown body using standard markdown image syntax, so it renders inline wherever that page is shown.

**Why this priority**: This extends the content-authoring capability (Story 6) with visual media; it depends on the markdown rendering pipeline already existing but is independently testable as its own upload/embed round trip.

**Independent Test**: Can be fully tested by an Organiser uploading an image, copying the reference/syntax the system provides for it, pasting that into a Content Page's markdown body, saving, and confirming the image renders inline on that page.

**Acceptance Scenarios**:

1. **Given** an Organiser is on the content management view, **When** they upload an image file, **Then** it is stored and added to the image library with a stable reference they can use in markdown.
2. **Given** an Organiser has just uploaded an image, **When** they look at the image library, **Then** they see the exact markdown syntax to embed that image, ready to copy into a page's body.
3. **Given** a Content Page's markdown body references an uploaded image using the provided syntax, **When** that page is rendered (on the homepage or in the Info section), **Then** the image displays inline at that position.
4. **Given** an uploaded image that is still referenced by at least one Content Page's markdown, **When** an Organiser attempts to delete it, **Then** the deletion is blocked and the Organiser is told which page(s) still reference it.
5. **Given** an uploaded image with no remaining references, **When** an Organiser deletes it, **Then** it is removed from the image library.
6. **Given** a non-Organiser user, **When** they look for image upload or management actions, **Then** none are available to them.
7. **Given** an Organiser is viewing the image library, **When** they edit an existing image's alt text and save, **Then** the new alt text is stored on the Content Image and reflected in the copyable markdown syntax shown for it going forward, while the image file and its stable reference remain unchanged; Content Pages that already embedded the old markdown syntax keep the alt text they were pasted with until an Organiser re-pastes the updated syntax into them.

---

### Edge Cases

- What happens when a user who is already an Active Participant somehow triggers the registration action again (e.g., stale page, double-submit)? The system MUST NOT create a second Participant record or duplicate registration; it MUST leave the existing record unchanged (consistent with the at-most-one-Participant-record rule).
- What happens when registration or revocation is disabled while a user already has the corresponding action's page open? The action MUST be rejected server-side even if the button was rendered before the setting changed, and the user MUST be shown the current, accurate state on their next view.
- What happens when a Standard user (not yet a Participant) attempts to propose a topic? The action MUST be denied; only registered Participants may propose topics.
- What happens when the author of a topic later has their Participant status revoked? The topic MUST retain the historical author reference and remain listed, consistent with the core domain model's historical record-keeping.
- What happens when an Organiser disables the topic-approval requirement while topics are already Pending? Those topics MUST remain Pending until an Organiser explicitly approves them; the setting change is not retroactive.
- What happens when submitted markdown content includes scripts or other active content? The system MUST render markdown as sanitized HTML, stripping any executable content before display.
- What happens when the Info section has no pages configured yet? It MUST display a clear empty state rather than an error.
- What happens when an Organiser attempts to edit a topic that a Participant is simultaneously editing? The system MUST apply a last-write-wins save and MUST NOT corrupt or merge partial data from both edits.
- What happens when a Participant who is not a topic's author tries to access that topic directly while it is still Pending? Access MUST be denied, consistent with it being hidden from everyone but the author and Organisers.
- What happens when an Organiser removes the Content Page currently designated as the homepage page? The homepage right column MUST show a clear empty/unset state rather than an error, until an Organiser designates a replacement.
- What happens when a Participant with no current Group revokes their registration? Revocation MUST proceed normally with no Group-membership change to apply.
- What happens when two Content Pages are given the same sort index? Their relative order MUST remain stable and deterministic (e.g., falling back to creation order) rather than fluctuating between views.
- What happens when an Organiser attempts to delete an uploaded image that is still referenced by at least one Content Page's markdown? The deletion MUST be blocked, and the Organiser MUST be told which page(s) still reference it, consistent with how the core domain model blocks removal of referenced Skills/Custom Fields.
- What happens when someone uploads a non-image file, or a file exceeding 5 MB? The upload MUST be rejected with a clear reason, and the system MUST NOT store the invalid file.
- What happens when a Content Page's markdown references an image that was already removed or never existed? The page MUST still render, showing a broken-image indicator at that position rather than failing to render the rest of the page.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST present a homepage to every authenticated user as the default landing view, laid out as two areas: a left area (registration status and topics) and a right area (rendered content).
- **FR-001a**: The left and right areas MUST each carry a clear, distinct label identifying their purpose (e.g., a heading such as "Your Registration & Topics" for the left area). On narrow viewports, the areas MUST stack vertically in this order: registration status, then topic list, then right-column content — so the most actionable information appears first for a user unfamiliar with the layout.
- **FR-002**: Left area MUST show whether the current user has a Participant record, and if so, its current status and its assigned Group/Topic, if any.
- **FR-003**: When the current user has no Participant record and self-registration is enabled, the left area MUST present a "Register" action that, when used, immediately creates an Active Participant record for that user with no intermediate data-entry form. Capturing Custom Field values and Skill selections during registration is out of scope for this feature and deferred to a later, more detailed onboarding feature.
- **FR-003a**: The "Register" action MUST be accompanied by a short explanatory line stating what registering means (e.g., that the user is joining as a hackathon Participant), and MUST show a clear confirmation once the action completes successfully.
- **FR-004**: When the current user has an Active Participant record and self-revocation is enabled, the left area MUST present a "Revoke Registration" action in place of the "Register" action.
- **FR-004a**: The "Revoke Registration" action MUST require the user to explicitly confirm before it takes effect, and the confirmation prompt MUST state that any current Group membership will also be removed.
- **FR-005**: System MUST allow an Organiser to independently enable or disable self-registration and self-revocation via a dedicated organiser settings area.
- **FR-005a**: Each organiser setting toggle (self-registration, self-revocation, topic-approval) MUST display its current effect in plain language next to the control (e.g., "Users can currently self-register"), in addition to its on/off state.
- **FR-006**: System MUST enforce the current registration/revocation settings on every registration or revocation attempt, regardless of what the requesting user's page displayed at load time.
- **FR-007**: System MUST set a Participant's status to Revoked when they use the "Revoke Registration" action, without deleting their record, and MUST allow that same record to be reactivated to Active status through a subsequent registration action while registration is enabled.
- **FR-007a**: When a Participant's status becomes Revoked via self-revocation, System MUST remove them from their current Group's membership, if any, while preserving that Group's historical record of their former membership.
- **FR-008**: Top navigation MUST include a link to the organiser area, including organiser settings, visible only to authenticated users holding the Organiser privilege.
- **FR-008a**: The organiser navigation link MUST be visually distinguished from standard navigation items via an icon or label carrying an accessible name (not by color alone), so an Organiser readily recognizes their elevated access regardless of how they perceive the page.
- **FR-009**: Left area MUST list existing topics, showing at minimum each topic's name, description, and the proposing author's display name together with their OIDC subject identifier shown in brackets.
- **FR-009a**: The topic list MUST order the topics visible to the current viewer into three groups, top to bottom: (1) Pending topics visible to the viewer per FR-012a (all Pending topics for an Organiser; only the viewer's own Pending topics for a Participant), (2) the viewer's own Approved topics, (3) all other Approved topics. Within each group, topics MUST be ordered by creation date. Each topic MUST appear in exactly one group — a Pending topic authored by the viewer appears only in group (1), not duplicated in group (2).
- **FR-010**: System MUST allow any registered Participant to propose a new Topic, recording them as its author, with no limit on how many Topics a single Participant may author.
- **FR-011**: System MUST allow a Participant to edit a Topic they authored; System MUST NOT allow a Participant to edit a Topic authored by someone else.
- **FR-012**: System MUST allow an Organiser to enable or disable a requirement that newly proposed topics be approved before becoming visible to users other than their author.
- **FR-012a**: While a Topic is Pending, it MUST be visible only to its author and to users holding the Organiser privilege; it MUST NOT appear in the topic list shown to any other user until approved.
- **FR-012b**: When a Pending topic is shown to its author or to an Organiser, the topic list MUST visually mark it with a distinct "Pending approval" status label, so its author can locate it among approved topics without confusion.
- **FR-013**: When the approval requirement is enabled, every newly proposed Topic MUST start in a Pending state; when disabled, newly proposed topics MUST be treated as immediately Approved.
- **FR-014**: System MUST allow an Organiser to approve a Pending Topic, after which it is treated the same as any topic that did not require approval.
- **FR-015**: System MUST allow an Organiser to edit any Topic's fields, including reassigning its recorded author to a different user, regardless of who originally proposed it.
- **FR-016**: Disabling the approval requirement MUST NOT automatically approve Topics already in a Pending state; each MUST still require explicit Organiser approval.
- **FR-017**: System MUST render the homepage's right-hand column and every Info page as formatted HTML from markdown source (supporting at minimum headings, lists, links, and emphasis), never as raw markdown text.
- **FR-018**: System MUST provide an "Info" navigation item listing markdown pages other than the homepage's right-column content, presented in an Organiser-defined order.
- **FR-019**: Homepage right-column content MUST be sourced from the same pool of markdown pages used for the Info section; System MUST allow an Organiser to designate exactly one such page as "the homepage page," and that designated page's content MUST be what renders in the right column.
- **FR-019a**: On first application startup, if no Content Page exists yet, System MUST seed a default placeholder Content Page and designate it as the homepage page, so a new deployment never presents an unset homepage right column; an Organiser MAY subsequently edit or replace this default content.
- **FR-020**: System MUST allow an Organiser to add, edit, remove, and reorder markdown pages available under the Info section.
- **FR-020a**: Each Content Page's edit view MUST expose a numeric sort index the Organiser can set directly; the Info section MUST order its pages ascending by that index.
- **FR-021**: System MUST NOT allow any non-Organiser user to add, edit, remove, or reorder markdown pages.
- **FR-022**: System MUST sanitize all markdown-derived HTML before rendering, so that no user-submitted script or other active content executes in another user's browser.
- **FR-023**: System MUST persist organiser settings (self-registration enabled, self-revocation enabled, topic-approval required) durably and apply the current value on every subsequent request, without requiring a restart or deployment.
- **FR-023a**: On first application startup, if no Organiser Settings record exists yet, System MUST create one with default values: self-registration enabled, self-revocation enabled, topic-approval not required.
- **FR-024**: System MUST allow an Organiser to upload image files to a shared image library, storing each image's binary data in the database rather than on the local filesystem.
- **FR-025**: System MUST assign each uploaded image a stable reference and MUST display, alongside each image in the library, the exact markdown syntax an Organiser can copy to embed that image in a Content Page's body.
- **FR-025a**: When uploading a Content Image, System MUST require the Organiser to provide a non-empty text description of the image; the markdown syntax System provides for that image (FR-025) MUST include this description as the image's alt text.
- **FR-025b**: System MUST allow an Organiser to edit a Content Image's alt-text description after upload without deleting or replacing the image file or its stable reference; the updated alt text MUST be reflected in the markdown syntax System subsequently displays for that image (FR-025), but System is NOT required to retroactively change alt text already copied into a Content Page's markdown body.
- **FR-026**: System MUST render an embedded image reference found in a Content Page's markdown as an inline image at that position in the formatted HTML output, preserving whatever alt text is present in the markdown source as the rendered image's alt attribute.
- **FR-027**: System MUST NOT allow any non-Organiser user to upload, remove, or manage images in the library.
- **FR-028**: System MUST block deletion of an uploaded image while any Content Page's markdown still references it, and MUST inform the Organiser which page(s) reference it.
- **FR-029**: System MUST reject an upload that is not a supported image format (PNG, JPEG, GIF, or WebP) or that exceeds 5 MB, without storing it.

### Accessibility Requirements (WCAG 2.1 AA)

- **FR-030**: All UI introduced by this feature (homepage, registration/revocation actions, topic list and propose/edit forms, Info section, organiser settings, Content Page and Content Image management) MUST conform to WCAG 2.1 Level AA. This requirement is scoped to screens and controls this feature introduces; it does NOT require a retrofit of pre-existing organiser UI delivered by the core domain model feature (e.g., Group, Skill, and Custom Field management screens).
- **FR-031**: Every interactive element introduced by this feature (buttons, links, toggles, form fields, the topic-propose/edit forms, the image upload control) MUST be operable using the keyboard alone, in a logical tab order, and MUST display a visible focus indicator when focused via keyboard.
- **FR-032**: Every form control introduced by this feature (Register/Revoke actions, topic name/description fields, Content Page fields including the sort index, Content Image upload and its alt-text field, organiser setting toggles) MUST have a programmatically associated label that assistive technology can read.
- **FR-033**: A status change that occurs without a full page reload (registration success, revocation confirmation, a topic's approval, a settings toggle taking effect) MUST be announced to assistive technology via an appropriately scoped live region, not communicated by visual change alone.
- **FR-034**: Any status conveyed by color (Participant status Active/Revoked, Topic status Pending/Approved, an organiser setting's enabled/disabled state) MUST also be conveyed by text or an icon with an accessible text alternative, so it remains distinguishable without relying on color perception.
- **FR-035**: The "Revoke Registration" confirmation prompt (FR-004a) MUST be programmatically identified as a dialog to assistive technology, MUST trap keyboard focus while open, and MUST return focus to a logical location in the page once it closes.
- **FR-036**: Rendered markdown content (the homepage right column, Info pages) MUST preserve a logical heading hierarchy: the page or content title MUST render as the top-level heading for that view, with any headings authored in the markdown body rendered at or below the next heading level, so assistive technology users can navigate the page by heading structure.
- **FR-037**: A validation error introduced by this feature (a rejected image upload, a failed topic save, a required field left empty) MUST be presented as text associated with the relevant field or action, not indicated by color or icon alone.
- **FR-038**: Text and interactive-element boundaries introduced by this feature MUST meet WCAG 2.1 AA contrast minimums (at least 4.5:1 for normal text, 3:1 for large text and for UI component boundaries such as input borders and focus indicators) in both the light and dark presentation of the UI, if both are supported.

### Key Entities

- **Organiser Settings**: A single, shared set of toggles controlling homepage self-service behavior for the whole hackathon: whether self-registration is enabled, whether self-revocation is enabled, and whether newly proposed topics require approval. A record is guaranteed to exist from first startup, seeded with defaults (registration enabled, revocation enabled, approval not required) per FR-023a.
- **Topic** *(as used by this feature)*: A name, a description, and an author. Now also carries an approval state (Pending or Approved) that gates visibility to non-author, non-Organiser users (FR-012a); its author is shown on the topic list by display name with their OIDC subject identifier in brackets, both already present on the author's User record, with no limit on how many Topics a single author may have (FR-010). Skill association on Topics, part of the core domain model, is not captured through this feature's propose/edit actions for now.
- **Content Page**: An Organiser-authored markdown document with a title, a markdown body, and a numeric sort index used to order it within the Info section (FR-020a). Exactly one Content Page may be designated by an Organiser as the homepage page, whose content supplies the homepage's right-column content (FR-019). A default placeholder page, designated as the homepage page, is seeded on first startup if none exists (FR-019a).
- **Content Image**: An Organiser-uploaded image file whose binary data is stored in the database, exposed via a stable reference and a required alt-text description (FR-025a) that an Organiser may edit in place after upload (FR-025b), that Content Page markdown bodies can embed using standard markdown image syntax (FR-024, FR-025). Cannot be deleted while any Content Page still references it (FR-028).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A user can go from having no Participant record to seeing their Active status reflected on the homepage in a single click, with no Organiser intervention required.
- **SC-002**: 100% of homepage loads show a "Register" or "Revoke Registration" action (or neither, when both are disabled) that matches the current organiser settings at the moment of the page load.
- **SC-003**: An Organiser can change the self-registration, self-revocation, or topic-approval setting and see it take effect for all users on their very next homepage or topic-proposal action, with no deployment.
- **SC-004**: A Participant can propose a new topic and see it reflect their authorship and contact information without needing an Organiser to create it on their behalf.
- **SC-005**: An Organiser can find and approve every Pending topic from the organiser views without needing direct database access.
- **SC-006**: 100% of markdown content shown on the homepage or the Info section renders as formatted output rather than visible markdown syntax, and contains no executable script content regardless of what was submitted.
- **SC-007**: An Organiser can publish a new Info page and have it appear, correctly positioned among existing pages, for all users within one page reload.
- **SC-008**: An Organiser can upload an image and have it appear, correctly rendered inline, on a Content Page within two actions (upload, then paste the provided syntax into the page body) — with no separate image-hosting step outside the system.
- **SC-009**: An automated accessibility scan of the homepage, topic propose/edit forms, this feature's new organiser settings controls (registration/revocation/approval toggles) and Content Page/Content Image management screens, and the Info section reports zero critical or serious WCAG 2.1 AA violations. Pre-existing feature-002 organiser screens are excluded from this scan.

## Assumptions

- The application requires authentication for all pages (per the core domain model's OIDC-only authentication); the homepage described here is reached only by already-authenticated users, not anonymous visitors.
- The system continues to operate in the context of a single ongoing hackathon (per the core domain model), so Organiser Settings are a single global set of toggles rather than per-event configuration.
- This feature supersedes the core domain model's prior assumption that registration and topic creation are performed only by an Organiser on a user's behalf: self-service registration, self-revocation, and self-service topic proposal are now delivered directly to end users, subject to the toggles this feature introduces.
- Only registered Participants (not Standard-only users) may propose topics; Standard-only users can view the topic list but see no propose/edit actions.
- Editing an already-approved Topic by its author does not re-trigger the approval requirement; the approval gate applies only to a topic's initial proposal.
- The Info section and homepage content are visible to any authenticated user (Standard, Participant, or Organiser alike); only the ability to manage (add/edit/remove/reorder) pages is restricted to Organisers.
- Detailed participant onboarding (capturing Custom Field values and Skill selections at registration time) and Skill association on Topics are intentionally deferred to a later, more detailed feature; this feature keeps self-service registration and topic proposal minimal (bare record / name+description only).
- The image library is a single shared pool available to embed in any Content Page, not scoped per-page; accepted formats are PNG, JPEG, GIF, and WebP, up to 5 MB per image (FR-029), to keep database storage manageable.
- A Content Page's sort index is a plain integer the Organiser sets directly (no separate drag-and-drop mechanism is assumed to be required); duplicate index values are permitted and resolved by a stable, deterministic secondary order.
- Default-value seeding (Organiser Settings, homepage placeholder Content Page — FR-023a, FR-019a) runs as part of application initialisation/startup logic, not as a one-off manual migration an Organiser must remember to perform.
- The topic-approval OIDC subject-in-brackets display (FR-009) is intentionally kept as specified: in the target deployment environment the OIDC subject is itself a human-readable email address, so no additional formatting or hover-only treatment is needed for it to remain legible to participants.
- WCAG 2.1 Level AA (not AAA) is the accessibility target for all UI introduced by this feature (FR-030–FR-038), consistent with common conformance targets for internal/organisational tools; automated scanning (SC-009) covers what tooling can verify, but manual screen-reader and keyboard-only testing is still expected before this feature ships.
- Within each of the three topic-list groups (FR-009a), "ordered by creation date" is assumed to mean oldest-first (FIFO), consistent with how duplicate Content Page sort indices fall back to creation order elsewhere in this spec; if a most-recent-first ordering is preferred, this is a low-effort reversal at implementation time.
