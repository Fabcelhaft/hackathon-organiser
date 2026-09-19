# Quickstart: Topic Markdown Descriptions & Attachments

This feature reuses the existing OIDC/test infrastructure unchanged (`SecurityMockServerConfigurers
.mockOidcLogin()`/`.mockUser()` — `mockOidcLogin()` alone needs a real `User` wired in for
`@AuthenticationPrincipal HackathonOidcUser` routes to resolve, per this codebase's IT conventions —
Testcontainers PostgreSQL, `spring.sql.init.mode=always`). Multipart uploads in ITs use `MultipartBodyBuilder`
+ `BodyInserters.fromMultipartData(...)` exactly as `ContentImageManagementIT` already does.

## Prerequisites

- Java 25, Maven, Docker (Testcontainers + `docker-compose.yml` Postgres) — unchanged.
- Network access to Maven Central once, to fetch the new `commonmark-ext-autolink` dependency (verified reachable).
- The `schema.sql` addition in [data-model.md](./data-model.md) (`topic_attachments` table) applied automatically
  via `spring.sql.init.mode=always`.
- A Chromium binary for the `a11y.*IT` Playwright suite (unchanged — see
  [003's quickstart](../003-homepage-overview/quickstart.md#prerequisites)).

## Running the automated validation suite

```bash
mvn verify
```

To run only the integration tests when a unit stage is failing, use the project's established override:

```bash
mvn verify -Dtest=NoSuchUnitTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='TopicSelfServiceManagementIT,TopicManagementIT'
```

| Story | Acceptance Scenario(s) | Test(s) |
|---|---|---|
| 1 — Read a richly formatted description | 1–7 | `content/MarkdownRendererTest` (autolink incl. trailing punctuation and balanced parens; every `<a>` gets `target="_blank"` + `rel` with `noopener`; scripts/`on*`/`javascript:` stripped; images kept), `topics/TopicSelfServiceManagementIT` (description `<section>` under `<h1>`, no Description row in the table, headings shifted to `<h2>`, plain prose preserved), `organiser/topic/TopicManagementIT` (organiser detail renders the same HTML, no Description `<dt>`) |
| 2 — Know that markdown is accepted | 1–4 | `topics/TopicSelfServiceManagementIT` + `organiser/topic/TopicManagementIT` (label text, `aria-describedby="description-hint"`, hint fragment present on new and edit forms; reopened edit form echoes the raw markdown verbatim) |
| 3 — Attach files | 1–11 | `topic/TopicAttachmentServiceTest` (allowlist by extension+type, empty file, > 10 MB, 11th file, path stripping, audit calls), `topics/TopicSelfServiceManagementIT` (upload → 303 with `?attachment=added` and row listed; remove → `?attachment=removed`; non-author 403 on both POSTs and no controls in markup; download 200 with `Content-Disposition: attachment` for a visible Topic, 404 for another user's Pending Topic, 404 after removal; `/topics/new` shows the "after saving" sentence and no upload form), `organiser/topic/TopicManagementIT` (organiser upload/remove on any Topic, audit entries `ATTACHMENT_ADDED`/`ATTACHMENT_REMOVED` with the file name, organiser detail lists attachments) |

Regression coverage for the deliberate shared-renderer change (FR-004b):

- `info/InfoManagementIT` — a Content Page containing a bare URL renders it as a link; rendered links carry
  `target="_blank"` and `rel` containing `noopener`.
- `organiser/content/ContentImageManagementIT` — untouched; image embedding still works (policy keeps `IMAGES`).

Each test class authenticates via `mockOidcLogin()`/`.mockUser()` (no live IdP) and exercises the routes in
[contracts/topic-description-and-attachments.md](./contracts/topic-description-and-attachments.md).

## Automated accessibility scan

```bash
mvn verify -Dtest=a11y.*IT -DfailIfNoTests=false
```

Update `a11y/TopicDetailAccessibilityIT` for the new layout (one `<h1>`, description section, Attachments
`<section>` with its own `<h2>` and a header-row table). Add a form-page check (either a new
`TopicFormAccessibilityIT` or an extra case in the existing suite) asserting the hint is associated via
`aria-describedby` and the upload control is labelled.

## Expected outcomes (traces to Success Criteria)

- SC-001: `TopicSelfServiceManagementIT` submits a description with a heading, list, bold, inline code, block quote
  and explicit link, then asserts each corresponding element (`<h2>`, `<ul>`, `<strong>`, `<code>`, `<blockquote>`,
  `<a>`) appears in both detail views.
- SC-002: `MarkdownRendererTest` asserts `https://example.org/docs.` yields a link whose `href` ends in `docs`
  (no trailing full stop) and whose text is the address.
- SC-003: `MarkdownRendererTest` + IT assert a description containing `<script>`, `onerror=` and `javascript:`
  renders none of them.
- SC-004: both form ITs assert the label reads "Description (Markdown)" and the hint element is present and
  referenced by `aria-describedby`.
- SC-005: covered functionally by the upload IT (single POST → listed with a working download link); the
  "under one minute" wall-clock claim is confirmed in the manual smoke test.
- SC-006: ITs assert a non-author's edit-page request is 403 and the detail page markup contains no upload form
  or Remove button; a direct non-author POST to both attachment routes is 403.
- SC-007: download IT asserts 404 for a Pending Topic's attachment requested by a non-author, non-organiser user.
- SC-008: IT creates a Topic with plain prose, asserts the stored `description` is byte-identical after the
  feature's routes run and the detail view shows it as a paragraph.

## Manual visual smoke test (required — Constitution Development Workflow #3)

Reuses the dev-only Dex identity provider setup documented in
[002's quickstart](../002-core-domain-model/quickstart.md#manual-visual-smoke-test-required--constitution-development-workflow-3)
steps 1–4. Then, for this feature:

1. As a participant, open `/topics/new`: confirm the label says "Description (Markdown)", the hint under the field
   lists the basics and says bare addresses become links, and the sentence about adding attachments after saving is
   present with no upload control. Propose a Topic whose description has a `#` heading, a bulleted list, `**bold**`,
   an explicit link and a bare `https://` URL followed by a full stop.
2. Open the Topic's detail page: the description renders full-width under the title (no Description row in the
   table), the heading is visually subordinate to the title, both links open in a new tab, and the full stop is
   not part of the bare link. Confirm the empty "Attachments" section reads "No attachments yet."
3. Open the Topic's edit screen: confirm the Attachments section sits below the text form with its own Upload
   button and the "save text changes first" note. Upload a PDF; confirm the page reloads with a confirmation and
   the file listed with its size. Try a `.exe`-style file and an 11 MB file; confirm each is rejected with the
   specific message and nothing is listed. Remove the PDF; confirm it disappears immediately with no prompt.
4. As a second participant, open the same Topic's detail page: attachments listed with download links, no
   upload/remove controls; download one and confirm the browser saves it under the original name rather than
   displaying it.
5. As an organiser, open `/organiser/topics/{id}`: description rendered the same way, Attachments listed; open
   the organiser edit screen, upload and remove a file, then open the Topic's Audit page and confirm the two new
   entries name the file.
6. Open `/info` on a Content Page containing a bare URL: confirm it is now a link and opens in a new tab
   (deliberate shared-renderer change).
