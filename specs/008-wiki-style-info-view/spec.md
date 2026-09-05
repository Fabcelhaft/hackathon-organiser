# Feature Specification: Wiki-Style Info View

**Feature Branch**: `008-info-view-more`

**Created**: 2026-09-05

**Status**: Draft

**Input**: User description: "Info view should be more like a wiki, having a menu on the left side and directly rendering it on the right side. Organisers directly see an edit button in the bottom under the rendered text"

## Clarifications

### Session 2026-09-05

- **Q: Which page shows by default when the Info section is opened?** → The first page in menu order (by index). Special pages (see below) are never the default.
- **Q: Should the homepage-designated page appear in the Info menu?** → No. Special pages that are linked into a specific context never appear in the Info menu; they are instead marked as such in the organiser overview.
- **Q: Where does the organiser return to after editing?** → Not applicable — authoring actions open the organiser screens in a **new browser tab**, so the wiki view is never left.
- **Q: What happens on an unknown or removed page?** → Keep returning "not found"; render it inside the wiki layout so the menu stays usable.
- **Q: Edit button placement?** → **Top** of the content area, not the bottom as originally described, so it is reachable on long pages without scrolling. A "New page" action sits alongside it.
- **Q: Page ordering?** → The index is mandatory when authoring a page; pages sharing an index are ordered alphabetically by title.
- **Q: Mobile/narrow-screen layout, per-page table of contents and section anchors, page hierarchy, and search?** → All out of scope for this feature.
- **Added scope:** Beyond the existing homepage designation, a page may be designated for **topic creation** or **user registration**; when designated, its content renders at the top of that context.
- Q: When an organiser edits a content page, how should they pick which context that page is designated for? → A: A single-select control listing None, Homepage, Topic creation, User registration — replacing today's homepage checkbox, so two contexts can never be claimed at once.
- Q: Should designated topic-creation and registration content appear only on the participant-facing forms, or also on the organiser equivalents? → A: Participant-facing forms only; the organiser topic-creation and participant-creation admin forms stay untouched.
- Q: Should changes to content pages, including designation changes, be recorded in the existing audit trail? → A: No — content pages remain unaudited, as they are today; the organiser overview showing current designations is sufficient.
- Q: How should a designated page's content be presented on the registration and topic-creation forms? → A: Body only, without the page title, rendered plain and unwrapped directly above the form fields — no card or container.
- Q: On the "page not found" view, should an organiser still see the authoring actions? → A: Show New page, hide Edit — there is no current page to edit.
- Q: Should the Info wiki stay behind login, or become readable without signing in? → A: Unchanged — the Info wiki requires login exactly as today; this feature moves no content across the authentication boundary.
- Q: What happens when an organiser deletes a page that is currently designated for a context? → A: Deletion requires an explicit confirmation naming the screen that will lose its content; deleting an undesignated page is unchanged.
- Q: Should the New page form pre-fill the index, or start empty? → A: Pre-fill with the next value after the highest existing index, so a new page appends to the end of the menu and indexes stay distinct.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Browse info pages like a wiki (Priority: P1)

A visitor (participant or organiser) opens the Info section and sees a menu listing every info page on the left, with a page's content rendered directly on the right. Clicking any menu entry immediately shows that page's content in the same view, without first landing on a separate list page.

**Why this priority**: This is the core of the requested redesign. Today, browsing Info requires going to a list page and then loading a separate detail page for each topic. Replacing that with a persistent menu + content pane is the change that delivers the "wiki" experience and is valuable on its own, before any other slice ships.

**Independent Test**: With at least two info pages existing, open the Info section and confirm a menu of pages is visible alongside rendered content, that the first page by index is shown by default, and that selecting a different menu entry updates the content pane and marks the newly selected entry as current.

**Acceptance Scenarios**:

1. **Given** at least one info page exists, **When** a user opens the Info section, **Then** they see a left-hand menu of all info pages and the content of the first page in menu order rendered on the right.
2. **Given** the Info section is open, **When** the user selects a different page from the menu, **Then** the right-hand content shows that page and the menu marks it as the current page.
3. **Given** a user opens a direct link to a specific info page, **When** the page loads, **Then** the wiki layout is shown with that page rendered and marked current in the menu.
4. **Given** two info pages share the same index, **When** the menu renders, **Then** those two pages appear next to each other in alphabetical order by title, and the whole menu order is identical on every load.
5. **Given** a user requests an info page that does not exist or has been removed, **When** the response renders, **Then** it reports "not found" while still showing the wiki layout and menu so the user can navigate to an existing page.

