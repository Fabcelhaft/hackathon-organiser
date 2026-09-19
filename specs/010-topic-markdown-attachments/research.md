# Phase 0 Research: Topic Markdown Descriptions & Attachments

The spec's two Clarification sessions resolved every product question. This phase resolves the *technical* unknowns needed to build it inside this codebase's conventions. Nothing in the Technical Context was left as NEEDS CLARIFICATION.

## 1. Bare-URL autolinking (FR-002, FR-004b)

**Decision**: Add `org.commonmark:commonmark-ext-autolink:0.24.0` (same version as the existing `commonmark` artifact) and register `AutolinkExtension.create()` on the shared `Parser` and `HtmlRenderer` in `MarkdownRenderer`. Maven Central is reachable from this environment (verified: the artifact's POM returns HTTP 200).

**Rationale**: The extension wraps `autolink-java`, which already implements exactly the edge cases FR-002 names: trailing `.`/`,`/`;`/`:`/`?`/`!` are excluded from the link, and a closing `)` is only included when balanced by an opening one inside the URL. Writing that ourselves would be a hand-rolled parser on user-supplied text — precisely what this codebase's security-sensitive-parsing guidance says not to do. **Correction, found during implementation**: the extension exposes only `AutolinkExtension.create()` — there is no way to narrow its `LinkType.URL` + `LinkType.EMAIL` default, so email addresses are also linked as `mailto:`. FR-002 asks only for web addresses and forbids nothing here, so the library's behaviour is accepted rather than worked around; narrowing it would have meant restricting the sanitizer's allowed protocols, which would silently break legitimate `mailto:` links an organiser wrote on a Content Page. `MarkdownRendererTest.anEmailAddressIsAlsoAutolinked` pins the behaviour so a future library change is noticed.

**Alternatives considered**: A regex post-pass over rendered HTML — rejected (parses HTML with regex, fragile around existing `<a>` and `<code>` content, and cannot honour the sanitizer boundary). Leaving links explicit-only — rejected by FR-002.

## 2. Opening every rendered link in a new tab safely (FR-004)

**Decision**: A commonmark `AttributeProviderFactory` on the `HtmlRenderer` adds `target="_blank"` to every `Link` node (explicit and autolinked). The sanitizer policy is then rebuilt with `HtmlPolicyBuilder` instead of `Sanitizers.LINKS`, because `Sanitizers.LINKS` allows only `href` on `<a>` and would strip the `target` attribute:

```java
new HtmlPolicyBuilder()
    .allowStandardUrlProtocols()
    .allowElements("a")
    .allowAttributes("href").onElements("a")
    .allowAttributes("target").matching(Pattern.compile("_blank")).onElements("a")
    .requireRelsOnLinks("nofollow", "noopener", "noreferrer")
    .toFactory()
```

combined with the existing `Sanitizers.BLOCKS.and(FORMATTING).and(IMAGES)`.

**Rationale**: `rel="noopener noreferrer"` is what makes `target="_blank"` safe (no `window.opener` access from the destination — the spec's "MUST NOT grant the destination page any access back to the app's window"). `requireRelsOnLinks` is the sanitizer's own mechanism for guaranteeing those `rel` tokens on every link regardless of what the author wrote, so the guarantee holds even if a future markdown extension emits raw `<a>` tags. `nofollow` is retained from `Sanitizers.LINKS`' current behaviour (it calls `requireRelNofollowOnLinks`), so existing Content Page links lose nothing. Restricting `target` to the literal `_blank` prevents an author from naming a frame.

**Alternatives considered**: Adding `target`/`rel` after sanitizing via string replace — rejected (post-sanitizer mutation of HTML defeats the "single point that emits unescaped HTML" design). Leaving links same-tab — rejected by FR-004 and the shared-behaviour clarification.

## 3. Images in participant descriptions (FR-004a)

**Decision**: No change — `Sanitizers.IMAGES` stays in the policy, so `![alt](https://…)` renders inline from any address for Topics exactly as for Content Pages, per the clarification.

**Rationale**: The spec explicitly chose one shared policy. Nothing to build.

## 4. Where the description is rendered to HTML

**Decision**: The two detail controllers (`TopicSelfServiceController.detail`, `TopicController.detail`) inject `MarkdownRenderer` and add a `descriptionHtml` model attribute alongside the existing `detail`; templates render it with `th:utext` in a `<section class="topic-description">` directly after the layout's `<h1>`, and the Description row is removed from the Topic Info `<table>` (participant) and the `<dl>` (organiser).

**Rationale**: `MarkdownRenderer` is already the single sanitization boundary and already lives in `content`, which `topics` already depends on (`ContentPageService` import). Adding the HTML at the controller keeps `TopicDetailView`/`TopicDetail` records (used by several other callers) unchanged. Two call sites is not enough duplication to justify a new read-model field. Headings inside a description render as `<h2>`+ thanks to the renderer's existing heading shift, so the page keeps one `<h1>` (FR-005) with no new logic.

**Alternatives considered**: Adding `descriptionHtml` to `TopicDiscoveryService.TopicDetailView` — rejected (spreads a `content` dependency into `topic` for no benefit). Rendering inside the table cell — rejected by clarification.

## 5. Attachment type allowlist (FR-016a)

**Decision**: A `TopicAttachmentType` enum-backed table mapping lower-cased **extension → set of accepted declared MIME types**:

| Extensions | Accepted declared types |
|---|---|
| `pdf` | `application/pdf` |
| `doc` / `docx` | `application/msword` / `application/vnd.openxmlformats-officedocument.wordprocessingml.document` |
| `xls` / `xlsx` | `application/vnd.ms-excel` / `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` |
| `ppt` / `pptx` | `application/vnd.ms-powerpoint` / `application/vnd.openxmlformats-officedocument.presentationml.presentation` |
| `odt` / `ods` / `odp` | `application/vnd.oasis.opendocument.text` / `.spreadsheet` / `.presentation` |
| `txt` | `text/plain` |
| `md` / `markdown` | `text/markdown`, `text/x-markdown`, `text/plain` |
| `png` / `jpg` / `jpeg` / `gif` / `webp` | the matching `image/*` type |
| `zip` | `application/zip`, `application/x-zip-compressed` |

An upload is accepted only if the extension is in the table **and** the declared `Content-Type` (from the multipart part headers, parameters stripped) is in that extension's set. Blank/`application/octet-stream` declared types are rejected — the message lists the accepted types.

**Rationale**: Directly implements the clarification ("declared type and extension together"). The alternate MIME spellings are the ones browsers actually send (Windows sends `application/x-zip-compressed`; `.md` is frequently sent as `text/plain`), so legitimate uploads are not rejected. The spec's stated limitation stands: browsers derive the declared type from the extension, so this stops accidental wrong files and casual renaming, not a determined attacker — content sniffing is out of scope (spec Assumptions).

**Alternatives considered**: Magic-byte sniffing (Apache Tika) — rejected as a new heavyweight dependency not asked for. Extension-only check — rejected by the clarification wording.

## 6. Upload plumbing and the size limit (FR-016, FR-017)

**Decision**: Same shape as `ContentImageController.upload`: `exchange.getMultipartData()` → `FilePart` → `DataBufferUtils.join(file.content())` → `byte[]` → service. `spring.codec.max-in-memory-size` is raised from `6MB` to `11MB` and its comment updated: the buffer must exceed the largest *service-validated* upload (now 10 MB, was 5 MB) so the service, not the codec, is what rejects an over-limit file with a friendly message. The service rejects `data.length == 0` ("choose a file"), `> 10 * 1024 * 1024` ("10 MB or smaller"), and an existing count `>= 10` ("at most 10 attachments") before any write. A missing `file` part (no file chosen) is detected in the controller as today (`!(filePart instanceof FilePart)`).

**Rationale**: Reuses the one multipart pattern already proven in this codebase; a single config constant change keeps the "service is the only real limit" invariant the existing comment documents.

**Alternatives considered**: Streaming parts to disk via `DefaultPartHttpMessageReader` disk mode — unnecessary at 10 MB and would add a temp-file lifecycle.

## 7. Storing attachments

**Decision**: New table `topic_attachments` with bytes in a `bytea` column, one row per file, FK `topic_id REFERENCES topics (id) ON DELETE CASCADE`; entity `TopicAttachment` mirrors `ContentImage` (`@Table`, `@Id UUID` DB-assigned via `DEFAULT uuidv7()`), plus `file_name`, `uploaded_by_user_id`. Listing uses a `DatabaseClient` query selecting metadata columns only (never `data`), mapped to a `TopicAttachmentSummary` record; the download route is the only path that loads `data`.

**Rationale**: `content_images` already proves `bytea` + R2DBC works here; the app is containerised with no filesystem volume convention, so DB storage is the only option that survives redeploys without new infrastructure. Metadata-only listing avoids dragging up to 100 MB through the detail page. `ON DELETE CASCADE` satisfies FR-021 at the database level — no Topic delete route exists today, so the cascade is what makes the requirement hold whenever one is added.

**Alternatives considered**: Filesystem/object storage — rejected (new infra). Reusing `content_images` with a nullable `topic_id` — rejected (mixes an organiser-only library with participant uploads and different lifecycle/visibility rules).

## 8. Download route, visibility and forced download (FR-019, FR-020)

**Decision**: `GET /topics/{topicId}/attachments/{attachmentId}` in a new `@RestController` `topics.TopicAttachmentDownloadController` (outside `/organiser/**`, so plain authentication applies — same reasoning as `web.ContentImageStreamController`). It resolves the Topic via `TopicService.findVisibleTo(topicId, viewer, isOrganiser)` first, then the attachment by id **and** `topicId`; either miss → 404. Response: stored `Content-Type`, `Content-Disposition: attachment` built with Spring's `ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build()` (emits RFC 5987 `filename*` for non-ASCII names, so the edge case "unsafe characters in file name" is handled by the framework), `Cache-Control: private, no-store`. Spring Security's default headers already add `X-Content-Type-Options: nosniff`. Both detail views link to this same route.

**Rationale**: Reusing `findVisibleTo` means attachment visibility can never exceed Topic visibility (SC-007) by construction. Pairing the id with `topicId` in the lookup prevents guessing another Topic's attachment id under a visible Topic's URL. `attachment` disposition is the standard way to force a download regardless of type (FR-019).

**Alternatives considered**: `application/octet-stream` for everything — rejected; the real type with `attachment` disposition downloads just the same and keeps the file useful. Long-lived public cache like images — rejected; attachments are visibility-gated.

## 9. Auditing add/remove (FR-022)

**Decision**: Two new `AuditEventType` values, `ATTACHMENT_ADDED` and `ATTACHMENT_REMOVED`, recorded against `AuditSubjectType.TOPIC` / the Topic id / the Topic name, with the file name in `newValue` (added) or `oldValue` (removed). `audit_entries.event_type` is a free `text` column with no CHECK constraint, so no schema change is needed.

**One template change is required.** `organiser/topics/audit.html` only delegates to the shared `table(entries)` fragment in `organiser/audit/list.html`, which renders the Change column as `${entry.oldValue} + ' -> ' + ${entry.newValue}` guarded by "either is non-null". Every event type that exists today sets **both** values (`STATUS_CHANGED`) or **neither** (`CREATED`, `EDITED`), so that concatenation has never met a one-sided entry. These two new types set exactly one, which would render `null -> report.pdf` and `report.pdf -> null`. The fragment therefore gains a both-present / one-present split before this feature ships. Event types themselves keep rendering as raw enum names, unchanged.

**Rationale**: `EDITED` with a file name stuffed into `newValue` would misuse the old/new semantics feature 006 defined; dedicated event types keep the trail self-describing, which is what "naming the file affected" asks for.

## 10. Confirmation after upload/remove (FR-012a)

**Decision**: The upload and remove routes redirect (303) back to the edit screen with a query parameter: `/topics/{id}/edit?attachment=added` or `?attachment=removed` (organiser: `/organiser/topics/{id}/edit?...`). The edit-form GET reads that parameter and passes a `notice` model attribute rendered as `<p role="status">`. Validation failures re-render the edit form directly with the existing `error` attribute (200), exactly like the text-form path.

**Rationale**: WebFlux has no flash-attribute support, and this codebase has no post-redirect message mechanism yet. A query parameter is the smallest stateless option, survives the redirect, and is bookmark-harmless (a stale `?attachment=added` only shows a notice). `role="status"` makes the confirmation reach assistive technology.

**Alternatives considered**: Re-rendering the edit form from the POST (no redirect) — rejected (refresh would re-submit the upload). Session-stored flash — rejected (new mechanism, session coupling).

## 11. Markdown hint markup (FR-009 – FR-011)

**Decision**: A shared fragment `fragments/markdown-hint.html` rendered directly under the description `<textarea>` in both forms as `<small id="description-hint">` containing a one-line list of the basics (`# Heading`, `**bold**`, `- list`, `[text](url)`) and the sentence that bare web addresses become links; the textarea gets `aria-describedby="description-hint"` and its label reads "Description (Markdown)".

**Rationale**: Pico CSS styles `<small>` after a form control as helper text with no custom CSS; `aria-describedby` is the standard association assistive technology announces (FR-011); one fragment keeps the two forms identical (FR-009/FR-010).

## 12. Edit-screen attachment section

**Decision**: On the edit path only (`topicId != null`), below the existing text `<form>`, an `<section id="attachments">` with: the "save text changes first" note, a `<table>` of current attachments (name, size, per-row `<form method="post">` Remove button — no `confirm()`, per FR-012b), and a separate `<form method="post" enctype="multipart/form-data">` with `<input type="file" name="file" required>` and an Upload button. On the new path, a single sentence: "Attachments can be added after the Topic is saved." Attachments are loaded by the edit-form GET via `TopicAttachmentService.listFor(id)`.

**Rationale**: Two independent forms is what the "immediate, separate action" clarification means at the HTML level; `required` on the file input gives a browser-side "choose a file" prompt while the controller still enforces it server-side (FR-017).

## 13. Regression surface on Content Pages

**Decision**: Because §1–§2 change the shared renderer, `InfoManagementIT` gains two assertions (a Content Page with a bare URL renders it as a link; every rendered `<a>` carries `target="_blank"` and `rel` containing `noopener`), and `MarkdownRendererTest` covers the policy directly. No Content Page template changes.

**Rationale**: The clarification deliberately accepted this behaviour change; tests make it explicit rather than incidental.
