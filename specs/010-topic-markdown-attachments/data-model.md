# Phase 1 Data Model: Topic Markdown Descriptions & Attachments

## Topic (unchanged storage)

Existing entity `net.fabcelhaft.hackathonorganiser.topic.Topic`, table `topics`. **No column changes.** `description` is now *interpreted* as markdown at render time (FR-001, FR-007) — the stored text is exactly what the author typed, and the edit forms re-populate it verbatim. `approval_status` is untouched by any attachment operation (spec edge cases).

## Topic Attachment (new)

Entity `net.fabcelhaft.hackathonorganiser.topic.TopicAttachment`, table `topic_attachments`. Mirrors `ContentImage`'s shape (bytes in the row, DB-assigned id).

| Field | Column | Type | Notes |
|---|---|---|---|
| `id` | `id` | `UUID` | DB-assigned (`DEFAULT uuidv7()`); left `null` on construction like every other entity here. Part of the download URL. |
| `topicId` | `topic_id` | `UUID` | Owning Topic. FK → `topics (id)` **`ON DELETE CASCADE`** (FR-021). |
| `fileName` | `file_name` | `String` | Original client file name, trimmed, path components stripped (only the last segment after `/` or `\` is kept). Not unique — two attachments may share a name (spec edge case). Shown on screen via `th:text` (escaped) and in `Content-Disposition` via Spring's `ContentDisposition` (RFC 5987 encoding). |
| `contentType` | `content_type` | `String` | Declared type accepted by the allowlist (research §5), parameters stripped. Served back on download. |
| `byteSize` | `byte_size` | `int` | `data.length`; rendered human-readable on screen. |
| `data` | `data` | `byte[]` | `bytea`. **Never selected by list queries** — only by the download path. |
| `uploadedByUserId` | `uploaded_by_user_id` | `UUID` | Author or organiser who uploaded; FK → `users (id)`. Not displayed (spec: uploader is visible via the audit trail); kept for future use and for the audit entry's actor. |
| `createdAt` | `created_at` | `Instant` | Upload moment; drives list order (FR-018). |

Immutable after upload: no field is ever edited in place — an attachment is only added or removed.

### `TopicAttachmentSummary` (read model, not persisted)

```java
public record TopicAttachmentSummary(UUID id, UUID topicId, String fileName, String contentType, int byteSize, Instant createdAt) {}
```

Produced by a `DatabaseClient` query (`SELECT id, topic_id, file_name, content_type, byte_size, created_at FROM topic_attachments WHERE topic_id = :tid ORDER BY created_at, id`) so listings never load `data`. Used by both detail views and both edit screens.

### Validation rules (upload)

All enforced in `TopicAttachmentService.upload(...)` before any write; every violation is a `TopicAttachmentConflictException` whose message is shown on the re-rendered edit form (`error`).

| Rule | Message | Source |
|---|---|---|
| A file part is present (controller) | "Please choose a file to upload" | FR-017 |
| `data.length > 0` | "Please choose a file to upload" | FR-017 |
| `data.length <= 10 * 1024 * 1024` | "Attachments must be 10 MB or smaller" | FR-016 |
| current count for the Topic `< 10` | "A Topic can have at most 10 attachments" | FR-016 |
| extension in allowlist **and** declared type in that extension's set | "Only PDF, Word, Excel, PowerPoint, OpenDocument, text, markdown, PNG/JPEG/GIF/WebP images and ZIP files are allowed" | FR-016a, research §5 |
| file name non-blank after stripping path segments | "Please choose a file to upload" | edge case |

### Authorization rules (not validation — enforced at the controllers, service double-checks ownership)

| Operation | Allowed for | Enforced by |
|---|---|---|
| Upload / remove via `/topics/{id}/attachments…` | Topic author only | `TopicService.findVisibleTo` (404) then `failIfNotAuthor` (403) — identical to the existing edit routes; service additionally re-checks `createdByUserId == requester` when called with `requireAuthor = true` |
| Upload / remove via `/organiser/topics/{id}/attachments…` | Organisers | `SecurityConfig` `/organiser/**` role rule |
| Download | Anyone who can see the Topic | `TopicService.findVisibleTo` in the download controller; attachment looked up by `(id, topicId)` pair |

### State transitions

```
[upload accepted] --> stored (listed on detail + edit screens; downloadable while its Topic is visible to the viewer)
      |
      +--remove (author/organiser)--> gone (404 on its download URL)
      +--Topic deleted (future route)--> gone via ON DELETE CASCADE
```

No other lifecycle state.

## Audit Entry (extended enum only)

`AuditEventType` gains `ATTACHMENT_ADDED` and `ATTACHMENT_REMOVED`. Entries recorded by `TopicAttachmentService`:

| Event | `subjectType` / `subjectId` / `subjectLabel` | `oldValue` | `newValue` | `actionId` |
|---|---|---|---|---|
| `ATTACHMENT_ADDED` | `TOPIC` / topic id / topic name | `null` | file name | `null` |
| `ATTACHMENT_REMOVED` | `TOPIC` / topic id / topic name | file name | `null` | `null` |

`audit_entries.event_type` is `text` without a CHECK constraint — no schema change.

## Relationships

- **Topic 1 → 0..10 Topic Attachment**: composition; attachments cannot exist without their Topic (cascade delete). The upper bound is a service rule, not a DB constraint (a concurrent 11th upload racing the count check is accepted as a benign hackathon-scale edge; the spec sets no atomicity requirement).
- **Topic Attachment → User (uploader)**: reference only, for the audit actor and future display.
- **Topic Attachment → Audit Entry**: each add/remove writes one `TOPIC`-subject entry (feature 006 pattern: no `GROUP`/attachment subject type).

## Schema change (`schema.sql`)

Appended in the file's existing idempotent, additive style:

```sql
-- Feature 010: Topic Attachments (data-model.md "Topic Attachment"; FR-012–FR-021). Bytes live in
-- the row like content_images; ON DELETE CASCADE is what satisfies FR-021 for any future Topic
-- delete route (none exists today). Listings never select data — see TopicAttachmentService.
CREATE TABLE IF NOT EXISTS topic_attachments (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    topic_id uuid NOT NULL REFERENCES topics (id) ON DELETE CASCADE,
    file_name text NOT NULL,
    content_type text NOT NULL,
    byte_size integer NOT NULL,
    data bytea NOT NULL,
    uploaded_by_user_id uuid NOT NULL REFERENCES users (id),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS topic_attachments_topic_idx
    ON topic_attachments (topic_id, created_at);
```

`schema.sql` runs on every startup (`spring.sql.init.mode=always`); both statements are `IF NOT EXISTS`, so re-runs are no-ops.

## Configuration change (`application.yml`)

`spring.codec.max-in-memory-size: 6MB` → `11MB`, with the comment updated to name the 10 MB attachment cap as the largest service-validated upload (research §6). The 5 MB Content Image cap continues to be enforced by `ContentImageService` and is unaffected.
