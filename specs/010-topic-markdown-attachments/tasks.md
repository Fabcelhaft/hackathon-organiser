---

description: "Task list for feature implementation"
---

# Tasks: Topic Markdown Descriptions & Attachments

**Input**: Design documents from `/specs/010-topic-markdown-attachments/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: Constitution Principle V (Test-First Development) is NON-NEGOTIABLE for this project — every phase below writes its failing tests before the implementation that satisfies them, per the Red-Green-Refactor cycle.

**Reactive verification**: Per Constitution Development Workflow #4, unit tests asserting a `Mono`/`Flux` result whose chain composes more than one operator MUST use `StepVerifier`, not a blocking `.block()`. This applies to `TopicAttachmentServiceTest` (T026). `MarkdownRendererTest` stays a plain synchronous unit test (the renderer returns a `String`, not a reactive type). Integration tests (`*ManagementIT`) continue using `WebTestClient` plus the existing `.block()`-based repository test-helper convention already established in `TopicSelfServiceManagementIT`/`ContentImageManagementIT` — that is test setup, not a reactive-chain assertion.

**Organization**: Tasks are grouped by user story (P1–P3 from spec.md) to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: Which user story this task belongs to (US1–US3)
- File paths are relative to the repository root

## Path Conventions

Single Maven/Spring Boot project (see [plan.md](plan.md) Project Structure), extending the existing layout —
one new package-less addition (`topic/TopicAttachment*`) plus changes inside `content/`, `audit/`, `topics/`,
`organiser/topic/`, their template counterparts, and `a11y/`.

**⚠️ Shared-file note**: US1 and US3 both edit `src/main/resources/templates/topics/detail.html` and
`src/main/resources/templates/organiser/topics/detail.html` (US1 adds the description section and removes the
Description row; US3 adds the Attachments section). US2 and US3 both edit
`src/main/resources/templates/topics/form.html` and `src/main/resources/templates/organiser/topics/form.html`.
These are additive edits to different regions of the same files — sequential story execution has no conflict;
parallel story execution must coordinate on those four templates.

---

## Phase 1: Setup

**Purpose**: Add the one new dependency this feature needs. Everything else (commonmark, OWASP sanitizer,
Testcontainers, Playwright/axe-core, `MultipartBodyBuilder`) is already on the classpath from earlier features.

- [X] T001 Add `org.commonmark:commonmark-ext-autolink:0.24.0` (same version as the existing `commonmark` artifact) to the `<dependencies>` block in `pom.xml`, directly after the existing `commonmark` entry, and confirm it resolves with `mvn -q dependency:resolve` (research.md §1)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: None. The three user stories touch disjoint production code — US1 changes the shared renderer and
the two detail views, US2 adds a hint fragment to the two forms, US3 adds a new table/entity/service and its
routes. No story reads state another story writes, so there is no cross-story prerequisite beyond the Setup
dependency in T001 (which only US1 consumes).

**Checkpoint**: After T001, any of US1 / US2 / US3 may begin.

---

## Phase 3: User Story 1 - Read a richly formatted Topic description (Priority: P1) 🎯 MVP

**Goal**: A Topic's description renders as formatted markdown in its own full-width section on both detail
views, with bare web addresses linked, every link opening in a new tab safely, and unsafe markup stripped.

**Independent Test**: Create a Topic whose description contains a heading, a bulleted list, bold text, an
explicit markdown link, a bare web address followed by a full stop, and a `<script>` tag; open its Topic
Details view as a participant and as an organiser and confirm each element renders as formatting (not literal
symbols), both links are clickable with `target="_blank"`, the trailing full stop is outside the bare link, the
script is gone, and the Topic Info table no longer carries a Description row.

### Tests for User Story 1 ⚠️ write first, confirm they fail

- [X] T002 [P] [US1] Extend `src/test/java/net/fabcelhaft/hackathonorganiser/content/MarkdownRendererTest.java` with failing cases: a bare `https://example.org/docs` becomes an `<a>` whose `href` and text are both the address; `https://example.org/docs.` excludes the trailing full stop from the `href`; `(see https://example.org/a(b)c)` keeps the balanced inner parens and excludes the wrapping one; an email address is **not** turned into a `mailto:` link (`LinkType.URL` only); every rendered `<a>` (explicit markdown link and autolink alike) carries `target="_blank"` and a `rel` containing `nofollow`, `noopener` and `noreferrer`; `<script>`, an `onerror=` attribute and a `javascript:` href are all stripped; an `![alt](https://…)` image still renders (`Sanitizers.IMAGES` retained); existing heading-shift and block-element behaviour is unchanged (FR-002, FR-003, FR-004, FR-004a, FR-005)
- [X] T003 [P] [US1] Add failing cases to `src/test/java/net/fabcelhaft/hackathonorganiser/topics/TopicSelfServiceManagementIT.java`: `GET /topics/{id}` for a Topic whose description contains a `#` heading, a `-` list, `**bold**`, `` `code` ``, a block quote, an explicit link and a bare URL renders `<section class="topic-description">` after the layout `<h1>` containing `<h2>`, `<ul>`, `<strong>`, `<code>`, `<blockquote>` and two `<a>` elements; the Topic Info table contains no Description row; a plain-prose description (no markdown) still renders as a paragraph with its text intact; the stored `topics.description` is byte-identical before and after the request (FR-001, FR-001a, FR-006, FR-007)
- [X] T004 [P] [US1] Add failing cases to `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/topic/TopicManagementIT.java`: `GET /organiser/topics/{id}` renders the same description section under the heading and the `<dl>` no longer contains a Description `<dt>`/`<dd>` pair (FR-001, FR-001a, Story 1 Scenario 7)
- [X] T005 [P] [US1] Add failing regression cases to `src/test/java/net/fabcelhaft/hackathonorganiser/info/InfoManagementIT.java`: a Content Page whose `body_markdown` contains a bare URL renders it as a link, and every `<a>` in rendered Content Page output carries `target="_blank"` and a `rel` containing `noopener` — the deliberate shared-renderer change (FR-004b, research.md §13)

