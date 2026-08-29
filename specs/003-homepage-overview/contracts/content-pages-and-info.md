# Contract: Rendered Homepage/Info Content & Organiser Content Page Management (Stories 5 & 6)

Covers FR-017–FR-021, FR-036. Participant-facing read routes require plain authentication; management routes
require `ROLE_ORGANISER` (`/organiser/**`).

| Method | Path | Purpose | Success | Failure |
|---|---|---|---|---|
| `GET` | `/info` | List all Content Pages except the one designated as the homepage page, ordered ascending by `sort_index` (tie-break: `created_at`) | 200, `info/list` view; empty-state message if none exist (Edge Cases) | — |
| `GET` | `/info/{id}` | Render one Content Page's markdown as sanitized HTML, page title as the top-level heading (FR-036) | 200, `info/detail` view | 404 if `id` unknown |
| `GET` | `/organiser/content-pages` | List all Content Pages (including the homepage page, flagged) for management | 200, `organiser/content-pages/list` view | — |
| `GET` | `/organiser/content-pages/new` | New-page form (`title`, `body_markdown`, `sort_index`, "designate as homepage page" checkbox) | 200, `organiser/content-pages/form` view | — |
| `POST` | `/organiser/content-pages` | Create a Content Page | Redirect 303 → `/organiser/content-pages/{id}` | 200 form re-rendered with error if `title`/`body_markdown` missing |
| `GET` | `/organiser/content-pages/{id}/edit` | Edit form, including the sort index field (FR-020a) and homepage-designation checkbox | 200, form view | 404 if `id` unknown |
| `POST` | `/organiser/content-pages/{id}` | Update `title`/`body_markdown`/`sort_index`/homepage designation | Redirect 303 → `/organiser/content-pages/{id}` | 404 if `id` unknown; 200 form re-rendered with error on missing fields |
| `POST` | `/organiser/content-pages/{id}/delete` | Remove a Content Page | Redirect 303 → `/organiser/content-pages` | 404 if `id` unknown |

## Behavioral notes

- Designating a page as the homepage page un-designates whichever page previously held that flag, in the same
  service-layer transaction — enforced additionally by the `content_pages_is_homepage_key` partial unique
  index (data-model.md) as a concurrency backstop, the same defense-in-depth pattern 002 uses throughout.
- If the homepage page is deleted without a replacement being designated, `GET /` shows a clear empty/unset
  state in the right column rather than erroring (Edge Cases) — `HomeController` treats "no Content Page has
  `is_homepage = true`" as a valid, renderable state, not an error path.
- All markdown → HTML happens through the single `MarkdownRenderer` (research.md §1) at render time; raw
  `body_markdown` is what's persisted and what the edit form re-populates — never sanitized/rendered HTML.
- `/organiser/content-pages/**` write routes are Organiser-only (FR-021); `GET /info`, `GET /info/{id}`, and
  the homepage right column are visible to any authenticated user (Standard, Participant, or Organiser alike —
  spec Assumptions).