---

### User Story 2 - Organisers author from the wiki view (Priority: P2)

While viewing any info page in the wiki layout, an organiser sees "Edit" and "New page" actions at the top of the content area. Both open the existing organiser content screens in a new browser tab, so the page they were reading stays open behind them.

**Why this priority**: This removes the navigation detour organisers face today (leaving Info entirely to find the matching entry in the management area) and is the second half of the original request. It depends on the wiki layout from User Story 1 existing first.

**Independent Test**: While signed in as an organiser and viewing any info page, confirm Edit and New page actions appear above the rendered content, that each opens the corresponding organiser screen in a new tab with the wiki view still open in the original tab, and that a participant viewing the same page sees neither action.

**Acceptance Scenarios**:

1. **Given** an organiser is viewing an info page, **When** the page renders, **Then** Edit and New page actions are visible at the top of the content area, above the rendered content.
2. **Given** an organiser activates Edit, **When** the action opens, **Then** the editing screen for that specific page opens in a new browser tab and the wiki view remains open and unchanged in the original tab.
3. **Given** an organiser activates New page, **When** the action opens, **Then** the page-creation screen opens in a new browser tab.
4. **Given** content pages already exist, **When** an organiser opens the page-creation form, **Then** the index field arrives pre-filled with the next value above the highest index in use, and the organiser can still change it before saving.
5. **Given** a participant is viewing the same info page, **When** the page renders, **Then** neither the Edit nor the New page action is shown.

---

### User Story 3 - Context-specific special pages (Priority: P3)

An organiser can designate a content page as the page for a specific context — the site homepage, the topic-creation form, or the user-registration form. A designated page's content renders at the top of that context, giving participants guidance exactly where they need it. Designated pages are kept out of the Info menu so the same content is not presented twice, and the organiser overview shows which page is designated for what.

**Why this priority**: This adds genuinely new capability (contextual guidance on the registration and topic-creation forms) and generalises the existing homepage designation, but the Info section is usable and complete without it.

**Independent Test**: Designate one page for topic creation and another for user registration, then open the topic-creation form and the registration form and confirm each shows its designated content at the top; confirm both pages have disappeared from the Info menu and are marked with their designation in the organiser overview.

**Acceptance Scenarios**:

1. **Given** a page is designated for user registration, **When** a participant opens the registration form, **Then** that page's content renders above the form fields.
2. **Given** a page is designated for topic creation, **When** a participant opens the topic-creation form, **Then** that page's content renders above the form fields.
3. **Given** a page is designated for topic creation, **When** a user opens the form to *edit* an existing topic, **Then** the designated content does **not** render — the designation applies to creating a topic only.
4. **Given** pages are designated for topic creation and registration, **When** an organiser opens the organiser topic-creation or participant-creation admin form, **Then** no designated content renders there — those forms are unchanged.
5. **Given** no page is designated for a context, **When** that context renders, **Then** it appears exactly as it does today, with no placeholder, gap, or error.
6. **Given** a page is currently designated for a context, **When** an organiser designates a different page for that same context, **Then** the previous page loses the designation, so at most one page holds each designation at any time.
7. **Given** a page carries any designation, **When** the Info menu renders, **Then** that page is absent from the menu and is never used as the default page.
8. **Given** an organiser opens the content page overview, **When** the list renders, **Then** each page shows which designation it holds, if any.
9. **Given** a page is designated for a context, **When** an organiser deletes it, **Then** they must confirm a message naming the screen that will lose its content, and afterwards that screen renders with no designated content.
10. **Given** a page holds no designation, **When** an organiser deletes it, **Then** deletion behaves exactly as it does today, with no added confirmation step.

---

### User Story 4 - Info section with no pages yet (Priority: P4)

Before any undesignated info pages exist, visitors to the Info section see a clear message explaining there is nothing to show yet, instead of an empty menu and blank content area. Organisers additionally see the action to create the first page.

**Why this priority**: An empty state is a real but infrequent situation, typically only before an event is fully set up, so it matters less than the browsing, authoring and special-page slices above.

