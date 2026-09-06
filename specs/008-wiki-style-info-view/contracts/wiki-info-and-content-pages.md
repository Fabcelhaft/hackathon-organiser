# Contract: Wiki-Style Info View, Special-Page Designation & Organiser Content Page Management

Covers FR-001–FR-021 (see spec.md). Supersedes `specs/003-homepage-overview/contracts/content-pages-and-info.md`'s
`GET /info`, `GET /info/{id}`, and Content Page management rows — those routes keep their paths but change
behaviour as described here. Participant-facing read routes require plain authentication; `/organiser/**`
management routes require `ROLE_ORGANISER`, unchanged.

## Wiki view (Story 1)

| Method | Path | Purpose | Success | Failure |
|---|---|---|---|---|
| `GET` | `/info` | Wiki view: left-hand menu of every Content Page with `context = NONE`, ordered ascending by `sort_index` (tie-break: `title`), plus the first page's rendered content on the right (FR-001–FR-004) | 200, `info/index` view | If zero undesignated pages exist: 200, same view, empty-state message instead of menu/content (FR-020) |
| `GET` | `/info/{id}` | Same wiki view, `id`'s content shown and marked current in the menu (FR-005, FR-006) | 200, `info/index` view, `id` marked current via `aria-current="page"` (FR-003) | `id` unknown, deleted, or currently designated (`context ≠ NONE`): 404 status, `info/index` view still rendered with the full menu and a "not found" message in the content pane (FR-008) |

The former `info/list` and `info/detail` views/routes are removed — `GET /info` is no longer a separate list page
with a link to a further detail page; both routes above render the one `info/index` template.

## Organiser authoring access (Story 2)

Rendered inside `info/index`, not separate routes:

| Element | Visible to | Behaviour |
|---|---|---|
| "Edit" action, top of content area | Organisers only, only when a page is currently displayed (not on the not-found view) | `<a href="/organiser/content-pages/{currentPageId}/edit" target="_blank" rel="noopener">` (FR-009, FR-011) |
| "New page" action, top of content area | Organisers only, always shown (including the not-found and empty-state views) | `<a href="/organiser/content-pages/new" target="_blank" rel="noopener">` (FR-009, FR-011, FR-011a, FR-021) |

Both actions are absent from the model/markup entirely for non-organisers (FR-010), not merely disabled.

## Special pages (Story 3)

| Method | Path | Purpose | Success | Failure |
|---|---|---|---|---|
| `GET` | `/` | Homepage right column shows the `context = HOMEPAGE` page's content, unchanged route/behaviour from feature 003 except the underlying lookup is now by `context` value instead of `is_homepage` (FR-017) | 200, `home/index` view | No page holds `HOMEPAGE`: right column shows its existing empty/unset state (unchanged) |
| `GET` | `/register` | Registration form now additionally shows the `context = USER_REGISTRATION` page's body (title-less, unwrapped) above the form fields (FR-016, FR-016b, FR-016c) | 200, `participants/register` view | No page holds `USER_REGISTRATION`: form renders exactly as before (FR-017) |
| `GET` | `/topics/new` | Topic-creation form now additionally shows the `context = TOPIC_CREATION` page's body above the form fields (FR-015, FR-016b, FR-016c) | 200, `topics/form` view | No page holds `TOPIC_CREATION`: form renders exactly as before (FR-017) |
| `GET` | `/topics/{id}/edit` | **Unchanged** — designated content is never shown when editing an existing Topic (FR-015) | 200, `topics/form` view, no designated content | — |
| Organiser topic-creation / participant-creation admin forms | **Unchanged** — no designated content rendered there (FR-016a) | — | — |

## Organiser Content Page management

| Method | Path | Purpose | Success | Failure |
|---|---|---|---|---|
| `GET` | `/organiser/content-pages` | List all Content Pages, each showing its context designation if any (FR-018) | 200, `organiser/content-pages/list` view | — |
| `GET` | `/organiser/content-pages/new` | New-page form: `title`, `body_markdown`, `sort_index` (pre-filled to one above the current highest index, or `0` if none exist — FR-019a, FR-019b), and a single-select context control (`None`/`Homepage`/`Topic creation`/`User registration`, replacing the homepage checkbox — FR-012a) | 200, `organiser/content-pages/form` view | — |
| `POST` | `/organiser/content-pages` | Create a Content Page | Redirect 303 → `/organiser/content-pages/{id}/edit` | 200 form re-rendered with error if `title`/`body_markdown` missing, or `sort_index` missing/non-numeric (FR-019) |
| `GET` | `/organiser/content-pages/{id}/edit` | Edit form, same fields as New, pre-filled from the existing page including its current context | 200, form view | 404 if `id` unknown |
| `POST` | `/organiser/content-pages/{id}` | Update `title`/`body_markdown`/`sort_index`/context | Redirect 303 → `/organiser/content-pages/{id}/edit` | 404 if `id` unknown; 200 form re-rendered with error on missing/invalid fields |
| `POST` | `/organiser/content-pages/{id}/delete` | Remove a Content Page. If the page currently holds a context designation, the list view's delete control requires an explicit confirmation naming that context before the request is sent (FR-018a); the request itself is unchanged once confirmed | Redirect 303 → `/organiser/content-pages` | 404 if `id` unknown |

## Behavioral notes

- Designating a page for any of the three non-`NONE` contexts un-designates whichever page previously held
  that specific context (not the other two), in the same service-layer operation — enforced additionally by
  the `content_pages_context_key` partial unique index (data-model.md) as a concurrency backstop (FR-013).
- A page's context and the Info menu are mutually exclusive: exactly the pages with `context = NONE` populate
  the menu and are eligible as the default page (FR-014).
- All markdown → HTML continues to go through the single `MarkdownRenderer` at render time; `body_markdown` is
  what's persisted and what the edit form re-populates — never sanitized/rendered HTML (unchanged from feature 003).
- Content Page changes, including context/designation changes, are **not** written to the audit trail — the
  organiser overview (`GET /organiser/content-pages`) showing current designations is the only visibility
  requirement (spec Clarifications).
