# Contract: Content Image Library (Story 7)

Covers FR-024–FR-029. Upload/manage routes require `ROLE_ORGANISER` (`/organiser/**`); the byte-serving route
requires only plain authentication (research.md §2 — any authenticated user must be able to load an image
embedded in a page they're allowed to view).

| Method | Path | Purpose | Success | Failure |
|---|---|---|---|---|
| `GET` | `/organiser/content-images` | Image library: thumbnail/list of every uploaded Content Image, each with its copyable markdown embed syntax (FR-025) and an alt-text edit control (FR-025b) | 200, `organiser/content-images/list` view | — |
| `POST` | `/organiser/content-images` | Upload a new image (multipart: file + required `alt_text`, FR-025a) | Redirect 303 → `/organiser/content-images` with the new image's embed syntax shown | 200 (or redirect-with-flash) error if not PNG/JPEG/GIF/WebP or over 5 MB (FR-029, rejected without storing) or `alt_text` blank (FR-025a) |
| `POST` | `/organiser/content-images/{id}/alt-text` | Edit an existing image's alt text in place (FR-025b) | Redirect 303 → `/organiser/content-images` | 404 if `id` unknown; error if blank |
| `POST` | `/organiser/content-images/{id}/delete` | Delete an image | Redirect 303 → `/organiser/content-images` | 404 if `id` unknown; error listing referencing page titles if still referenced (FR-028) — deletion blocked |
| `GET` | `/content-images/{id}` | Raw image bytes with the stored `Content-Type` | 200, image body | 404 if `id` unknown |

## Behavioral notes

- The embed syntax shown for each image is always `![<current alt_text>](/content-images/{id})` — since
  `id` and the URL never change, editing alt text (FR-025b) only changes what newly-copied syntax reads;
  pages that already pasted the old syntax keep whatever alt text they were pasted with (FR-025b — this
  feature does not retroactively rewrite `body_markdown`).
- Deletion-block detection is a query-time substring search over `content_pages.body_markdown`
  (research.md §3, data-model.md) — not a foreign key, so there's no `ON DELETE RESTRICT` to rely on; the
  block is entirely `ContentImageService`'s responsibility, raising `ContentImageConflictException` with the
  referencing page titles for the controller to surface (FR-028).
- `GET /content-images/{id}` is deliberately outside `/organiser/**`: `SecurityConfig`'s existing
  `.anyExchange().authenticated()` default already covers it correctly, no new security rule needed
  (research.md §2).
- A broken/removed image reference inside a Content Page's markdown (Edge Cases) is not something this route
  itself handles specially — the browser's own broken-image rendering for a 404'd `<img src>` already
  satisfies "the page MUST still render, showing a broken-image indicator."
