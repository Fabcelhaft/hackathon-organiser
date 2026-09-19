# Feature Specification: Topic Markdown Descriptions & Attachments

**Feature Branch**: `010-topic-markdown-attachments`

**Created**: 2026-09-18

**Status**: Draft

**Input**: User description: "Topic descriptions support markdown, reusing the existing content-page renderer. Both the participant and organiser topic forms make it clear that markdown is accepted. Bare URLs in a description become clickable links. A topic's author can upload files as attachments to the topic, shown on the topic detail view with download links."

## Clarifications

### Session 2026-09-18

- Q: Should images referenced by external web address inside a participant-written description be displayed inline? → A: Yes — allow images from any address, exactly as organiser content pages do today. One shared rendering and safety policy applies to both; the privacy trade-off (a reader's browser fetching a third-party resource) is accepted.
- Q: What kinds of files may be uploaded as Topic attachments? → A: An allowlist of common document and image types only — PDF, office documents (Word, Excel, PowerPoint and their open-document equivalents), plain text and markdown, images (PNG, JPEG, GIF, WebP), and ZIP archives. Everything else is rejected with a message naming the accepted types.
- Q: Should bare-address linking and new-tab links also apply to organiser content pages, or only to Topic descriptions? → A: Both apply everywhere the shared renderer is used — content pages (Info wiki, homepage, designated form content) and Topic descriptions behave identically.
- Q: Who may add and remove attachments on a Topic? → A: The Topic's author and organisers only — the same rule that already governs editing the Topic's other fields. Group members who are not the author get no attachment controls.
- Q: Are the attachment limits of 10 MB per file and 10 files per Topic the right ones? → A: Yes — confirmed as fixed limits for this feature, not configurable per instance.
- Q: Where should the rendered description sit on the Topic Details view? → A: In its own full-width section directly under the page heading, above the Topic Info table; the Description row leaves the table. The organiser Topic detail view follows the same layout.
- Q: Should an upload from the edit screen happen immediately as its own action, separate from the Save button for name, description and skills? → A: Yes — choosing a file and pressing Upload stores it at once and reloads the edit screen; the text fields keep their own Save button, and the attachments section tells the author to save text changes first. Unsaved text edits are not preserved across the upload.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Read a richly formatted Topic description (Priority: P1)

Anyone viewing a Topic's details sees its description rendered as formatted text — headings, paragraphs, lists, emphasis and links — instead of one flat block of plain text. A web address typed plainly into the description (without any link syntax) is shown as a clickable link. Formatting that could be unsafe never reaches a reader's browser.

**Why this priority**: The description is the one place a Topic's author explains what the Topic is about; long descriptions in plain text are hard to read. Rendering existing markdown-style text delivers value immediately, works for every Topic already stored, and every other slice of this feature builds on it.

**Independent Test**: Create a Topic whose description contains a heading, a bulleted list, bold text, an explicit markdown link and a bare web address; open its Topic Details view as a participant and as an organiser and confirm each element renders as formatting rather than literal symbols, and that both the explicit link and the bare address are clickable.

**Acceptance Scenarios**:

1. **Given** a Topic whose description uses markdown formatting, **When** any user opens its Topic Details view, **Then** the description renders with that formatting applied (headings, paragraphs, lists, emphasis, links) in its own full-width section directly under the page heading, and the Topic Info table no longer carries a Description row.
2. **Given** a Topic whose description contains a bare web address such as `https://example.org/docs`, **When** its Topic Details view renders, **Then** that address is presented as a clickable link whose text is the address itself.
3. **Given** a Topic description containing a heading, **When** the Topic Details view renders, **Then** the Topic's name remains the single top-level heading of the page and every description heading appears subordinate to it.
4. **Given** a Topic description containing scripting, event handlers or other unsafe markup, **When** the Topic Details view renders, **Then** the unsafe parts are removed and the rest of the description still renders normally.
5. **Given** an existing Topic whose description was written before this feature as plain prose with no markdown, **When** its Topic Details view renders, **Then** it reads the same as before: paragraphs are preserved and no text is lost or garbled.
6. **Given** a Topic's description contains a link, **When** a reader activates it, **Then** the linked page opens in a new browser tab and the Topic Details view stays open, so a reader is never navigated away from the app by another participant's link.
7. **Given** the organiser Topic detail view, **When** it renders a Topic's description, **Then** it applies the same rendering as the participant-facing Topic Details view.

---

### User Story 2 - Know that markdown is accepted while writing (Priority: P2)

A participant proposing or editing a Topic, and an organiser creating or editing one, can see from the form itself that the description accepts markdown, what basic formatting is available, and that plain web addresses become links automatically — without having to guess or consult documentation.

**Why this priority**: Rendering alone (Story 1) leaves authors unaware of what they can do; a visible hint turns the capability into something people actually use. It is a small, self-contained change to two existing forms.

**Independent Test**: Open the participant "Propose Topic" form and the organiser topic form and confirm each shows, next to the description field, that markdown is supported, with a short illustration of the basics, and that the hint is announced by assistive technology as the field's description.

**Acceptance Scenarios**:

1. **Given** a participant opens the Propose Topic or Edit Topic form, **When** the form renders, **Then** the description field is visibly labelled as accepting markdown and is accompanied by a short hint showing the basic formatting available (headings, emphasis, lists, links) and stating that bare web addresses become links.
2. **Given** an organiser opens the organiser topic create or edit form, **When** the form renders, **Then** the same markdown hint accompanies the description field.
3. **Given** the description field and its hint, **When** an assistive technology user focuses the field, **Then** the hint is announced as the field's description.
4. **Given** an author submits a description containing markdown, **When** they reopen the edit form, **Then** the description field contains exactly the markdown they wrote, not a rendered or altered version.

---

### User Story 3 - Attach files to a Topic (Priority: P3)

A Topic's author can attach files to their Topic — a slide deck, a sketch, a dataset, a PDF brief — from the Topic's edit screen. Everyone who can see the Topic sees its attachments listed on the Topic Details view, each with its name, size and a download link. The author can remove an attachment they no longer want. Organisers can manage attachments on any Topic.

**Why this priority**: Attachments extend what a Topic can communicate beyond text, but they are genuinely new capability with their own storage, limits and access rules, and the description improvements are useful without them.

**Independent Test**: As a Topic's author, upload two files from the Topic's edit screen, open the Topic Details view as another participant and confirm both files are listed with name, size and a working download link; remove one as the author and confirm it disappears; confirm a non-author participant is offered no upload or remove controls and cannot upload by any other means.

**Acceptance Scenarios**:

1. **Given** a participant is the author of an existing Topic, **When** they open its edit screen, **Then** they see an attachments section, separate from the Save button for the text fields, listing current attachments, an upload control for adding a file, and a note that text changes should be saved before uploading.
2. **Given** the author chooses a file within the allowed size and presses Upload, **When** the upload completes, **Then** the edit screen reloads with the attachment listed under its original file name and size and a confirmation shown, without the name, description or skills having been saved by that action.
3. **Given** a Topic has attachments, **When** any user who can see the Topic opens its Topic Details view, **Then** the attachments are listed with file name, size and a download link, and downloading delivers the file unchanged under its original name.
4. **Given** the author chooses a file larger than the allowed size, or the Topic already holds the maximum number of attachments, **When** they submit it, **Then** the upload is rejected with a message naming the limit, and the existing attachments are unchanged.
4a. **Given** the author chooses a file of a type that is not allowed (for example an executable or a script), **When** they submit it, **Then** the upload is rejected with a message listing the accepted types, and the existing attachments are unchanged.
5. **Given** the author submits the upload control with no file selected, **When** the request is processed, **Then** they are told to choose a file and nothing else changes.
6. **Given** a Topic has an attachment, **When** its author removes it, **Then** it disappears from the edit screen and from every Topic Details view, and its download link stops working.
7. **Given** a participant who is not the Topic's author, **When** they view the Topic Details view or attempt to reach the upload or removal actions directly, **Then** they see no such controls and any direct attempt is refused.
8. **Given** an organiser, **When** they open the organiser edit screen for any Topic, **Then** they can add and remove attachments exactly as the author can, and the organiser Topic detail view lists attachments with download links.
9. **Given** a Topic that is not visible to a given user (for example another participant's pending Topic), **When** that user tries to download one of its attachments directly, **Then** the download is refused, so attachment visibility never exceeds Topic visibility.
10. **Given** a Topic is deleted, **When** the deletion completes, **Then** its attachments are removed with it and their download links stop working.
11. **Given** a Topic is being created for the first time, **When** the Propose Topic form renders, **Then** no attachments section is offered; attachments can be added once the Topic exists, and the form says so.

---

### Edge Cases

- A description contains raw HTML or script: unsafe elements and attributes are stripped, safe formatting is kept, and the page still renders. This is the first time content written by participants (not organisers) is rendered as formatting, so the safety filtering must apply identically regardless of who wrote the description.
- A bare web address is followed by a full stop or comma, or wrapped in parentheses: the trailing punctuation is not swallowed into the link.
- A description uses very deep headings: they are capped at the deepest heading level available rather than rendering as literal text.
- A description is extremely long: it renders in full on the Topic Details view; no truncation is introduced by this feature.
- Two attachments on the same Topic have the same file name: both are stored and listed; the second is not rejected and the first is not overwritten.
- A file name contains characters that are unsafe in a download header or on screen: the name is shown safely and the download still carries a sensible file name.
- An upload is interrupted or arrives empty: nothing is stored and the author is told the upload failed.
- A file of a disallowed type is renamed to an allowed extension, or an allowed file arrives with a mismatched declared type: the upload is rejected, since both the declared type and the extension must be acceptable.
- A user follows a bookmarked download link for an attachment that has since been removed: the response reports "not found" rather than erroring.
- Attachment lists stay legible when a Topic holds the maximum number of files.
- The author edits the description, then uploads a file without saving first: the upload succeeds and the unsaved description edit is lost on reload; the note in the attachments section exists to warn of exactly this.
- Adding or removing an attachment never changes a Topic's approval status, matching how editing the Topic's other fields already behaves.

## Requirements *(mandatory)*

### Functional Requirements

#### Description rendering

- **FR-001**: System MUST render a Topic's description as formatted content on the participant-facing Topic Details view and on the organiser Topic detail view, supporting at least headings, paragraphs, ordered and unordered lists, emphasis, inline code, block quotes and links.
- **FR-001a**: On both detail views the rendered description MUST occupy its own full-width section directly under the page heading, above the Topic Info table; the Description row MUST be removed from that table so the description is shown exactly once.
- **FR-002**: System MUST turn bare web addresses (`http://` and `https://` forms) in a description into clickable links whose visible text is the address, without swallowing trailing punctuation.
- **FR-003**: System MUST remove scripting, event-handler attributes and any other unsafe markup from a rendered description, applying the same allowlist-based safety filtering already used for organiser-authored content pages, before it reaches any reader's browser.
- **FR-004**: Every link in a rendered description MUST open in a new browser tab and MUST NOT grant the destination page any access back to the app's window.
- **FR-004a**: Images referenced in a description MUST display inline, from any address, under the same rules that apply to organiser content pages; the description rendering MUST NOT introduce a separate, stricter image policy for participant-written content.
- **FR-004b**: Bare-address linking (FR-002) and new-tab links (FR-004) MUST apply identically to organiser content pages wherever they are rendered (Info wiki, homepage, designated form content), so the shared renderer has one behaviour everywhere.
- **FR-005**: Headings within a description MUST render subordinate to the Topic's name, so each detail view keeps exactly one top-level heading.
- **FR-006**: Descriptions written as plain text before this feature MUST continue to read as before, with paragraph breaks preserved and no content lost.
- **FR-007**: The description as stored MUST remain the exact text the author entered; rendering happens on display only, so the edit form always shows the author's original markdown.
- **FR-008**: Places that show a Topic's description other than the two detail views (none exist today) are out of scope; the home page and Topic overview tables MUST remain unchanged.

#### Authoring hints

- **FR-009**: The participant Propose/Edit Topic form and the organiser topic create/edit form MUST each label the description field as accepting markdown.
- **FR-010**: Each such form MUST show, adjacent to the description field, a short hint illustrating the basic formatting available and stating that bare web addresses become links.
- **FR-011**: The hint MUST be programmatically associated with the description field so assistive technology announces it as the field's description.

#### Attachments

- **FR-012**: System MUST allow a Topic's author to add file attachments to their existing Topic from the Topic's edit screen, and to remove any attachment from that Topic.
- **FR-012a**: Adding and removing an attachment MUST each be an immediate, self-contained action on the edit screen, independent of the Save action for the Topic's name, description and skills; completing either returns the author to the edit screen with the attachments list updated and a confirmation shown. The attachments section MUST state that unsaved text changes should be saved before uploading, since they are not carried across the upload.
- **FR-012b**: Removing an attachment MUST NOT require a separate confirmation step, consistent with how the existing image library removes files.
- **FR-013**: System MUST allow organisers to add and remove attachments on any Topic from the organiser topic edit screen.
- **FR-014**: System MUST refuse attachment upload or removal by any user other than the Topic's author or an organiser, including attempts made by direct request rather than through the screen.
- **FR-015**: System MUST store each attachment's original file name, size and content type, together with the Topic it belongs to and when it was added.
- **FR-016**: System MUST enforce a maximum size per attachment of 10 MB and a maximum of 10 attachments per Topic, rejecting an upload that would exceed either with a message that names the limit.
- **FR-016a**: System MUST accept only attachments of the allowed types — PDF, office documents (Word, Excel, PowerPoint and their open-document equivalents), plain text and markdown, images (PNG, JPEG, GIF, WebP), and ZIP archives — and MUST reject any other file with a message listing the accepted types. The decision MUST be based on the file's declared type and extension together, so a disallowed file cannot pass by renaming alone.
- **FR-017**: System MUST reject an upload with no file or with an empty file, telling the author to choose a file.
- **FR-018**: The participant Topic Details view and the organiser Topic detail view MUST list a Topic's attachments with file name, human-readable size and a download link, in the order they were added, in an "Attachments" section placed after the Topic Info table and before the Joined Participants table; when there are none, the section MUST say so rather than disappear.
- **FR-019**: Downloading an attachment MUST deliver the stored bytes unchanged, as a download under the original file name, never rendered inline in the browser, regardless of file type.
- **FR-020**: Downloading an attachment MUST be permitted only to users who can currently see the Topic it belongs to, and MUST report "not found" for an attachment that no longer exists.
- **FR-021**: Deleting a Topic MUST remove all of its attachments.
- **FR-022**: Adding or removing an attachment MUST be recorded in the existing audit trail as a change to the Topic, naming the file affected, consistent with how other Topic edits are audited.
- **FR-023**: The Propose Topic form (creating a new Topic) MUST NOT offer attachment upload, and MUST tell the author attachments can be added after the Topic is saved.

### Key Entities

- **Topic** *(extended by this feature)*: Its description is now understood as markdown text. Nothing about how the description is stored changes; only how it is displayed and what the forms say about it.
- **Topic Attachment**: A file uploaded to a specific Topic by its author or an organiser. Carries the original file name, size, content type, the moment it was added, and the file's bytes. Belongs to exactly one Topic and is removed with it. Visible to everyone who can see the Topic; manageable only by the Topic's author and organisers.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of markdown formatting elements listed in FR-001 render as formatting, not literal symbols, on both detail views.
- **SC-002**: 100% of bare `http`/`https` addresses in descriptions are clickable on the detail views, with no trailing punctuation included in the link.
- **SC-003**: Zero unsafe markup from a Topic description ever reaches a reader's browser, verified by submitting descriptions containing scripts and event handlers and confirming none survive rendering.
- **SC-004**: An author opening either Topic form can tell, without leaving the page, that markdown is accepted and what the basic syntax is.
- **SC-005**: A Topic's author can attach a file and see it listed with a working download link in under one minute from opening the edit screen.
- **SC-006**: 100% of users who are neither the author nor an organiser see no attachment management controls and are refused on any direct attempt.
- **SC-007**: No attachment is ever downloadable by a user who cannot see its Topic.
- **SC-008**: Every existing Topic description reads the same or better after this feature; no stored description is altered by it.

## Assumptions

- The same markdown dialect and the same safety filtering used for organiser content pages apply to Topic descriptions; this feature does not introduce a second rendering pipeline. Bare-address linking and new-tab links are additions to that shared behaviour and deliberately change content pages too (FR-004b, see Clarifications).
- Description length remains unlimited beyond whatever limit exists today; no new maximum is introduced.
- Attachments are limited to the allowlisted document, text, image and ZIP types (FR-016a); code, datasets and notebooks can be shared inside a ZIP archive. Downloads are additionally always delivered as files rather than rendered inline, so an allowed type that a browser could display (an image, a PDF) is still handed over as a download.
- The per-file limit of 10 MB and the per-Topic limit of 10 attachments are confirmed, fixed limits (see Clarifications); they keep storage per event predictable and are not configurable per instance.
- Attachments are managed from the edit screens rather than from the detail views, because the detail views are read-only by design (feature 005, Story 9).
- Detail view order after this feature: page heading, actions, rendered description, Topic Info table (Needed Skills, Participants, Compliance), Attachments, Joined Participants. The organiser Topic detail view mirrors the same order.
- Attachments are added one at a time from an existing Topic's edit screen as an immediate action independent of Save (FR-012a, see Clarifications); a freshly proposed Topic gains attachments on its next edit. Multi-file selection, drag-and-drop, and preserving unsaved text edits across an upload are out of scope.
- Attachment changes do not affect a Topic's approval status, because approval is set once at creation and editing never re-derives it.
- Attachments are listed, not embedded: this feature does not provide a way to reference an attachment from within the description text. An author who wants an image inside the description links to it by address like any other image (FR-004a).
- Allowing inline images from any address in participant-written descriptions is a deliberate, accepted trade-off (see Clarifications): it keeps one shared rendering policy with content pages at the cost of readers' browsers fetching third-party resources chosen by other participants.
- Attachment additions and removals are audited as Topic changes because Topics are already within the audit trail (feature 006); the description rendering change itself needs no audit, since stored data is untouched.
- Attachment management follows Topic edit permission exactly: the author and organisers, nobody else (see Clarifications). Group members who are not the author see attachments but cannot manage them.
- Attachments are visible to exactly the audience that can see the Topic; no separate per-attachment visibility is introduced.
- Out of scope: image thumbnails or previews of attachments, virus scanning, attachment versioning, per-instance configuration of the limits, and a live preview of the rendered description while editing.
