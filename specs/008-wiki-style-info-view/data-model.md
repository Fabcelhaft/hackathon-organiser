# Phase 1 Data Model: Wiki-Style Info View

## Content Page

Existing entity (`net.fabcelhaft.hackathonorganiser.content.ContentPage`, table `content_pages`), one field changed.

| Field | Type | Notes |
|---|---|---|
| `id` | `UUID` | Unchanged. DB-assigned (`DEFAULT uuidv7()`). |
| `title` | `String` | Unchanged. Required, non-blank (FR-037, unchanged). |
| `bodyMarkdown` | `String` | Unchanged. Required, non-blank. Sanitized/rendered at read time by `MarkdownRenderer`; never stored as HTML. |
| `sortIndex` | `int` | **Behaviour change, same column.** Now mandatory: creating/updating a page with a missing or non-numeric index is rejected with a validation message (FR-019) instead of silently defaulting to `0`. Ties broken alphabetically by `title` at read time (FR-002), not by `createdAt` as today — see Ordering below. |
| ~~`isHomepage`~~ → **`context`** | ~~`boolean`~~ → **`ContentPageContext`** | **Replaced.** See below. |
| `createdAt` | `Instant` | Unchanged. |
| `updatedAt` | `Instant` | Unchanged. |

### `ContentPageContext` (new enum)

```java
public enum ContentPageContext {
    NONE,
    HOMEPAGE,
    TOPIC_CREATION,
    USER_REGISTRATION
}
```

- Persisted as the `content_pages.context` `text` column (`Enum.name()`, native Spring Data R2DBC conversion — same pattern as `ParticipantStatus`/`TopicApprovalStatus`).
- **Invariant**: at most one `ContentPage` may hold each non-`NONE` value at any time (FR-013), enforced by:
  - Application logic: designating a page for a context first clears that context from whichever page previously held it (generalises today's `unsetPreviousHomepageIfNeeded`).
  - Database: `UNIQUE INDEX content_pages_context_key ON content_pages (context) WHERE context <> 'NONE'` (replaces `content_pages_is_homepage_key`), the concurrency-safe backstop.
- A page with `context != NONE` is **excluded** from the Info menu and can never be the wiki's default page (FR-014).
- A page with `context == NONE` is unaffected by any of this feature's context logic — it behaves exactly as an ordinary Info page does today.

### Ordering (menu + wiki default)

Unchanged column, changed comparator: menu/default-page order is `sortIndex` ascending, ties broken **alphabetically by `title`** (FR-002), applied only over pages where `context == NONE`. This replaces today's `findAllByOrderBySortIndexAscCreatedAtAsc()` tie-break (`createdAt`) — `createdAt`-based tie-breaking is no longer spec-compliant now that FR-002 mandates a title-based, fully deterministic order.

### Validation rules (create/update)

| Rule | Enforced by | On violation |
|---|---|---|
| `title` non-blank | `ContentPageService` (unchanged) | `ContentPageConflictException` → form re-render with `error` |
| `bodyMarkdown` non-blank | `ContentPageService` (unchanged) | `ContentPageConflictException` → form re-render with `error` |
| `sortIndex` present and numeric | `ContentPageController` (new — see research.md §7) | `ContentPageConflictException` → form re-render with `error`, submitted `title`/`bodyMarkdown`/`context` preserved |
| `context` one of the four enum values | Implicit — an unparseable value from the form falls back to `NONE` rather than erroring, since the control is a closed single-select the browser itself constrains | n/a |
| At most one page per non-`NONE` context | `ContentPageService` (generalised from `unsetPreviousHomepageIfNeeded`) + DB partial unique index | Previous holder's `context` silently reset to `NONE` as part of the same save — no error surfaced, matching today's homepage-swap behaviour |

### State transitions

```
[create]  --designate ctx≠NONE-->  holds ctx  --designate different page for ctx--> loses ctx (context := NONE)
   |                                    |
   |                                    +--delete (with confirmation)--> gone; ctx now unheld
   +--designate NONE (default)--> ordinary Info-menu page
```

No page-level status/workflow beyond the context field — Content Page has no other lifecycle state (spec Assumptions: pages, titles, and bodies are otherwise unchanged).

## Relationships

- **Content Page → Context Designation**: one Content Page optionally holds one `ContentPageContext` value other than `NONE`; a context value other than `NONE` is held by at most one Content Page. This is a 0..1-to-1 relationship expressed as a single enum column on `ContentPage`, not a separate table — "Context Designation" (spec Key Entities) is a conceptual entity, not a persisted one, exactly as the current `isHomepage` boolean already models the homepage case.
- **Content Page → Home / Registration form / Topic-creation form**: read-only, one-directional lookups (`findRenderedByContext(ctx)`) from `HomeController`, `RegistrationController`, and `TopicSelfServiceController` respectively; none of those controllers own or mutate `ContentPage`.

## Schema change (`schema.sql`)

```sql
ALTER TABLE content_pages ADD COLUMN IF NOT EXISTS context text NOT NULL DEFAULT 'NONE';
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'content_pages' AND column_name = 'is_homepage') THEN
        UPDATE content_pages SET context = 'HOMEPAGE' WHERE is_homepage;
        DROP INDEX IF EXISTS content_pages_is_homepage_key;
        ALTER TABLE content_pages DROP COLUMN is_homepage;
    END IF;
END $$;
CREATE UNIQUE INDEX IF NOT EXISTS content_pages_context_key ON content_pages (context) WHERE context <> 'NONE';
```

This follows the file's existing idempotent, additive-migration style (see the Feature 003/004 `ALTER TABLE ...
ADD COLUMN IF NOT EXISTS` blocks already in `schema.sql`) with one addition this file has not needed before:
this is its *first-ever column drop*. `schema.sql` runs on **every** application startup
(`spring.sql.init.mode=always`), so the backfill-and-drop must itself be idempotent — unlike a plain
`ADD COLUMN IF NOT EXISTS`, `UPDATE ... WHERE is_homepage` and `DROP COLUMN is_homepage` are only valid while
that column still exists. The `information_schema.columns` guard makes the whole block a genuine one-time
migration: it runs exactly once (the first startup against a database that still has `is_homepage`) and is a
silent no-op on every startup after that, instead of failing with "column is_homepage does not exist" on the
second boot.
