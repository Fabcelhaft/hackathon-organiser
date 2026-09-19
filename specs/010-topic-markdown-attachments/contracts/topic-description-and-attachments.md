# Contract: Topic Markdown Descriptions, Authoring Hints & Attachments

Covers FR-001–FR-023 (see spec.md). Extends `specs/005-topic-management/contracts/topic-details.md` and
`topic-proposal-and-skills.md` (participant routes) and `specs/002-core-domain-model` / `003` organiser topic
routes — paths listed as **unchanged** keep their behaviour except for the additions described here.
Participant-facing routes require plain authentication; `/organiser/**` routes require `ROLE_ORGANISER`,
unchanged. Visibility of a Topic follows `TopicService.findVisibleTo` (Pending Topics are visible only to
their author and organisers), unchanged.

## Description rendering (Story 1)

| Method | Path | Change | Success | Failure |
|---|---|---|---|---|
| `GET` | `/topics/{id}` | Model gains `descriptionHtml` (sanitized HTML from `MarkdownRenderer`) and `attachments` (list of `TopicAttachmentSummary`). Template renders `<section class="topic-description">` with `th:utext="${descriptionHtml}"` directly under the layout `<h1>`, **removes** the Description row from the Topic Info table, and adds an Attachments section between Topic Info and Joined Participants (FR-001, FR-001a, FR-018) | 200, `topics/detail` view | 404 for unknown/invisible id (unchanged) |
| `GET` | `/organiser/topics/{id}` | Same additions; Description `<dt>/<dd>` removed from the `<dl>`; Attachments section added after the `<dl>` (FR-001, FR-001a, FR-018) | 200, `organiser/topics/detail` view | 404 unknown id (unchanged) |

Rendered-HTML guarantees (apply to Topic descriptions **and** Content Pages, since both go through `MarkdownRenderer` — FR-004b):

| Guarantee | Mechanism | FR |
|---|---|---|
| Headings, paragraphs, ordered/unordered lists, emphasis, inline code, block quotes, links, images render | commonmark + `BLOCKS/FORMATTING/IMAGES` + link policy | FR-001, FR-004a |
| Bare `http://`/`https://` addresses become `<a>` with the address as text; trailing `.,;:?!` and unbalanced `)` excluded | `commonmark-ext-autolink` (`LinkType.URL` only) | FR-002 |
| Every `<a>` carries `target="_blank"` and `rel` containing `nofollow noopener noreferrer` | commonmark `AttributeProvider` + `HtmlPolicyBuilder.requireRelsOnLinks` | FR-004 |
| `<script>`, `on*` attributes, `javascript:` URLs and any element outside the allowlist are removed | OWASP sanitizer (allowlist) | FR-003 |
| Markdown `#` headings render one level down (`<h2>`…`<h6>`) | existing heading shift | FR-005 |
| Plain prose renders as paragraphs, nothing lost | commonmark default | FR-006 |

## Authoring hints (Story 2)

Rendered inside the existing forms, not new routes:

| Route | Element | Behaviour |
|---|---|---|
| `GET /topics/new`, `GET /topics/{id}/edit`, `GET /organiser/topics/new`, `GET /organiser/topics/{id}/edit` | `<label for="description">Description (Markdown)</label>` + `<textarea id="description" aria-describedby="description-hint">` + `~{fragments/markdown-hint :: hint}` → `<small id="description-hint">…</small>` | Hint names the basics (`# Heading`, `**bold**`, `- list`, `[text](url)`) and states that bare web addresses become links (FR-009–FR-011) |
| `POST /topics`, `POST /topics/{id}`, `POST /organiser/topics`, `POST /organiser/topics/{id}` | **Unchanged** — the description is stored verbatim (FR-007) | — |

## Attachments — participant (author) routes (Story 3)

