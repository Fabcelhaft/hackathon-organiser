# Phase 1 Data Model: Homepage Overview, Self-Service Registration & Topics

Continues 002's conventions (research.md §4–§6 here explain the deviations): UUIDv7 PKs via Postgres's native
`uuid PRIMARY KEY DEFAULT uuidv7()` for every genuinely new entity table; `created_at`/`updated_at`
`timestamptz NOT NULL DEFAULT now()` on every table; composite-key "pure association"/"payload" tables
manipulated via `DatabaseClient`, never a single-`@Id` `ReactiveCrudRepository`, when a table has no natural
single-column key. `schema.sql` stays the single source of DDL, `CREATE TABLE IF NOT EXISTS` throughout, rerun
on every startup via `spring.sql.init.mode=always`.

## New Entities

### Organiser Settings — UUIDv7 PK, singleton (FR-023, FR-023a; research.md §4)

| Field | Type | Rules |
|---|---|---|
| `id` | UUID (v7) | PK, `DEFAULT uuidv7()` |
| `singleton` | boolean | `NOT NULL DEFAULT true`; `UNIQUE` — guarantees exactly one row ever exists |
| `self_registration_enabled` | boolean | `NOT NULL DEFAULT true` (FR-023a default) |
| `self_revocation_enabled` | boolean | `NOT NULL DEFAULT true` (FR-023a default) |
| `topic_approval_required` | boolean | `NOT NULL DEFAULT false` (FR-023a default) |
| `updated_at` | timestamptz | `NOT NULL DEFAULT now()` |

No `created_at`: the row is created exactly once by the schema seed (research.md §4) and never re-created: an
`updated_at`-only audit trail is sufficient. Read on every gated action (register/revoke/propose) — no
in-memory caching, so FR-023/SC-003's "next request" latency is zero extra staleness.

### Content Page — UUIDv7 PK (FR-018–FR-020a; research.md §5, §6)

| Field | Type | Rules |
|---|---|---|
| `id` | UUID (v7) | PK, `DEFAULT uuidv7()` |
| `title` | text | `NOT NULL` — renders as the page's top-level heading (FR-036) |
| `body_markdown` | text | `NOT NULL` — raw Organiser-authored source; sanitized HTML is derived at render time, never stored (single sanitization boundary, research.md §1) |
| `sort_index` | integer | `NOT NULL DEFAULT 0` — Organiser-set (FR-020a); Info section orders ascending by this, with `created_at` as the documented tie-break (spec Edge Cases: duplicate indices) |
| `is_homepage` | boolean | `NOT NULL DEFAULT false` |
| `created_at`, `updated_at` | timestamptz | |

`CREATE UNIQUE INDEX content_pages_is_homepage_key ON content_pages (is_homepage) WHERE is_homepage;` — the
same partial-unique-index pattern 002 uses for `groups_topic_id_active_key`, guaranteeing "exactly one Content
Page may be designated... the homepage page" (FR-019) is enforced at the database level, not just by
application logic. Designating a new page as the homepage page must first un-set the previous one in the same
transaction/statement (`ContentPageService`), or the partial index rejects the write.

Info section listing query: `findAllByOrderBySortIndexAscCreatedAtAsc()` — excludes the page currently flagged
`is_homepage = true` from `/info` (FR-018: "pages other than the homepage's right-column content"), though
that same page remains individually viewable — an Organiser may still want to link to it, and nothing in the
spec says it must be hidden from direct view, only excluded from the Info *listing*.

### Content Image — UUIDv7 PK (FR-024–FR-029; research.md §2, §3)

| Field | Type | Rules |
|---|---|---|
| `id` | UUID (v7) | PK, `DEFAULT uuidv7()` — also the value embedded in the `/content-images/{id}` stable reference (FR-025) |
| `alt_text` | text | `NOT NULL`, non-blank enforced in `ContentImageService` (FR-025a) |
| `content_type` | text | `NOT NULL` — one of `image/png`, `image/jpeg`, `image/gif`, `image/webp` (FR-029), validated in `ContentImageService` before insert |
| `byte_size` | integer | `NOT NULL` — the uploaded size in bytes, `<= 5_242_880` (5 MB, FR-029) validated before insert |
| `data` | bytea | `NOT NULL` — the raw image bytes (FR-024) |
| `created_at`, `updated_at` | timestamptz | `updated_at` only ever changes when `alt_text` is edited (FR-025b) — `data`/`content_type`/`byte_size` are immutable after upload |

No relational FK from Content Page to Content Image: the reference lives inside `content_pages.body_markdown`
as a literal path string, not a foreign key column (research.md §3) — deletion-blocking is a query-time
substring search, not a constraint-enforced relationship.

## Modified Entities

### Topic (extends 002's `topics` table — FR-009a, FR-012–FR-016; research.md §6)