**Independent Test**: With zero undesignated pages, open the Info section as a participant and confirm an explanatory message appears instead of an empty menu; repeat as an organiser and confirm the create-page action is also present.

**Acceptance Scenarios**:

1. **Given** no undesignated info pages exist, **When** a participant opens the Info section, **Then** they see an explanatory empty-state message instead of an empty menu and content pane.
2. **Given** every existing page carries a designation, **When** a participant opens the Info section, **Then** the same empty-state message is shown, because designated pages never populate the menu.
3. **Given** no undesignated info pages exist, **When** an organiser opens the Info section, **Then** they see the empty-state message plus the action to create the first page.

---

### Edge Cases

- A user follows a bookmarked link to a page that has since been deleted or designated for a context: the response reports "not found" but still renders the wiki layout and menu, so the user can reach an existing page in one click rather than hitting a dead end.
- The page currently open in the wiki is designated for a context in another tab: it disappears from the menu on the next load, and if it had been the default page, the next page by index becomes the default.
- Several pages are given the same index: they are ordered alphabetically by title, so the menu never reorders itself between loads.
- An organiser submits a page without an index, or with a non-numeric one: the page is rejected with a validation message rather than silently falling back to a default index.
- An organiser deletes the page currently designated for registration or topic creation: they must confirm a message naming the screen that will lose its content, and afterwards that form renders without designated content rather than breaking.
- The number of info pages exceeds what fits on screen: the menu remains fully browsable without breaking the layout or pushing the content pane out of view.
- A participant attempts to reach an authoring screen directly by URL: access is denied, consistent with how organiser-only areas are already protected.

## Requirements *(mandatory)*

### Functional Requirements

#### Wiki layout and navigation

- **FR-001**: System MUST present the Info section as a single wiki-style view combining a menu of all undesignated content pages on the left with one page's rendered content on the right.
- **FR-002**: The menu MUST order pages by their index ascending, with pages sharing an index ordered alphabetically by title, so menu order is fully deterministic.
- **FR-003**: The menu MUST identify the page currently displayed both visually and programmatically, so assistive technology announces it as the current page rather than relying on styling alone.
- **FR-004**: When the Info section is opened without a specific page requested, system MUST display the first page in menu order.
- **FR-005**: Selecting a page from the menu MUST render that page's content and update the current-page indicator; the separate Info list view MUST NOT remain as a reachable alternative.
- **FR-006**: Every content page in the menu MUST remain individually linkable, and opening such a link MUST render the full wiki layout with that page shown and marked current.
- **FR-007**: The displayed page's own title MUST be the single top-level heading of the view, with all content-authored headings remaining subordinate to it.
- **FR-008**: A request for an unknown, removed, or newly designated content page MUST report "not found" while rendering the wiki layout, so the menu remains available.

#### Organiser authoring access

- **FR-009**: For users with organiser permissions, system MUST display an Edit action for the currently displayed page and a New page action, both at the top of the content area above the rendered content.
- **FR-010**: System MUST NOT display either authoring action to users without organiser permissions.
- **FR-011**: Both authoring actions MUST open the corresponding existing organiser content screen in a new browser tab, leaving the wiki view open and unchanged in the original tab.
- **FR-011a**: On the "page not found" view, system MUST show the New page action to organisers and MUST omit the Edit action, since no page is displayed to edit.

#### Special pages

- **FR-012**: System MUST allow a content page to be designated for at most one context from: site homepage, topic creation, user registration.
- **FR-012a**: The authoring form MUST offer the designation as a single-choice control whose options are "none" plus the three contexts, replacing the current homepage checkbox, so that a page claiming two contexts cannot be expressed or submitted.
- **FR-013**: System MUST permit at most one page per context at any time; designating a page for a context MUST clear that designation from whichever page previously held it.
- **FR-014**: System MUST exclude every designated page from the Info menu, and MUST never select a designated page as the default page.
- **FR-015**: When a page is designated for topic creation, system MUST render its content at the top of the participant-facing topic-creation form, and MUST NOT render it when an existing topic is being edited.
- **FR-016**: When a page is designated for user registration, system MUST render its content at the top of the participant-facing registration form.
- **FR-016a**: System MUST NOT render designated content on the organiser-facing equivalents of those forms (organiser topic creation and organiser participant creation); those screens stay exactly as they are today.
- **FR-016b**: On the topic-creation and registration forms, system MUST render the designated page's body only — not its title — as plain content flowing directly above the form fields, without a card or other visual container.
- **FR-016c**: Content-authored headings within designated content MUST remain subordinate to the host form's own page heading, so each context keeps exactly one top-level heading.
- **FR-017**: When no page is designated for a context, system MUST render that context unchanged, with no placeholder or error.
- **FR-018**: The organiser content page overview MUST show, for each page, which context it is designated for, if any.
- **FR-018a**: Deleting a page that currently holds a designation MUST require an explicit confirmation that names the context losing its content; deleting an undesignated page MUST remain as it is today, with no added confirmation step.
- **FR-018b**: After a designated page is deleted, its context MUST fall back to rendering with no designated content, per FR-017, rather than erroring or retaining stale content.