| Method | Path | Purpose | Success | Failure |
|---|---|---|---|---|
| `GET` | `/topics/{id}/edit` | Edit form now also loads `attachments` and, when the `attachment` query parameter is `added` or `removed`, a `notice` rendered as `<p role="status">`. Edit path shows the Attachments section (note + table + upload form); `GET /topics/new` shows only "Attachments can be added after the Topic is saved." (FR-012a, FR-023) | 200, `topics/form` view | 404 unknown/invisible; 403 not author (unchanged) |
| `POST` | `/topics/{id}/attachments` | Multipart upload, part name `file`. Author only. Validates presence, non-empty, ≤ 10 MB, count < 10, extension+declared type in allowlist (data-model.md) | 303 → `/topics/{id}/edit?attachment=added`; audit `ATTACHMENT_ADDED` | 404 unknown/invisible; 403 not author; 200 `topics/form` re-rendered with `error` and current `attachments` on validation failure — nothing stored (FR-016, FR-016a, FR-017) |
| `POST` | `/topics/{id}/attachments/{attachmentId}/delete` | Remove one attachment. Author only. No confirmation step (FR-012b) | 303 → `/topics/{id}/edit?attachment=removed`; audit `ATTACHMENT_REMOVED` | 404 unknown/invisible Topic, or attachment not belonging to this Topic; 403 not author |

## Attachments — organiser routes (Story 3)

| Method | Path | Purpose | Success | Failure |
|---|---|---|---|---|
| `GET` | `/organiser/topics/{id}/edit` | Same additions as the participant edit form (attachments + `notice`); `GET /organiser/topics/new` shows the "after saving" sentence (FR-013, FR-023) | 200, `organiser/topics/form` view | 404 unknown id |
| `POST` | `/organiser/topics/{id}/attachments` | Same validation as the participant upload; any organiser, any Topic | 303 → `/organiser/topics/{id}/edit?attachment=added`; audit `ATTACHMENT_ADDED` (actor organiser = true) | 404 unknown id; 200 form re-rendered with `error` on validation failure |
| `POST` | `/organiser/topics/{id}/attachments/{attachmentId}/delete` | Remove one attachment | 303 → `/organiser/topics/{id}/edit?attachment=removed`; audit `ATTACHMENT_REMOVED` | 404 unknown Topic or attachment not belonging to it |

## Attachments — download (any viewer)

| Method | Path | Purpose | Success | Failure |
|---|---|---|---|---|
| `GET` | `/topics/{topicId}/attachments/{attachmentId}` | Deliver the stored bytes. Topic resolved via `findVisibleTo(topicId, viewer, isOrganiser)` first; attachment looked up by `(attachmentId, topicId)` | 200; `Content-Type` = stored type; `Content-Disposition: attachment; filename="…"; filename*=UTF-8''…`; `Cache-Control: private, no-store`; `X-Content-Type-Options: nosniff` (Spring Security default) (FR-019) | 404 if the Topic is unknown or not visible to the viewer, or the attachment does not exist under that Topic (FR-020) — never 403, so an invisible Pending Topic's existence is not leaked |

Both detail views link each attachment to this route (FR-018). Organiser views use the same URL.

## Attachments section markup (both detail views)

```html
<section id="attachments">
  <h2>Attachments</h2>
  <p th:if="${attachments.isEmpty()}">No attachments yet.</p>
  <table th:unless="${attachments.isEmpty()}">
    <thead><tr><th scope="col">File</th><th scope="col">Size</th></tr></thead>
    <tbody>
      <tr th:each="a : ${attachments}">
        <td><a th:href="@{/topics/{tid}/attachments/{aid}(tid=${a.topicId()},aid=${a.id()})}" th:text="${a.fileName()}">file.pdf</a></td>
        <td th:text="${a.humanSize()}">1.2 MB</td>
      </tr>
    </tbody>
  </table>
</section>
```

Size formatting is a `humanSize()` accessor on the `TopicAttachmentSummary` read model, not a Thymeleaf-accessible bean, so the formatting travels with the data it describes. Ordered by `created_at` ascending (FR-018).

## Behavioral notes

- Uploading or removing an attachment never changes `approval_status` and never touches `name`/`description`/skills — the text `<form>` and the attachment forms are independent (spec Clarifications; FR-012a).
- Validation failures re-render the edit form with the submitted text fields **as stored** (the upload form carries no text fields), so no unsaved text is echoed back; the note in the Attachments section warns authors to save text first.
- `spring.codec.max-in-memory-size` = 11MB guarantees a 10 MB part reaches `TopicAttachmentService`; anything larger than 11 MB fails in the codec with the framework's own error, which is acceptable since the friendly limit message covers everything up to and including the spec's cap.
- Content Pages (Info wiki, homepage, designated form content) gain bare-URL autolinks and new-tab links as a deliberate side effect of the shared renderer (FR-004b); no Content Page route changes.
- Audit: every add/remove writes one `TOPIC`-subject entry with the file name (data-model.md); the description rendering change writes nothing.