New column: `approval_status text NOT NULL DEFAULT 'APPROVED'` (`TopicApprovalStatus`: `PENDING` | `APPROVED`).
Set once at creation by `TopicService.propose(...)`, based on `OrganiserSettings.topicApprovalRequired` read at
that instant (FR-013); never changed automatically afterward, only via the explicit Organiser approve action
(FR-014, FR-016 — disabling the setting is not retroactive).

**Supersedes 002's `created_by_user_id` immutability.** 002's `Topic.java` doc comment states the creator "is
set once, at creation, and never reassigned afterward" and 002's `TopicService.update(...)` has no parameter
for it at all. FR-015 in *this* feature explicitly requires an Organiser be able to reassign a Topic's author.
This feature adds a **separate** Organiser-only method (e.g. `TopicService.reassignAuthor(UUID topicId, UUID
newAuthorUserId)`) rather than exposing `created_by_user_id` on the existing participant-facing `update(...)`
path — so the immutability guarantee 002 documented still holds for every caller except this one, explicit,
Organiser-gated route. `Topic.java`'s class comment is updated to note the exception and point to this file.

Visibility (FR-012a) and 3-group ordering (FR-009a) are computed read-side in `TopicService`, not stored
columns — see research.md §6.

### Participant (002's `participants` table — no schema change)

Behavioral additions only: `ParticipantService.selfRegister(UUID userId)` and `selfRevoke(UUID participantId)`.
Both reject if the relevant `OrganiserSettings` toggle (`selfRegistrationEnabled` / `selfRevocationEnabled`) is
false, re-checked on every call per FR-006/FR-023 ("regardless of what the requesting user's page displayed at
load time") — not read once and cached.

**`selfRegister` does *not* reuse 002's `register(UUID userId)` as-is** — that method rejects *any* pre-existing
Participant record outright, which is correct for the organiser-driven flow it serves but wrong here: FR-007
explicitly requires "that same record" be reactivated to `Active` through a subsequent registration action.
`selfRegister` therefore branches on the existing record's status, not merely its existence:

| Existing Participant record | `selfRegister` behavior |
|---|---|
| None | Create a new record, `status = ACTIVE` (FR-003) |
| `status = ACTIVE` | No-op, record unchanged (Edge Cases: double-submit MUST NOT create a duplicate or otherwise mutate the record) |
| `status = REVOKED` (or any other non-`ACTIVE` status) | Update the *same* record's `status` to `ACTIVE` (FR-007) — no new row |

`selfRevoke(UUID participantId)`: on success, sets `status = REVOKED` and removes the Participant's current
Group membership per FR-007a — research.md §10.

### Group (002's `groups`/`group_members` tables — no schema change)

One new public method, `GroupService.findActiveGroupForParticipant(UUID participantId)`, used by
`ParticipantService.selfRevoke(...)` (research.md §10). No new columns, no new invariants.

## Schema additions (illustrative DDL — final statements land in `schema.sql`)

```sql
-- Organiser Settings: singleton row (research.md §4)
CREATE TABLE IF NOT EXISTS organiser_settings (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    singleton boolean NOT NULL DEFAULT true,
    self_registration_enabled boolean NOT NULL DEFAULT true,
    self_revocation_enabled boolean NOT NULL DEFAULT true,
    topic_approval_required boolean NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS organiser_settings_singleton_key ON organiser_settings (singleton);
INSERT INTO organiser_settings (singleton) VALUES (true) ON CONFLICT (singleton) DO NOTHING;

-- Content Pages (research.md §5, §6)
CREATE TABLE IF NOT EXISTS content_pages (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    title text NOT NULL,
    body_markdown text NOT NULL,
    sort_index integer NOT NULL DEFAULT 0,
    is_homepage boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS content_pages_is_homepage_key ON content_pages (is_homepage) WHERE is_homepage;

INSERT INTO content_pages (title, body_markdown, sort_index, is_homepage)
SELECT 'Welcome', '# Welcome to the Hackathon', 0, true
WHERE NOT EXISTS (SELECT 1 FROM content_pages);

-- Content Images (research.md §2, §3)
CREATE TABLE IF NOT EXISTS content_images (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    alt_text text NOT NULL,
    content_type text NOT NULL,
    byte_size integer NOT NULL,
    data bytea NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- Topic approval state (research.md §6) — added to 002's existing `topics` table
ALTER TABLE topics ADD COLUMN IF NOT EXISTS approval_status text NOT NULL DEFAULT 'APPROVED';
```

## Entity relationship summary

```text
User 1───0..1 Participant  (002, unchanged)
User 1───* Topic            (created_by_user_id — reassignable only via TopicService.reassignAuthor, FR-015)
Topic 1───0..1 Group         (002, unchanged)
Participant 0..1───0..1 Group (via group_members.active, 002, unchanged) — selfRevoke() clears this (FR-007a)
OrganiserSettings (singleton, no relationships — read by ParticipantService, TopicService)
ContentPage 0..1 "is the homepage page" (partial-unique invariant)
ContentPage *───* ContentImage  (informal: a literal /content-images/{id} path string inside body_markdown,
                                  not an FK — research.md §3)
```