#### Authoring constraints

- **FR-019**: System MUST require an index when a content page is created or updated, rejecting a missing or non-numeric index with a validation message instead of substituting a default.
- **FR-019a**: The page-creation form MUST arrive with the index pre-filled to the next value above the highest index currently in use, so a new page appends to the end of the menu by default. The value MUST be visible and editable before submission, not applied silently afterwards.
- **FR-019b**: When no content pages exist yet, the page-creation form MUST pre-fill the index with the first value in the ordering sequence.

#### Empty state

- **FR-020**: When no undesignated content pages exist, system MUST show an explanatory empty-state message in place of the menu and content pane.
- **FR-021**: When no undesignated content pages exist and the viewer has organiser permissions, system MUST additionally present the action to create the first page.

### Key Entities

- **Content Page**: An organiser-authored page with a title, body content, and a mandatory index that determines its position in the menu. A page may optionally hold one context designation. Pages without a designation are exactly what the Info menu lists; pages with one are rendered in their designated context instead. ("Content Page" is the canonical term throughout — the Info section is a view over these pages, not a separate kind of page.)
- **Context Designation**: The link between a page and one specific place in the product — site homepage, topic creation, or user registration. Each context can be held by at most one page at a time, and a page holds at most one context.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: From the Info section, a user can reach any listed page's content in a single interaction, with no intermediate list page in the path.
- **SC-002**: A user can tell which page they are currently reading from the menu alone, without checking the browser address bar.
- **SC-003**: An organiser can start editing the page they are reading in a single interaction, and the page they were reading is still open afterwards.
- **SC-004**: 100% of non-organiser visitors see no authoring actions anywhere in the Info section.
- **SC-005**: When no undesignated pages exist, 100% of visitors see an explanatory message rather than a blank or broken-looking screen.
- **SC-006**: No context is ever held by more than one page, and no designated page ever appears in the Info menu.
- **SC-007**: For each context with a designated page, that content appears above the relevant form; for each context without one, the form renders exactly as it does today, with no placeholder, gap, or error.
- **SC-008**: The menu presents pages in the same order on every load, for every user.

## Assumptions

- The existing pool of content pages, their titles and their bodies are reused unchanged; this feature changes how pages are browsed, where they can be surfaced, and how they are ordered — not what a page can contain.
- The homepage designation continues to behave exactly as it does today, including its existing card treatment; this feature generalises the same one-page-per-context rule to two further contexts rather than redefining the homepage. The two new contexts deliberately render without a card (FR-016b), so the homepage's container is an intentional difference, not an inconsistency to "fix".
- Creating and editing pages continues to use the existing organiser content screens; no inline or in-place editing is introduced inside the wiki view.
- Because authoring opens in a new tab, no return-navigation, save-and-return, or cancel-and-return behaviour is specified for the organiser screens.
- The original request placed the Edit action at the bottom, under the rendered text; it was moved to the top during clarification so it stays reachable on long pages without scrolling.
- Only organisers can see or use authoring actions, consistent with how organiser-only areas are already restricted.
- The Info wiki remains readable to signed-in users only, unchanged from today; no content becomes publicly readable as a result of this feature, and no page-level public/private setting is introduced.
- Out of scope for this feature: mobile and narrow-screen layout of the menu, per-page tables of contents and section-level anchor links, nested or hierarchical pages, and search across pages. The flat menu is accepted as sufficient for the page counts expected at a hackathon.
- Content pages stay outside the audit trail, as they are today; recording who changed a page or moved a designation is not part of this feature.