### Implementation for User Story 1

- [X] T006 [US1] In `src/main/java/net/fabcelhaft/hackathonorganiser/content/MarkdownRenderer.java`, register `AutolinkExtension.create()` on both the `Parser.builder()` and `HtmlRenderer.builder()`, narrowed to `Set.of(LinkType.URL)` so email addresses are not linked; update the class javadoc to record that bare-URL autolinking now applies to every caller of this renderer, Content Pages included (research.md §1)
- [X] T007 [US1] In the same file, add an `AttributeProviderFactory` to the `HtmlRenderer` builder whose provider sets `target="_blank"` on every `Link` node's `<a>` tag, alongside the existing `HeadingLevelShiftingRenderer` node-renderer factory (research.md §2)
- [X] T008 [US1] In the same file, replace `Sanitizers.LINKS` in the `POLICY` constant with an `HtmlPolicyBuilder` factory that calls `allowStandardUrlProtocols()`, `allowElements("a")`, `allowAttributes("href").onElements("a")`, `allowAttributes("target").matching(Pattern.compile("_blank")).onElements("a")` and `requireRelsOnLinks("nofollow", "noopener", "noreferrer")`, combined with the existing `Sanitizers.BLOCKS.and(FORMATTING).and(IMAGES)`; document in the javadoc why `Sanitizers.LINKS` cannot be kept (it allows only `href`, so it would strip the `target` added in T007) (research.md §2)
- [X] T009 [US1] In `src/main/java/net/fabcelhaft/hackathonorganiser/topics/TopicSelfServiceController.java`, inject `MarkdownRenderer` and add a `descriptionHtml` model attribute to the `detail` route, rendered from `detail.topic().getDescription()` (research.md §4)
- [X] T010 [P] [US1] In `src/main/resources/templates/topics/detail.html`, add `<section class="topic-description" th:utext="${descriptionHtml}">` immediately inside `#content` above the actions/Topic Info table, and delete the `<tr>` carrying the Description row; add a Thymeleaf comment noting `th:utext` is deliberate because the HTML is pre-sanitized by `MarkdownRenderer`, matching the existing `designated-content` comment style in `topics/form.html` (FR-001a)
- [X] T011 [US1] In `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/topic/TopicController.java`, inject `MarkdownRenderer` and add the same `descriptionHtml` model attribute to the `detail` route
- [X] T012 [P] [US1] In `src/main/resources/templates/organiser/topics/detail.html`, add the same `<section class="topic-description" th:utext="${descriptionHtml}">` above the `<dl>` and delete the Description `<dt>`/`<dd>` pair
- [X] T013 [P] [US1] Add a `.topic-description` block-spacing rule to `src/main/resources/static/css/app.css` only if the Pico CSS defaults leave the new section visually cramped against the heading — skip this task if the rendered page already looks right (Constitution Principle IV: custom CSS only where Pico's semantic defaults cannot cover it)

**Checkpoint**: Topic descriptions render as markdown on both detail views with safe new-tab links; Content
Pages gain the same link behaviour. US1 is independently demonstrable.

---

## Phase 4: User Story 2 - Know that markdown is accepted while writing (Priority: P2)

**Goal**: Both Topic forms state that the description accepts markdown, illustrate the basics, and announce
that hint to assistive technology as the field's description.

**Independent Test**: Open the participant Propose Topic form and the organiser topic form and confirm each
shows a "Description (Markdown)" label plus a hint listing the basics and the bare-address behaviour, with the
hint referenced by the textarea's `aria-describedby`; reopen an edit form and confirm the textarea contains the
author's raw markdown unchanged.

### Tests for User Story 2 ⚠️ write first, confirm they fail

- [X] T014 [P] [US2] Add failing cases to `src/test/java/net/fabcelhaft/hackathonorganiser/topics/TopicSelfServiceManagementIT.java`: `GET /topics/new` and `GET /topics/{id}/edit` each render a label reading `Description (Markdown)`, a `<small id="description-hint">` containing the basic syntax examples and the sentence about bare web addresses, and a description `<textarea>` carrying `aria-describedby="description-hint"`; reopening the edit form for a Topic saved with markdown shows that exact markdown in the textarea, not rendered HTML (FR-009, FR-010, FR-011, Story 2 Scenario 4)
- [X] T015 [P] [US2] Add the equivalent failing cases to `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/topic/TopicManagementIT.java` for `GET /organiser/topics/new` and `GET /organiser/topics/{id}/edit` (FR-009, FR-010, FR-011, Story 2 Scenario 2)

### Implementation for User Story 2

- [X] T016 [US2] Create `src/main/resources/templates/fragments/markdown-hint.html` defining a `hint` fragment that renders `<small id="description-hint">` listing `# Heading`, `**bold**`, `- list` and `[text](url)` and stating that bare web addresses become links, following the existing fragment conventions in `src/main/resources/templates/fragments/teams-link.html` (research.md §11)
- [X] T017 [P] [US2] In `src/main/resources/templates/topics/form.html`, change the description label to `Description (Markdown)`, add `aria-describedby="description-hint"` to the `<textarea>`, and render `~{fragments/markdown-hint :: hint}` directly below it
- [X] T018 [P] [US2] Apply the identical three changes to `src/main/resources/templates/organiser/topics/form.html`

**Checkpoint**: Both forms advertise markdown support accessibly. US2 is independently demonstrable and does
not depend on US1 having shipped.

---

## Phase 5: User Story 3 - Attach files to a Topic (Priority: P3)

**Goal**: A Topic's author (and any organiser) can upload and remove file attachments from the edit screen as
an action independent of Save; everyone who can see the Topic sees them listed with working download links.

**Independent Test**: As a Topic's author, upload two allowed files from the edit screen, open the Topic
Details view as another participant and confirm both are listed with name, size and a working download that
saves rather than displays; remove one as the author and confirm it disappears and its link 404s; confirm a
non-author participant sees no upload or remove controls and is refused on a direct POST; confirm a rejected
type, an over-size file, an 11th file and an empty submission each fail with their specific message and store
nothing.

### Tests for User Story 3 ⚠️ write first, confirm they fail

- [X] T019 [P] [US3] Create `src/test/java/net/fabcelhaft/hackathonorganiser/topic/TopicAttachmentServiceTest.java` with failing `StepVerifier`-based unit tests covering: an allowed extension paired with its declared type is stored; an allowed extension with a mismatched declared type is rejected; a disallowed extension (for example `.exe`) is rejected even when its declared type is allowed; a blank or `application/octet-stream` declared type is rejected; a zero-length file is rejected; a file over 10 MB is rejected; an 11th attachment on a Topic already holding 10 is rejected; a file name arriving as `C:\path\to\file.pdf` is stored as `file.pdf`; each rejection message names its limit or lists the accepted types; a successful upload records one `ATTACHMENT_ADDED` audit entry with the file name in `newValue` and a successful removal records one `ATTACHMENT_REMOVED` with the file name in `oldValue` (data-model.md validation table, FR-016, FR-016a, FR-017, FR-022)
- [X] T020 [P] [US3] Add failing attachment cases to `src/test/java/net/fabcelhaft/hackathonorganiser/topics/TopicSelfServiceManagementIT.java` using `MultipartBodyBuilder` + `BodyInserters.fromMultipartData` (the pattern in `ContentImageManagementIT`): `POST /topics/{id}/attachments` as the author returns 303 to `/topics/{id}/edit?attachment=added` and the file is listed on the edit screen and the detail view; `GET /topics/{id}/edit?attachment=added` renders a `role="status"` notice; `POST /topics/{id}/attachments/{aid}/delete` returns 303 to `?attachment=removed`; both POSTs by a non-author return 403 and by any user for an invisible Pending Topic return 404; the detail view for a non-author contains no upload form and no Remove button; `GET /topics/new` shows the "attachments can be added after the Topic is saved" sentence and no file input; a rejected upload re-renders the form with the specific error, stores nothing, and still shows the Topic's current name, description and checked skills (a blank form here is the failure mode T031 guards against) (FR-012, FR-012a, FR-012b, FR-014, FR-018, FR-023)
- [X] T021 [P] [US3] Add failing download cases to `src/test/java/net/fabcelhaft/hackathonorganiser/topics/TopicSelfServiceManagementIT.java`: `GET /topics/{tid}/attachments/{aid}` returns 200 with the stored bytes, the stored `Content-Type`, a `Content-Disposition` starting `attachment` and naming the original file name, and `Cache-Control` containing `no-store`; the same request for another user's Pending Topic returns 404; a valid attachment id requested under a **different** Topic's id returns 404; a removed attachment's URL returns 404 (FR-019, FR-020)
- [X] T022 [P] [US3] Add failing organiser cases to `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/topic/TopicManagementIT.java`: an organiser can upload to and remove from any Topic via `/organiser/topics/{id}/attachments…` with the same 303 redirects; the organiser detail view lists attachments with download links; `GET /organiser/topics/{id}/audit` afterwards shows entries for both operations naming the file, and the rendered Change column contains the file name **without** a `null ->` or `-> null` fragment — the shared audit fragment concatenates old and new unconditionally today, so assert the absence of the literal `null`, not merely the presence of the file name, or the defect T039 fixes will pass unnoticed (FR-013, FR-018, FR-022)

### Implementation for User Story 3

- [X] T023 [P] [US3] Append the `topic_attachments` table and `topic_attachments_topic_idx` index to `src/main/resources/schema.sql` exactly as specified in [data-model.md](data-model.md) "Schema change", including the explanatory comment and the `ON DELETE CASCADE` on `topic_id` (FR-021)
- [X] T024 [P] [US3] Raise `spring.codec.max-in-memory-size` from `6MB` to `11MB` in `src/main/resources/application.yml` and update the adjacent comment to name the 10 MB Topic Attachment cap as the largest service-validated upload, keeping the existing explanation that the service, not the codec, must be what rejects an over-limit file (research.md §6)
- [X] T025 [P] [US3] Add `ATTACHMENT_ADDED` and `ATTACHMENT_REMOVED` to `src/main/java/net/fabcelhaft/hackathonorganiser/audit/AuditEventType.java`, noting in the javadoc that `audit_entries.event_type` is free `text` with no CHECK constraint so no schema change accompanies this (research.md §9)
- [X] T026 [P] [US3] Create `src/main/java/net/fabcelhaft/hackathonorganiser/topic/TopicAttachment.java` as an `@Table("topic_attachments")` entity with the fields and javadoc conventions in [data-model.md](data-model.md) "Topic Attachment", mirroring `content/ContentImage.java` (DB-assigned `@Id UUID` left null on construction)
- [X] T027 [P] [US3] Create `src/main/java/net/fabcelhaft/hackathonorganiser/topic/TopicAttachmentRepository.java` as a `ReactiveCrudRepository<TopicAttachment, UUID>`, following `content/ContentImageRepository.java`
- [X] T028 [P] [US3] Create `src/main/java/net/fabcelhaft/hackathonorganiser/topic/TopicAttachmentConflictException.java` mirroring `content/ContentImageConflictException.java`, so the controllers can re-render the edit form with a friendly message
- [X] T029 [P] [US3] Create `src/main/java/net/fabcelhaft/hackathonorganiser/topic/TopicAttachmentType.java` holding the extension → accepted-declared-types allowlist table from [research.md](research.md) §5 plus an `isAllowed(String fileName, String contentType)` helper that lower-cases the extension, strips `Content-Type` parameters, and rejects a blank or `application/octet-stream` declared type; document that this stops accidental and casual mislabelling, not a determined attacker (content sniffing is out of scope per spec Assumptions)
- [X] T030 [US3] Create `src/main/java/net/fabcelhaft/hackathonorganiser/topic/TopicAttachmentService.java` with `listFor(topicId)` (a `DatabaseClient` metadata-only query returning `TopicAttachmentSummary`, never selecting `data`, ordered by `created_at, id`), `upload(topicId, requesterUserId, requireAuthor, fileName, contentType, bytes, actor)` applying every validation rule in [data-model.md](data-model.md) before any write and recording `ATTACHMENT_ADDED`, `remove(topicId, attachmentId, requesterUserId, requireAuthor, actor)` recording `ATTACHMENT_REMOVED`, and `findForDownload(topicId, attachmentId)` matching on the id **pair**; define the `TopicAttachmentSummary` record here (depends on T025–T029)
- [X] T031 [US3] In `src/main/java/net/fabcelhaft/hackathonorganiser/topics/TopicSelfServiceController.java`, inject `TopicAttachmentService`; add `attachments` and a query-parameter-driven `notice` to the `editForm` model; add `POST /topics/{id}/attachments` and `POST /topics/{id}/attachments/{attachmentId}/delete`, each resolving the Topic via `findVisibleTo` then `failIfNotAuthor` (preserving the existing 404-vs-403 split), reading the multipart part the way `ContentImageController.upload` does (`getMultipartData` → `FilePart` → `DataBufferUtils.join`), redirecting 303 to the edit screen with `?attachment=added`/`?attachment=removed`, and, on `TopicAttachmentConflictException`, re-rendering `topics/form` with the **complete** edit-form model — `error`, `attachments`, `topicId`, `name` and `description` read from the stored Topic (not from the request, which carries no text fields), `allSkills` and `selectedSkillIds` from `topicService.findDetail(id)` — so a rejected upload returns a fully populated form rather than a blank one; reuse the model-building already present in `editForm` rather than duplicating it (contracts §"Attachments — participant (author) routes", research.md §10)
- [X] T032 [P] [US3] Create `src/main/java/net/fabcelhaft/hackathonorganiser/topics/TopicAttachmentDownloadController.java` as a `@RestController` serving `GET /topics/{topicId}/attachments/{attachmentId}`: resolve the Topic through `TopicService.findVisibleTo` first, then the attachment by the `(attachmentId, topicId)` pair, returning the bytes with the stored `Content-Type`, `ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build()`, and `Cache-Control: private, no-store`; 404 for every miss, never 403; document why it sits outside `/organiser/**`, mirroring `web/ContentImageStreamController.java`'s class comment (FR-019, FR-020, research.md §8)
- [X] T033 [US3] In `src/main/java/net/fabcelhaft/hackathonorganiser/organiser/topic/TopicController.java`, inject `TopicAttachmentService`; add `attachments` + `notice` to the `editForm` model and add `POST /organiser/topics/{id}/attachments` and `POST /organiser/topics/{id}/attachments/{attachmentId}/delete` with the same body-reading and redirect behaviour, passing `requireAuthor = false` and an organiser `AuditActor`; on rejection re-render `organiser/topics/form` with the complete edit-form model — `error`, `attachments`, `topicId`, `name`, `description`, `allSkills`, `selectedSkillIds`, plus this form's own `availableUsers` and `currentAuthorUserId`, without which the Creator select renders empty (contracts §"Attachments — organiser routes")
- [X] T034 [US3] Add a human-readable byte-size formatter as a `humanSize()` accessor on the `TopicAttachmentSummary` record in `src/main/java/net/fabcelhaft/hackathonorganiser/topic/TopicAttachmentService.java` (keeping the formatting in the read model rather than introducing a Thymeleaf-accessible bean), and use it from the markup added in T035–T038
- [X] T035 [P] [US3] In `src/main/resources/templates/topics/form.html`, add an `<section id="attachments">` below the existing text `<form>` shown only when `${topicId} != null`, containing the "save text changes first" note, a `<table>` of current attachments with a per-row `<form method="post">` Remove button (no `confirm()`, per FR-012b), and a separate `<form method="post" enctype="multipart/form-data">` with `<input type="file" name="file" required>` and an Upload button; when `${topicId} == null`, render only the sentence that attachments can be added after saving; render the `notice` as `<p role="status">` when present (FR-012a, FR-023, research.md §12)
- [X] T036 [P] [US3] Apply the equivalent section to `src/main/resources/templates/organiser/topics/form.html`, posting to the `/organiser/topics/{id}/attachments…` routes
- [X] T037 [P] [US3] In `src/main/resources/templates/topics/detail.html`, add the `<section id="attachments">` listing markup from [contracts/topic-description-and-attachments.md](contracts/topic-description-and-attachments.md) between the Topic Info table and the Joined Participants table, with an explicit "No attachments yet." message when empty (FR-018)
- [X] T038 [P] [US3] Add the same Attachments section to `src/main/resources/templates/organiser/topics/detail.html`, placed after the `<dl>` and before the existing actions block
- [X] T039 [US3] Make the audit table's Change column null-safe in `src/main/resources/templates/organiser/audit/list.html` (the shared `table(entries)` fragment that `organiser/topics/audit.html` delegates to — that file itself maps nothing and needs no change). Today the cell renders `${entry.oldValue} + ' -> ' + ${entry.newValue}`, which every existing event type satisfies because it sets both values or neither; `ATTACHMENT_ADDED`/`ATTACHMENT_REMOVED` set exactly one, so the cell would read `null -> report.pdf` or `report.pdf -> null`. Render the arrow form only when both values are present, and the single value alone when only one is, leaving the both-null empty-cell branch as it is. Event type itself keeps rendering as the raw enum name, consistent with `STATUS_CHANGED` today (research.md §9, FR-022)

**Checkpoint**: All three user stories are independently functional.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T040 [P] Update `src/test/java/net/fabcelhaft/hackathonorganiser/a11y/TopicDetailAccessibilityIT.java` for the new Topic Details layout: exactly one `<h1>`, the description `<section>`, and the Attachments `<section>` with its own `<h2>` and a header-row table, confirming the axe scan stays clean
- [X] T041 [P] Add an accessibility check for the Topic forms — either a new `src/test/java/net/fabcelhaft/hackathonorganiser/a11y/TopicFormAccessibilityIT.java` alongside the existing siblings or an extra case in an existing suite — asserting the markdown hint is associated via `aria-describedby` and the file input is labelled
- [X] T042 Run the full suite with `mvn verify` from the repository root and confirm every pre-existing test still passes, paying particular attention to `src/test/java/net/fabcelhaft/hackathonorganiser/organiser/content/ContentImageManagementIT.java` (image embedding must still work under the rebuilt sanitizer policy) and `src/test/java/net/fabcelhaft/hackathonorganiser/a11y/InfoAccessibilityIT.java` (Content Page links now carry `target`/`rel`)
- [X] T043 Walk the manual visual smoke test in [quickstart.md](quickstart.md) "Manual visual smoke test" steps 1–6 against the running application, including the deliberate Content Page link change in step 6 — required by Constitution Development Workflow #3 before this feature is considered complete

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — T001 can start immediately.
- **Foundational (Phase 2)**: Empty by design (see that phase). Blocks nothing.
- **User Stories (Phases 3–5)**: US1 depends on T001 (the autolink dependency). US2 and US3 depend on nothing
  in Phase 1 and may start immediately. All three may run in parallel, subject to the shared-template note at
  the top of this file.
- **Polish (Phase 6)**: T040 depends on US1 + US3 templates; T041 depends on US2 + US3 form changes; T042/T043
  depend on every story intended for the release.

### User Story Dependencies

- **User Story 1 (P1)**: Needs T001 only. No dependency on US2 or US3.
- **User Story 2 (P2)**: Fully independent. Shippable alone (the hint is honest as soon as US1 ships; shipping
  US2 before US1 would advertise rendering that does not exist yet, so prefer US1 first).
- **User Story 3 (P3)**: Fully independent of US1 and US2.

### Within Each User Story

- Tests are written and confirmed failing before their implementation tasks.
- Entity → repository → service → controllers → templates (US3's T026–T038 follow exactly this order).
- `TopicAttachmentService` (T030) depends on T025–T029; both attachment controllers (T031, T033) depend on T030.

### Parallel Opportunities

- T002–T005 (all US1 tests) touch four different test files and can be written in parallel.
- T014–T015 (US2 tests) are two different files.
- T019–T022 (US3 tests) span three files; T020 and T021 edit the same file and must be sequential with each other.
- T023–T029 (US3 schema, config, enum, entity, repository, exception, allowlist) are seven different files, all parallel.
- T035–T038 (US3 templates) are four different files, all parallel once T034 has settled the size-formatting approach.
- T040 and T041 are different test files.

---

## Parallel Example: User Story 3 foundations

```bash
# After the US3 tests are red, launch the independent building blocks together:
Task: "Append topic_attachments table to src/main/resources/schema.sql"
Task: "Raise spring.codec.max-in-memory-size to 11MB in src/main/resources/application.yml"
Task: "Add ATTACHMENT_ADDED/ATTACHMENT_REMOVED to audit/AuditEventType.java"
Task: "Create topic/TopicAttachment.java entity"
Task: "Create topic/TopicAttachmentRepository.java"
Task: "Create topic/TopicAttachmentConflictException.java"
Task: "Create topic/TopicAttachmentType.java allowlist"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1 (T001).
2. Complete Phase 3 (T002–T013).
3. **STOP and VALIDATE**: descriptions render as markdown on both detail views; Content Page links behave as
   the clarification intends; `mvn verify` is green.
4. Deploy/demo — this alone resolves the original request's "make it clear to the user" for reading.

### Incremental Delivery

1. Setup → US1 (markdown rendering) → demo.
2. Add US2 (authoring hints) → demo. Small, and it makes US1's capability discoverable.
3. Add US3 (attachments) → demo. The largest slice; ships independently of the other two.
4. Finish with Phase 6 polish before merging.

### Parallel Team Strategy

1. One developer takes T001 and US1 (the shared renderer is the only file two stories would contend for).
2. In parallel, a second developer takes US3 from T019 — its only shared files are the four templates US1 and
   US2 also touch, so coordinate those four edits (or land US1's template changes first).
3. US2 is a half-day slice anyone can pick up once US1's rendering is merged.

---

## Notes

- [P] tasks = different files, no dependency on an incomplete task.
- [Story] label maps each task to its user story for traceability.
- Verify tests fail before implementing — Constitution Principle V is non-negotiable here.
- Commit after each task or logical group.
- `MarkdownRenderer` remains the single point in this codebase that emits unescaped HTML; T006–T008 change what
  it emits, not that boundary. Any new `th:utext` added by this feature (T010, T012) must be fed only from it.
- The shared-renderer change deliberately alters Content Page output (FR-004b) — T005 exists so that is
  asserted, not discovered.
